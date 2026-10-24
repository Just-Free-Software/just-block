uniffi::setup_scaffolding!();

mod proxy;

mod doh3;
mod quic;
#[cfg(test)]
mod wire_test_util;

use doh3::Doh3Client;
use etherparse::{SlicedPacket, TransportSlice};
use proxy::{
    build_null_response, build_servfail_response, create_forwarded_response, create_tcp_rst,
    parse_query,
};
use quic::{DoqClient, QuicEndpoint};
use radix_trie::Trie;
use std::collections::HashMap;
use std::os::fd::RawFd;
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::Duration;
use tokio::io::unix::AsyncFd;
use tokio::runtime::Runtime;
use tokio::sync::Mutex as TokioMutex;
use tokio::sync::{Notify, mpsc};
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

fn get_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Failed to build Tokio runtime")
    })
}

/// Hard upper bound on how long any single forwarded DNS query may take
/// (all upstream attempts end to end, dial + query + response delivery).
const QUERY_DEADLINE: Duration = Duration::from_secs(3);

/// Per-upstream budget for establishing a connection, so a hanging primary
/// still leaves time in [QUERY_DEADLINE] for backup upstreams to answer.
const DIAL_BUDGET: Duration = Duration::from_secs(1);

/// After a dial or query failure, an upstream is skipped for this long so
/// queries fail over to the next upstream immediately instead of burning
/// the dial budget on a known-dead server.
const FAILED_UPSTREAM_BACKOFF: Duration = Duration::from_secs(30);

/// Tracks the currently-running proxy task. `generation` disambiguates an old
/// task's cleanup from a new task's registration so a stale exit can never
/// clobber a fresh run's token.
#[derive(Default)]
struct RunSlot {
    generation: u64,
    token: Option<CancellationToken>,
}

/// A backup upstream reachable over DoH3 (DNS over HTTP/3, RFC 8484 body
/// over an HTTP/3 POST). Cloudflare and Google both offer this but not DoQ.
#[derive(uniffi::Record)]
pub struct Doh3Backup {
    pub addr_v4: Option<String>,
    pub addr_v6: Option<String>,
    pub sni_hostname: String,
    pub path: String,
}

#[derive(uniffi::Object)]
pub struct DnsProxy {
    tun_fd: i32,
    quic_fds: Vec<i32>,
    blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    allowlist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    pauses: Arc<RwLock<Trie<Vec<u8>, i64>>>,
    run_slot: Arc<Mutex<RunSlot>>,
    upstreams: Vec<Arc<Upstream>>,
}

#[uniffi::export]
impl DnsProxy {
    #[uniffi::constructor]
    pub fn new(
        tun_fd: i32,
        upstream_v4: Option<String>,
        upstream_v6: Option<String>,
        sni_hostname: String,
        doh3_backups: Vec<Doh3Backup>,
    ) -> Arc<Self> {
        let _guard = get_runtime().enter();
        let upstream_v4 = upstream_v4.and_then(|s| match s.parse::<std::net::IpAddr>() {
            Ok(ip) if ip.is_ipv4() => Some(ip),
            _ => {
                log_trace!(&format!("invalid IPv4 upstream {:?}", s));
                None
            }
        });
        let upstream_v6 = upstream_v6.and_then(|s| match s.parse::<std::net::IpAddr>() {
            Ok(ip) if ip.is_ipv6() => Some(ip),
            _ => {
                log_trace!(&format!("invalid IPv6 upstream {:?}", s));
                None
            }
        });

        let mut upstreams: Vec<Arc<Upstream>> = Vec::new();
        let mut quic_fds: Vec<i32> = Vec::new();

        // Primary upstream: DoQ (RFC 9250).
        if upstream_v4.is_some() || upstream_v6.is_some() {
            let endpoint_v4 = upstream_v4.as_ref().map(|_| {
                Arc::new(QuicEndpoint::new_doq_v4().expect("Failed to create DoQ IPv4 endpoint"))
            });
            if let Some(ep) = &endpoint_v4 {
                quic_fds.push(ep.get_socket_fd());
            }
            let endpoint_v6 = upstream_v6.as_ref().map(|_| {
                Arc::new(QuicEndpoint::new_doq_v6().expect("Failed to create DoQ IPv6 endpoint"))
            });
            if let Some(ep) = &endpoint_v6 {
                quic_fds.push(ep.get_socket_fd());
            }
            upstreams.push(Arc::new(Upstream {
                kind: UpstreamKind::Doq {
                    endpoint_v4,
                    endpoint_v6,
                    ip_v4: upstream_v4,
                    ip_v6: upstream_v6,
                },
                sni: sni_hostname,
                shared: Arc::new(UpstreamShared::default()),
            }));
        }

        // Backup upstreams: DoH3 over a shared h3-ALPN endpoint pair.
        if !doh3_backups.is_empty() {
            let any_v4 = doh3_backups.iter().any(|b| parse_ip(&b.addr_v4).is_some());
            let any_v6 = doh3_backups.iter().any(|b| parse_ip(&b.addr_v6).is_some());
            let endpoint_v4 = any_v4.then(|| {
                Arc::new(QuicEndpoint::new_h3_v4().expect("Failed to create h3 IPv4 endpoint"))
            });
            if let Some(ep) = &endpoint_v4 {
                quic_fds.push(ep.get_socket_fd());
            }
            let endpoint_v6 = any_v6.then(|| {
                Arc::new(QuicEndpoint::new_h3_v6().expect("Failed to create h3 IPv6 endpoint"))
            });
            if let Some(ep) = &endpoint_v6 {
                quic_fds.push(ep.get_socket_fd());
            }
            for backup in doh3_backups {
                let ip_v4 = parse_ip(&backup.addr_v4);
                let ip_v6 = parse_ip(&backup.addr_v6);
                if ip_v4.is_none() && ip_v6.is_none() {
                    log_trace!(&format!(
                        "skipping DoH3 backup {:?} — no usable address",
                        backup.sni_hostname
                    ));
                    continue;
                }
                upstreams.push(Arc::new(Upstream {
                    kind: UpstreamKind::Doh3 {
                        endpoint_v4: endpoint_v4.clone(),
                        endpoint_v6: endpoint_v6.clone(),
                        ip_v4,
                        ip_v6,
                        path: backup.path,
                    },
                    sni: backup.sni_hostname,
                    shared: Arc::new(UpstreamShared::default()),
                }));
            }
        }

        Arc::new(Self {
            tun_fd,
            quic_fds,
            blocklist: Arc::new(RwLock::new(Trie::new())),
            allowlist: Arc::new(RwLock::new(Trie::new())),
            pauses: Arc::new(RwLock::new(Trie::new())),
            run_slot: Arc::new(Mutex::new(RunSlot::default())),
            upstreams,
        })
    }

    /// All QUIC socket fds owned by this proxy (DoQ + DoH3). Every one must
    /// be protected from the VPN tunnel by the Android side before use.
    pub fn get_quic_fds(&self) -> Vec<i32> {
        self.quic_fds.clone()
    }

    /// Cancels the running proxy task, if any. Idempotent: calling stop()
    /// when no task is running is a no-op. Unlike the previous design, the
    /// proxy can be started again afterwards — start() mints a fresh
    /// cancellation token per run.
    pub fn stop(&self) {
        let mut slot = self.lock_run_slot();
        if let Some(token) = slot.token.take() {
            token.cancel();
        }
    }

    /// Starts the proxy task. Idempotent: if a task is already running
    /// (and has not been stopped), this is a no-op — it can never spawn a
    /// second reader on the same TUN fd.
    pub fn start(&self) {
        let (generation, cancel_token) = {
            let mut slot = self.lock_run_slot();
            if let Some(token) = &slot.token {
                if !token.is_cancelled() {
                    return;
                }
            }
            slot.generation += 1;
            let token = CancellationToken::new();
            slot.token = Some(token.clone());
            (slot.generation, token)
        };

        let tun_fd = self.tun_fd;
        let blocklist = self.blocklist.clone();
        let allowlist = self.allowlist.clone();
        let pauses = self.pauses.clone();
        let upstreams = self.upstreams.clone();
        let run_slot = self.run_slot.clone();

        get_runtime().spawn(async move {
            run_proxy(tun_fd, upstreams, blocklist, allowlist, pauses, cancel_token).await;
            // Clear the slot on exit (both natural exit and cancellation) so
            // a subsequent start() works. The generation check ensures a
            // stale task never clears a newer run's token.
            let mut slot = run_slot.lock().unwrap_or_else(|e| e.into_inner());
            if slot.generation == generation {
                slot.token = None;
            }
        });
    }

    pub fn update_blocklist(&self, domains: Vec<String>) {
        #[cfg_attr(
            not(all(target_os = "android", feature = "dns-trace")),
            allow(unused_variables)
        )]
        let requested = domains.len();
        let trie = domain_trie(domains);
        log_trace!(&format!(
            "update_blocklist: {} entries requested, {} inserted",
            requested,
            // Fully qualified: a `use` of the trait would be unused (and
            // warn) in builds where log_trace! compiles to nothing.
            radix_trie::TrieCommon::len(&trie)
        ));
        *self.blocklist.write().unwrap_or_else(|e| e.into_inner()) = trie;
    }

    /// Replaces the allowlist (user-whitelisted domains). An allowlisted
    /// domain — and every subdomain of it — is always forwarded, overriding
    /// both the blocklist and expired pauses.
    pub fn update_allowlist(&self, domains: Vec<String>) {
        log_trace!(&format!("update_allowlist: {} entries", domains.len()));
        let trie = domain_trie(domains);
        *self.allowlist.write().unwrap_or_else(|e| e.into_inner()) = trie;
    }

    /// Replaces the pause set. `pauses` maps lowercased domains to epoch-millis
    /// expiry times; `i64::MAX` means an indefinite pause. Pauses are enforced
    /// lazily at query time (see [pause_expired]), so they keep working even
    /// when the Android UI process that owns the countdown timer is gone.
    pub fn update_pauses(&self, pauses: HashMap<String, i64>) {
        log_trace!(&format!("update_pauses: {} entries", pauses.len()));
        let trie = pauses_trie(pauses);
        *self.pauses.write().unwrap_or_else(|e| e.into_inner()) = trie;
    }
}

impl DnsProxy {
    fn lock_run_slot(&self) -> std::sync::MutexGuard<'_, RunSlot> {
        self.run_slot.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Convert a domain string to a reversed, lowercased wire-format key for the trie.
/// "example.com" → \x03com\x07example  (no trailing \x00)
///
/// Reversed so that `get_ancestor_value` can match subdomains:
/// blocking "example.com" (key: \x03com\x07example) will match
/// a query for "ads.example.com" (key: \x03com\x07example\x03ads)
/// because the parent key is a byte-prefix of the child key.
///
/// Returns `None` if any label exceeds the 63-byte DNS label limit or the
/// name exceeds 255 bytes, so malformed blocklist entries are rejected
/// rather than silently producing truncated/garbage trie keys.
pub(crate) fn domain_to_wire_format(domain: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let parts: Vec<&str> = domain.split('.').filter(|p| !p.is_empty()).collect();
    for part in parts.iter().rev() {
        if part.len() > 63 || out.len() + 1 + part.len() > 255 {
            return None;
        }
        out.push(part.len() as u8);
        out.extend_from_slice(part.to_ascii_lowercase().as_bytes());
    }
    Some(out)
}

/// Builds a reversed wire-format trie from a domain list, skipping entries
/// that aren't valid DNS names.
pub(crate) fn domain_trie(domains: Vec<String>) -> Trie<Vec<u8>, ()> {
    let mut trie = Trie::new();
    for domain in domains {
        if let Some(wire_format) = domain_to_wire_format(&domain) {
            trie.insert(wire_format, ());
        }
    }
    trie
}

/// Converts a domain → expiry map into a reversed wire-format trie, using the
/// same key encoding as the blocklist trie so ancestor matching is identical.
pub(crate) fn pauses_trie(pauses: HashMap<String, i64>) -> Trie<Vec<u8>, i64> {
    let mut trie = Trie::new();
    for (domain, expires_at) in pauses {
        if let Some(wire_format) = domain_to_wire_format(&domain) {
            trie.insert(wire_format, expires_at);
        }
    }
    trie
}

/// Pure expiry predicate: true when a pause lapsed by `now_ms`.
fn pause_expired(now_ms: i64, expiry: i64) -> bool {
    now_ms >= expiry
}

/// Query-path pause check. Ancestor matching mirrors the blocklist trie
/// (pausing "example.com" covers "sub.example.com"). The clock is only read
/// when a pause actually covers `trie_key`, so unpause-covered queries pay
/// nothing beyond the trie lookup.
fn pause_lazily_expired(pauses: &Trie<Vec<u8>, i64>, trie_key: &[u8]) -> bool {
    match pauses.get_ancestor_value(trie_key) {
        Some(&expiry) => pause_expired(now_epoch_ms(), expiry),
        None => false,
    }
}

/// Query-path block decision. The allowlist wins over everything: a
/// whitelisted domain (or any subdomain of it) is never blocked, even if
/// it or a parent is on the blocklist or has a lapsed pause.
fn should_block(
    blocklist: &Trie<Vec<u8>, ()>,
    allowlist: &Trie<Vec<u8>, ()>,
    pauses: &Trie<Vec<u8>, i64>,
    trie_key: &[u8],
) -> bool {
    if allowlist.get_ancestor_value(trie_key).is_some() {
        return false;
    }
    blocklist.get_ancestor_value(trie_key).is_some() || pause_lazily_expired(pauses, trie_key)
}

fn now_epoch_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(i64::MAX)
}

/// DNS trace logging. Compiles to nothing (no format!, no allocation, no
/// qname parsing) unless built for Android with the `dns-trace` feature,
/// which CMake enables for debug builds only.
macro_rules! log_trace {
    ($msg:expr) => {
        #[cfg(all(target_os = "android", feature = "dns-trace"))]
        crate::android_log::write($msg);
    };
}
use log_trace;

/// Debug logging to Android logcat (tag: justblock-rust). Temporarily
/// wired into log_trace below for on-device regression diagnosis.
#[cfg(all(target_os = "android", feature = "dns-trace"))]
mod android_log {
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }

    const LOG_DEBUG: i32 = 3;

    pub fn write(msg: &str) {
        let tag = c"justblock-rust";
        if let Ok(text) = std::ffi::CString::new(msg) {
            unsafe {
                __android_log_write(
                    LOG_DEBUG,
                    tag.as_ptr() as *const u8,
                    text.as_ptr() as *const u8,
                );
            }
        }
    }
}

/// Best-effort dotted qname from a raw DNS payload, for debug logging only.
#[cfg_attr(
    not(all(target_os = "android", feature = "dns-trace")),
    allow(dead_code)
)]
fn qname_str(payload: &[u8]) -> String {
    domain::base::message::Message::from_slice(payload)
        .ok()
        .and_then(|m| m.sole_question().ok())
        .map(|q| q.qname().to_string())
        .unwrap_or_else(|| "<malformed>".into())
}

// ---------------------------------------------------------------------------
// Upstream pool & connection state
// ---------------------------------------------------------------------------

/// One established DNS session with an upstream: either DoQ or DoH3.
enum DnsSession {
    Doq(DoqClient),
    Doh3(Doh3Client),
}

impl DnsSession {
    fn is_alive(&self) -> bool {
        match self {
            DnsSession::Doq(c) => c.is_alive(),
            DnsSession::Doh3(c) => c.is_alive(),
        }
    }

    async fn send_query(
        &self,
        payload: &[u8],
    ) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
        match self {
            DnsSession::Doq(c) => c.send_query(payload).await,
            DnsSession::Doh3(c) => c.send_query(payload).await,
        }
    }
}

/// Shared upstream-connection state for one proxy run.
///
/// `Connecting` makes connection establishment explicit: concurrent queries
/// wait on `changed` (no polling loop, no timestamp-as-lock), and exactly one
/// task performs the dial. Everyone else is woken when it succeeds or fails.
/// `backoff_until` suppresses dialing for FAILED_UPSTREAM_BACKOFF after a
/// failed attempt so a known-dead upstream doesn't burn the dial budget.
enum ConnState {
    /// No connection; the next query that needs one dials.
    Idle,
    /// A connection attempt is in flight.
    Connecting,
    /// An established, presumably-alive connection.
    Connected(Arc<DnsSession>),
}

impl Default for ConnState {
    fn default() -> Self {
        ConnState::Idle
    }
}

#[derive(Default)]
struct UpstreamShared {
    state: TokioMutex<ConnState>,
    changed: Notify,
    backoff_until: TokioMutex<Option<tokio::time::Instant>>,
}

impl UpstreamShared {
    /// Mark the current connection dead so the next query re-dials.
    /// Never interrupts an in-flight dial.
    async fn invalidate(&self) {
        let mut st = self.state.lock().await;
        if matches!(&*st, ConnState::Connected(_)) {
            *st = ConnState::Idle;
        }
        self.changed.notify_waiters();
    }

    /// True when this upstream failed recently and should be skipped.
    async fn in_backoff(&self, now: tokio::time::Instant) -> bool {
        let until = self.backoff_until.lock().await;
        matches!(*until, Some(t) if now < t)
    }

    async fn mark_failed(&self, now: tokio::time::Instant) {
        *self.backoff_until.lock().await = Some(now + FAILED_UPSTREAM_BACKOFF);
    }
}

/// How to reach one upstream: transport kind plus its endpoints/addresses.
enum UpstreamKind {
    Doq {
        endpoint_v4: Option<Arc<QuicEndpoint>>,
        endpoint_v6: Option<Arc<QuicEndpoint>>,
        ip_v4: Option<std::net::IpAddr>,
        ip_v6: Option<std::net::IpAddr>,
    },
    Doh3 {
        endpoint_v4: Option<Arc<QuicEndpoint>>,
        endpoint_v6: Option<Arc<QuicEndpoint>>,
        ip_v4: Option<std::net::IpAddr>,
        ip_v6: Option<std::net::IpAddr>,
        path: String,
    },
}

struct Upstream {
    kind: UpstreamKind,
    sni: String,
    shared: Arc<UpstreamShared>,
}

impl Upstream {
    async fn connect(&self) -> Result<DnsSession, Box<dyn std::error::Error + Send + Sync>> {
        let (endpoint_v4, endpoint_v6, ip_v4, ip_v6) = match &self.kind {
            UpstreamKind::Doq {
                endpoint_v4,
                endpoint_v6,
                ip_v4,
                ip_v6,
            } => (endpoint_v4, endpoint_v6, ip_v4, ip_v6),
            UpstreamKind::Doh3 {
                endpoint_v4,
                endpoint_v6,
                ip_v4,
                ip_v6,
                ..
            } => (endpoint_v4, endpoint_v6, ip_v4, ip_v6),
        };
        let conn = dial_connection(endpoint_v4, endpoint_v6, ip_v4, ip_v6, &self.sni).await?;
        match &self.kind {
            UpstreamKind::Doq { .. } => Ok(DnsSession::Doq(DoqClient::new(conn))),
            UpstreamKind::Doh3 { path, .. } => {
                let client = Doh3Client::new(conn, self.sni.clone(), path.clone()).await?;
                Ok(DnsSession::Doh3(client))
            }
        }
    }
}

fn parse_ip(s: &Option<String>) -> Option<std::net::IpAddr> {
    s.as_deref().and_then(|s| s.parse().ok())
}

/// Returns a live session with this upstream, dialing (or waiting for an
/// in-flight dial) as needed. Returns `None` if no session is available by
/// `deadline`.
async fn get_session(
    upstream: &Upstream,
    deadline: tokio::time::Instant,
) -> Option<Arc<DnsSession>> {
    let shared = &upstream.shared;
    loop {
        // Register for change notification BEFORE inspecting state so a
        // state transition between inspection and waiting cannot be missed.
        let notified = shared.changed.notified();

        enum Step {
            Ready(Arc<DnsSession>),
            WaitForDial,
            Dial,
        }
        let step = {
            let st = shared.state.lock().await;
            match &*st {
                ConnState::Connected(c) if c.is_alive() => Step::Ready(c.clone()),
                ConnState::Connecting => Step::WaitForDial,
                ConnState::Idle | ConnState::Connected(_) => Step::Dial,
            }
        };

        match step {
            Step::Ready(c) => return Some(c),
            Step::WaitForDial => {
                tokio::select! {
                    _ = notified => continue,
                    _ = tokio::time::sleep_until(deadline) => return None,
                }
            }
            Step::Dial => {
                // Claim the Connecting slot; re-check in case another task
                // claimed it (or finished dialing) in the meantime.
                {
                    let mut st = shared.state.lock().await;
                    match &*st {
                        ConnState::Connected(c) if c.is_alive() => return Some(c.clone()),
                        ConnState::Connecting => continue,
                        _ => *st = ConnState::Connecting,
                    }
                }

                let result = tokio::time::timeout_at(deadline, upstream.connect()).await;

                let mut st = shared.state.lock().await;
                match result {
                    Ok(Ok(session)) => {
                        let arc = Arc::new(session);
                        *st = ConnState::Connected(arc.clone());
                        shared.changed.notify_waiters();
                        return Some(arc);
                    }
                    Ok(Err(_)) | Err(_) => {
                        *st = ConnState::Idle;
                        shared.changed.notify_waiters();
                        return None;
                    }
                }
            }
        }
    }
}

/// Happy-Eyeballs dial: prefer IPv6, fall back to IPv4 after 50ms if both
/// families are configured; otherwise dial whichever exists. Returns the
/// raw QUIC connection; the caller wraps it in a DoQ or DoH3 session.
async fn dial_connection(
    endpoint_v4: &Option<Arc<QuicEndpoint>>,
    endpoint_v6: &Option<Arc<QuicEndpoint>>,
    upstream_v4: &Option<std::net::IpAddr>,
    upstream_v6: &Option<std::net::IpAddr>,
    sni: &str,
) -> Result<quinn::Connection, Box<dyn std::error::Error + Send + Sync>> {
    let fut_v6 = async {
        match (endpoint_v6, upstream_v6) {
            (Some(ep), Some(ip)) => ep.connect(ip, sni).await,
            _ => Err("No IPv6 upstream".into()),
        }
    };
    let fut_v4 = async {
        match (endpoint_v4, upstream_v4) {
            (Some(ep), Some(ip)) => ep.connect(ip, sni).await,
            _ => Err("No IPv4 upstream".into()),
        }
    };

    if upstream_v6.is_some() && upstream_v4.is_some() {
        let mut fut6 = std::pin::pin!(fut_v6);
        let mut fut4 = std::pin::pin!(fut_v4);
        tokio::select! {
            res = &mut fut6 => {
                if res.is_ok() { return res; }
                fut4.await
            }
            _ = tokio::time::sleep(Duration::from_millis(50)) => {
                tokio::select! {
                    res = &mut fut6 => {
                        if res.is_ok() { return res; }
                        fut4.await
                    }
                    res = &mut fut4 => {
                        if res.is_ok() { return res; }
                        fut6.await
                    }
                }
            }
        }
    } else if upstream_v6.is_some() {
        fut_v6.await
    } else {
        fut_v4.await
    }
}

// ---------------------------------------------------------------------------
// TUN I/O
// ---------------------------------------------------------------------------

/// Async write of one packet to the TUN fd, handling EAGAIN (fd is
/// nonblocking), EINTR, and partial writes. Returns Err on a hard failure
/// (e.g. TUN closed), which should terminate the proxy loop.
async fn write_tun(fd: &AsyncFd<RawFd>, packet: &[u8]) -> std::io::Result<()> {
    let mut remaining = packet;
    loop {
        let mut guard = fd.writable().await?;
        let n = unsafe {
            libc::write(
                *fd.get_ref(),
                remaining.as_ptr() as *const libc::c_void,
                remaining.len(),
            )
        };
        if n > 0 {
            remaining = &remaining[n as usize..];
            if remaining.is_empty() {
                return Ok(());
            }
            // More to write: re-arm the readiness interest.
            guard.clear_ready();
        } else if n < 0 {
            let err = std::io::Error::last_os_error();
            match err.kind() {
                std::io::ErrorKind::WouldBlock => guard.clear_ready(),
                std::io::ErrorKind::Interrupted => {}
                _ => return Err(err),
            }
        } else {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "TUN write returned 0",
            ));
        }
    }
}

// ---------------------------------------------------------------------------
// Proxy main loop
// ---------------------------------------------------------------------------

async fn run_proxy(
    tun_fd: i32,
    upstreams: Vec<Arc<Upstream>>,
    blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    allowlist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    pauses: Arc<RwLock<Trie<Vec<u8>, i64>>>,
    cancel_token: CancellationToken,
) {
    log_trace!("run_proxy started");

    // Set O_NONBLOCK before registering with the reactor, and check for
    // errors: a failed F_GETFL (-1) must not be OR'd into F_SETFL.
    let flags = unsafe { libc::fcntl(tun_fd, libc::F_GETFL) };
    if flags == -1 {
        log_trace!(&format!(
            "F_GETFL failed: {}",
            std::io::Error::last_os_error()
        ));
        return;
    }
    if unsafe { libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } == -1 {
        log_trace!(&format!(
            "F_SETFL failed: {}",
            std::io::Error::last_os_error()
        ));
        return;
    }

    // The TUN fd may still be registered with the Tokio reactor by a proxy
    // task from a previous run on the same fd (the Android side recycles
    // the TUN across DnsProxy instances). EEXIST here is transient — it
    // clears once the old task's AsyncFd is dropped — so retry briefly
    // rather than treating it as fatal.
    let mut buf = vec![0u8; 65536];
    let mut async_fd_opt = None;
    for _ in 0..500 {
        // SAFETY: `tun_fd` is a valid, open file descriptor obtained from the
        // Android VpnService, it is set nonblocking above, and the Android side
        // does not close or replace it for the lifetime of this proxy task.
        match unsafe { AsyncFd::register(tun_fd) } {
            Ok(fd) => {
                async_fd_opt = Some(fd);
                break;
            }
            Err(e) => {
                let (_, io_err) = e.into_parts();
                if io_err.raw_os_error() == Some(libc::EEXIST) {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                } else {
                    log_trace!(&format!("AsyncFd::register failed: {}", io_err));
                    return;
                }
            }
        }
    }

    let async_fd = match async_fd_opt {
        Some(fd) => fd,
        None => {
            log_trace!("AsyncFd::new failed with EEXIST after 5s");
            return;
        }
    };

    let semaphore = Arc::new(tokio::sync::Semaphore::new(100));

    // Bounded response channel. Query tasks use send().await (bounded
    // backpressure; the channel closes when the loop exits, so sends cannot
    // hang forever). The read loop uses try_send with an explicit drop
    // policy, since it must never block on a full channel.
    let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1000);

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => {
                break;
            }
            Some(packet) = rx.recv() => {
                if let Err(_e) = write_tun(&async_fd, &packet).await {
                    // A failed write drops this response but must not kill
                    // the proxy — read errors handle a genuinely dead TUN.
                    log_trace!(&format!("TUN write failed (dropping packet): {}", _e));
                }
            }
            res = async_fd.readable() => {
                let mut guard = match res {
                    Ok(g) => g,
                    Err(_) => continue,
                };

        match unsafe { libc::read(tun_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) } {
            n if n > 0 => {
                let n = n as usize;
                let pkt = &buf[..n];

                if let Ok(sliced) = SlicedPacket::from_ip(pkt) {
                    if let Some(TransportSlice::Udp(udp)) = sliced.transport.as_ref() {
                        if udp.destination_port() == 53 {
                            let payload = udp.payload();
                            if let Some(trie_key) = parse_query(payload) {
                                let blocked = should_block(
                                    &blocklist.read().unwrap_or_else(|e| e.into_inner()),
                                    &allowlist.read().unwrap_or_else(|e| e.into_inner()),
                                    &pauses.read().unwrap_or_else(|e| e.into_inner()),
                                    &trie_key,
                                );

                                if blocked {
                                    log_trace!(&format!("BLOCKED {} -> NXDOMAIN", qname_str(payload)));
                                    // Re-parse is intentional and cheap: the builders need
                                    // the full Message, not just the derived keys.
                                    if let Ok(query) = domain::base::message::Message::from_slice(payload) {
                                        if let Some(null_resp) = build_null_response(&query) {
                                            // Wrap in IP/UDP headers before writing to the TUN —
                                            // a bare DNS payload is not a valid TUN packet.
                                            if let Some(resp) = create_forwarded_response(&sliced, payload, &null_resp) {
                                                // Read-loop sends must not block on a full
                                                // channel; dropping is the deliberate policy.
                                                if tx.try_send(resp).is_err() {
                                                    log_trace!("response channel full — dropped NXDOMAIN");
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Forward upstream (spawned task owns all it needs)
                                    log_trace!(&format!("FORWARD {}", qname_str(payload)));
                                    match semaphore.clone().try_acquire_owned() {
                                        Ok(permit) => {
                                        let upstreams = upstreams.clone();
                                        let payload_vec = payload.to_vec();
                                        let req_ip = pkt.to_vec();
                                        let tx_clone = tx.clone();
                                        let task_token = cancel_token.clone();
                                        tokio::spawn(async move {
                                            let _permit = permit;
                                            tokio::select! {
                                                _ = task_token.cancelled() => {}
                                                _ = forward_query(
                                                    &upstreams,
                                                    &payload_vec,
                                                    &req_ip,
                                                    &tx_clone,
                                                ) => {}
                                            }
                                        });
                                        }
                                        Err(_) => {
                                            // Semaphore full: return SERVFAIL immediately
                                            // to prevent app hangs
                                            if let Ok(query) = domain::base::message::Message::from_slice(payload) {
                                                if let Some(servfail) = build_servfail_response(&query) {
                                                    if let Some(resp) = create_forwarded_response(&sliced, payload, &servfail) {
                                                        if tx.try_send(resp).is_err() {
                                                            log_trace!("response channel full — dropped SERVFAIL");
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Non-DNS UDP to the VPN's fake DNS addresses:
                            // drop silently (no ICMP amplification).
                        }
                    } else if let Some(TransportSlice::Tcp(_)) = sliced.transport.as_ref() {
                        // Deliberate policy: only DNS (UDP/53) is proxied.
                        // DNS-over-TCP is not supported, and nothing else is
                        // expected to reach the TUN (only the fake DNS
                        // addresses are routed into it), so all TCP gets a
                        // RST rather than being silently blackholed.
                        if let Some(resp) = create_tcp_rst(&sliced) {
                            if tx.try_send(resp).is_err() {
                                log_trace!("response channel full — dropped TCP RST");
                            }
                        }
                    }
                    // All other traffic (ICMP, etc.): drop.
                }
            }
            n if n < 0 => {
                let err = std::io::Error::last_os_error();
                if err.kind() == std::io::ErrorKind::WouldBlock {
                    guard.clear_ready();
                } else {
                    log_trace!(&format!("Read error: {}", err));
                    break;
                }
            }
            _ => {
                log_trace!("TUN closed / EOF");
                break;
            }
        }
            } // end res = async_fd.readable()
        } // end tokio::select!
    } // end loop
}

/// Forwards one DNS query upstream and delivers the response on `tx`.
///
/// Upstreams are tried in order (primary first, then backups). Each
/// connection attempt is capped at [DIAL_BUDGET] so a hanging upstream
/// still leaves time for the next; the whole chain is bounded by
/// [QUERY_DEADLINE]. An upstream whose dial or query fails is put on
/// [FAILED_UPSTREAM_BACKOFF] so subsequent queries fail over immediately.
async fn forward_query(
    upstreams: &[Arc<Upstream>],
    payload: &[u8],
    req_ip: &[u8],
    tx: &mpsc::Sender<Vec<u8>>,
) {
    let deadline = tokio::time::Instant::now() + QUERY_DEADLINE;

    // Backoff only exists to fail over faster. When every upstream is in
    // backoff (e.g. all failed once during a network switch), skipping them
    // all would SERVFAIL every query for the whole backoff window, so try
    // them anyway — concurrent dials are coalesced by get_session.
    let now = tokio::time::Instant::now();
    let mut ignore_backoff = true;
    for upstream in upstreams {
        if !upstream.shared.in_backoff(now).await {
            ignore_backoff = false;
            break;
        }
    }

    for upstream in upstreams {
        if !ignore_backoff
            && upstream
                .shared
                .in_backoff(tokio::time::Instant::now())
                .await
        {
            continue;
        }
        // Cap the dial budget so backups get a share of the deadline.
        let now = tokio::time::Instant::now();
        let attempt_deadline = now
            .checked_add(DIAL_BUDGET)
            .filter(|d| *d < deadline)
            .unwrap_or(deadline);

        let session = match get_session(upstream, attempt_deadline).await {
            Some(c) => c,
            None => {
                upstream.shared.mark_failed(now).await;
                continue;
            }
        };

        match tokio::time::timeout_at(deadline, session.send_query(payload)).await {
            Ok(Ok(resp_payload)) => {
                if let Ok(sliced) = SlicedPacket::from_ip(req_ip) {
                    if let Some(resp) = create_forwarded_response(&sliced, payload, &resp_payload) {
                        // Bounded by the surrounding cancel-token select; fails
                        // immediately if the proxy loop has exited (rx dropped).
                        let _ = tx.send(resp).await;
                    }
                }
                return;
            }
            _ => {
                // Query failed or timed out: drop the connection so the next
                // query re-dials, back off this upstream, and try the next one.
                upstream.shared.invalidate().await;
                upstream
                    .shared
                    .mark_failed(tokio::time::Instant::now())
                    .await;
            }
        }
    }

    // Every upstream failed (or was in backoff): answer SERVFAIL so the
    // client isn't left waiting on its own timeout.
    send_servfail(req_ip, payload, tx).await;
}

/// Builds a SERVFAIL answer, wraps it in IP/UDP headers from the original
/// request packet, and delivers it on `tx`.
async fn send_servfail(req_ip: &[u8], payload: &[u8], tx: &mpsc::Sender<Vec<u8>>) {
    if let Ok(query) = domain::base::message::Message::from_slice(payload) {
        if let Some(servfail) = build_servfail_response(&query) {
            if let Ok(sliced) = SlicedPacket::from_ip(req_ip) {
                if let Some(resp) = create_forwarded_response(&sliced, payload, &servfail) {
                    let _ = tx.send(resp).await;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expiry_boundary_is_inclusive() {
        assert!(pause_expired(1000, 1000));
        assert!(pause_expired(1001, 1000));
        assert!(!pause_expired(999, 1000));
    }

    fn trie_from(pauses: &[(&str, i64)]) -> Trie<Vec<u8>, i64> {
        let map: HashMap<String, i64> = pauses.iter().map(|(d, e)| (d.to_string(), *e)).collect();
        pauses_trie(map)
    }

    fn key(domain: &str) -> Vec<u8> {
        domain_to_wire_format(domain).unwrap()
    }

    #[test]
    fn active_pause_is_not_expired() {
        let trie = trie_from(&[("example.com", now_epoch_ms() + 60_000)]);
        assert!(!pause_lazily_expired(&trie, &key("example.com")));
    }

    #[test]
    fn expired_pause_is_expired() {
        let trie = trie_from(&[("example.com", now_epoch_ms() - 1)]);
        assert!(pause_lazily_expired(&trie, &key("example.com")));
    }

    #[test]
    fn indefinite_pause_never_expires() {
        let trie = trie_from(&[("example.com", i64::MAX)]);
        assert!(!pause_lazily_expired(&trie, &key("example.com")));
    }

    #[test]
    fn expired_pause_covers_subdomains() {
        let trie = trie_from(&[("example.com", now_epoch_ms() - 1)]);
        assert!(pause_lazily_expired(&trie, &key("sub.example.com")));
    }

    #[test]
    fn active_pause_covers_subdomains() {
        let trie = trie_from(&[("example.com", now_epoch_ms() + 60_000)]);
        assert!(!pause_lazily_expired(&trie, &key("sub.example.com")));
    }

    #[test]
    fn unpaused_domain_is_untouched() {
        let trie = trie_from(&[("example.com", now_epoch_ms() - 1)]);
        assert!(!pause_lazily_expired(&trie, &key("other.com")));
    }

    #[test]
    fn empty_pause_set_never_blocks() {
        let trie = trie_from(&[]);
        assert!(!pause_lazily_expired(&trie, &key("example.com")));
    }

    #[test]
    fn invalid_domains_are_skipped_when_building_the_trie() {
        // A 64-byte label exceeds the DNS label limit and must be dropped,
        // not panic.
        let long_label = "a".repeat(64);
        let map: HashMap<String, i64> = [(format!("{long_label}.com"), now_epoch_ms() - 1)]
            .into_iter()
            .collect();
        let trie = pauses_trie(map);
        assert!(!pause_lazily_expired(&trie, &key("example.com")));
    }

    #[test]
    fn pauses_trie_uses_lowercase_keys() {
        let map: HashMap<String, i64> = [("EXAMPLE.com".to_string(), now_epoch_ms() - 1)]
            .into_iter()
            .collect();
        let trie = pauses_trie(map);
        assert!(pause_lazily_expired(&trie, &key("example.com")));
    }

    fn list(domains: &[&str]) -> Trie<Vec<u8>, ()> {
        domain_trie(domains.iter().map(|d| d.to_string()).collect())
    }

    #[test]
    fn whitelist_overrides_identical_block() {
        let block = list(&["example.com"]);
        let allow = list(&["example.com"]);
        let pauses = trie_from(&[]);
        assert!(!should_block(&block, &allow, &pauses, &key("example.com")));
        assert!(!should_block(&block, &allow, &pauses, &key("www.example.com")));
    }

    #[test]
    fn whitelisted_parent_overrides_blocked_subdomain() {
        // Blocklists often list "www.example.com" separately; whitelisting
        // the bare domain (what WhitelistUrlParser produces) must cover it.
        let block = list(&["www.example.com", "cdn.example.com"]);
        let allow = list(&["example.com"]);
        let pauses = trie_from(&[]);
        assert!(!should_block(&block, &allow, &pauses, &key("www.example.com")));
        assert!(!should_block(&block, &allow, &pauses, &key("cdn.example.com")));
    }

    #[test]
    fn whitelisted_subdomain_overrides_blocked_parent() {
        let block = list(&["example.com"]);
        let allow = list(&["docs.example.com"]);
        let pauses = trie_from(&[]);
        assert!(!should_block(&block, &allow, &pauses, &key("docs.example.com")));
        assert!(should_block(&block, &allow, &pauses, &key("ads.example.com")));
        assert!(should_block(&block, &allow, &pauses, &key("example.com")));
    }

    #[test]
    fn whitelist_overrides_expired_pause() {
        let block = list(&[]);
        let allow = list(&["example.com"]);
        let pauses = trie_from(&[("example.com", now_epoch_ms() - 1)]);
        assert!(!should_block(&block, &allow, &pauses, &key("example.com")));
    }

    #[test]
    fn whitelist_does_not_leak_to_lookalike_domains() {
        let block = list(&["example.com", "notexample.com"]);
        let allow = list(&["example.com"]);
        let pauses = trie_from(&[]);
        assert!(should_block(&block, &allow, &pauses, &key("notexample.com")));
    }

    #[test]
    fn blocking_unaffected_without_whitelist() {
        let block = list(&["example.com"]);
        let allow = list(&[]);
        let pauses = trie_from(&[]);
        assert!(should_block(&block, &allow, &pauses, &key("sub.example.com")));
        assert!(!should_block(&block, &allow, &pauses, &key("other.com")));
    }
}

/// Failover integration tests.
///
/// The two network tests (marked `#[ignore]`) dial real upstreams and must
/// be run explicitly with `cargo test -- --ignored`. They use 192.0.2.1
/// (TEST-NET-1, RFC 5737 — never routable) as a guaranteed-dead primary.
#[cfg(test)]
mod failover_tests {
    use super::*;
    use crate::wire_test_util::make_query;
    use domain::base::iana::Rcode;

    /// Wraps a DNS payload in the IP/UDP header shape the TUN read loop
    /// produces (client 10.0.0.2:33333 → fake DNS 10.0.0.1:53).
    fn wrap_query(payload: &[u8]) -> Vec<u8> {
        let mut packet = Vec::new();
        etherparse::PacketBuilder::ipv4(
            "10.0.0.2".parse::<std::net::Ipv4Addr>().unwrap().octets(),
            "10.0.0.1".parse::<std::net::Ipv4Addr>().unwrap().octets(),
            64,
        )
        .udp(33333, 53)
        .write(&mut packet, payload)
        .unwrap();
        packet
    }

    fn unwrap_response(packet: &[u8]) -> domain::base::message::Message<Vec<u8>> {
        let sliced = SlicedPacket::from_ip(packet).expect("valid IP packet");
        match sliced.transport.as_ref() {
            Some(TransportSlice::Udp(udp)) => {
                domain::base::message::Message::from_octets(udp.payload().to_vec()).unwrap()
            }
            _ => panic!("expected UDP response"),
        }
    }

    #[tokio::test]
    async fn backoff_window_is_bounded() {
        let shared = UpstreamShared::default();
        let now = tokio::time::Instant::now();
        assert!(!shared.in_backoff(now).await);
        shared.mark_failed(now).await;
        assert!(shared.in_backoff(now).await);
        assert!(
            shared
                .in_backoff(now + FAILED_UPSTREAM_BACKOFF - Duration::from_millis(1))
                .await
        );
        // Backoff lapses at exactly FAILED_UPSTREAM_BACKOFF.
        assert!(!shared.in_backoff(now + FAILED_UPSTREAM_BACKOFF).await);
    }

    /// No real network needed: 192.0.2.1 never answers, so the dial fails
    /// (by timeout or an immediate unreachable error) either way.
    #[tokio::test]
    async fn upstreams_are_retried_when_all_are_in_backoff() {
        let upstream = Arc::new(Upstream {
            kind: UpstreamKind::Doq {
                endpoint_v4: Some(Arc::new(QuicEndpoint::new_doq_v4().unwrap())),
                endpoint_v6: None,
                ip_v4: Some("192.0.2.1".parse().unwrap()),
                ip_v6: None,
            },
            sni: "dns.adguard-dns.com".to_string(),
            shared: Arc::new(UpstreamShared::default()),
        });
        let first_failure = tokio::time::Instant::now();
        upstream.shared.mark_failed(first_failure).await;

        let payload = make_query("example.com");
        let req_ip = wrap_query(&payload);
        let (tx, mut rx) = mpsc::channel(10);
        forward_query(&[upstream.clone()], &payload, &req_ip, &tx).await;

        // Still a SERVFAIL (the upstream is dead), but it was dialed again:
        // the failed attempt pushed the backoff window past the original.
        let msg = unwrap_response(&rx.recv().await.expect("a SERVFAIL"));
        assert_eq!(msg.header().rcode(), Rcode::SERVFAIL);
        let until = upstream.shared.backoff_until.lock().await.unwrap();
        assert!(
            until > first_failure + FAILED_UPSTREAM_BACKOFF,
            "the backed-off upstream should have been retried"
        );
    }

    #[tokio::test]
    #[ignore = "network test — dials real DoH3 upstreams"]
    async fn forward_query_fails_over_to_doh3_backup() {
        let primary = Arc::new(Upstream {
            kind: UpstreamKind::Doq {
                endpoint_v4: Some(Arc::new(QuicEndpoint::new_doq_v4().unwrap())),
                endpoint_v6: None,
                ip_v4: Some("192.0.2.1".parse().unwrap()),
                ip_v6: None,
            },
            sni: "dns.adguard-dns.com".to_string(),
            shared: Arc::new(UpstreamShared::default()),
        });
        let backup = Arc::new(Upstream {
            kind: UpstreamKind::Doh3 {
                endpoint_v4: Some(Arc::new(QuicEndpoint::new_h3_v4().unwrap())),
                endpoint_v6: None,
                ip_v4: Some("1.1.1.1".parse().unwrap()),
                ip_v6: None,
                path: "/dns-query".to_string(),
            },
            sni: "cloudflare-dns.com".to_string(),
            shared: Arc::new(UpstreamShared::default()),
        });
        let upstreams = vec![primary.clone(), backup.clone()];
        let payload = make_query("example.com");
        let req_ip = wrap_query(&payload);

        // --- Query 1: primary is dead, backup must answer. ---
        let (tx, mut rx) = mpsc::channel(10);
        forward_query(&upstreams, &payload, &req_ip, &tx).await;
        let msg = unwrap_response(&rx.recv().await.expect("a response"));
        assert_eq!(msg.header().rcode(), Rcode::NOERROR);
        assert!(msg.header_counts().ancount() >= 1, "no A record in answer");
        assert_eq!(
            msg.header().id(),
            domain::base::message::Message::from_slice(&payload)
                .unwrap()
                .header()
                .id(),
            "transaction ID not patched to the query's"
        );
        // The dead primary must have been marked for backoff.
        assert!(
            primary.shared.in_backoff(tokio::time::Instant::now()).await,
            "failed primary should be in backoff"
        );

        // --- Query 2: primary is skipped via backoff (no dial budget
        // burned), backup session is cached — must answer fast. ---
        let start = std::time::Instant::now();
        forward_query(&upstreams, &payload, &req_ip, &tx).await;
        let elapsed = start.elapsed();
        let msg = unwrap_response(&rx.recv().await.expect("a response"));
        assert_eq!(msg.header().rcode(), Rcode::NOERROR);
        assert!(
            elapsed < DIAL_BUDGET,
            "second query should skip the backed-off primary (took {elapsed:?})"
        );
    }

    #[tokio::test]
    #[ignore = "network test — dials real DoH3 upstreams"]
    async fn forward_query_servfails_when_all_upstreams_are_dead() {
        let upstreams = vec![
            Arc::new(Upstream {
                kind: UpstreamKind::Doq {
                    endpoint_v4: Some(Arc::new(QuicEndpoint::new_doq_v4().unwrap())),
                    endpoint_v6: None,
                    ip_v4: Some("192.0.2.1".parse().unwrap()),
                    ip_v6: None,
                },
                sni: "dns.adguard-dns.com".to_string(),
                shared: Arc::new(UpstreamShared::default()),
            }),
            Arc::new(Upstream {
                kind: UpstreamKind::Doh3 {
                    endpoint_v4: Some(Arc::new(QuicEndpoint::new_h3_v4().unwrap())),
                    endpoint_v6: None,
                    ip_v4: Some("198.51.100.1".parse().unwrap()),
                    ip_v6: None,
                    path: "/dns-query".to_string(),
                },
                sni: "dns.google".to_string(),
                shared: Arc::new(UpstreamShared::default()),
            }),
        ];
        let payload = make_query("example.com");
        let req_ip = wrap_query(&payload);

        let start = std::time::Instant::now();
        let (tx, mut rx) = mpsc::channel(10);
        forward_query(&upstreams, &payload, &req_ip, &tx).await;
        let elapsed = start.elapsed();

        // Both upstreams are dead: the client gets a SERVFAIL, bounded by
        // QUERY_DEADLINE rather than hanging.
        let msg = unwrap_response(&rx.recv().await.expect("a SERVFAIL"));
        assert_eq!(msg.header().rcode(), Rcode::SERVFAIL);
        assert!(
            elapsed < QUERY_DEADLINE + Duration::from_millis(500),
            "SERVFAIL should arrive within the query deadline (took {elapsed:?})"
        );
        assert!(
            elapsed >= DIAL_BUDGET,
            "the dead primary should still consume its dial budget first (took {elapsed:?})"
        );
    }
}
