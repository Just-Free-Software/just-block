uniffi::setup_scaffolding!();

mod proxy;

mod quic;
#[cfg(test)]
mod wire_test_util;

use etherparse::{SlicedPacket, TransportSlice};
use proxy::{
    build_null_response, build_servfail_response, create_forwarded_response, create_tcp_rst,
    parse_query,
};
use quic::{DoqClient, DoqEndpoint};
use radix_trie::Trie;
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
/// (connection establishment + query + response delivery), end to end.
const QUERY_DEADLINE: Duration = Duration::from_secs(3);

/// Tracks the currently-running proxy task. `generation` disambiguates an old
/// task's cleanup from a new task's registration so a stale exit can never
/// clobber a fresh run's token.
#[derive(Default)]
struct RunSlot {
    generation: u64,
    token: Option<CancellationToken>,
}

#[derive(uniffi::Object)]
pub struct DnsProxy {
    tun_fd: i32,
    quic_fd_v4: Option<i32>,
    quic_fd_v6: Option<i32>,
    upstream_v4: Option<std::net::IpAddr>,
    upstream_v6: Option<std::net::IpAddr>,
    sni_hostname: String,
    blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    run_slot: Arc<Mutex<RunSlot>>,
    doq_endpoint_v4: Option<Arc<DoqEndpoint>>,
    doq_endpoint_v6: Option<Arc<DoqEndpoint>>,
}

#[uniffi::export]
impl DnsProxy {
    #[uniffi::constructor]
    pub fn new(
        tun_fd: i32,
        upstream_v4: Option<String>,
        upstream_v6: Option<String>,
        sni_hostname: String,
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
        let doq_endpoint_v4 = upstream_v4
            .as_ref()
            .map(|_| Arc::new(DoqEndpoint::new_v4().expect("Failed to create DoQ IPv4 endpoint")));
        let quic_fd_v4 = doq_endpoint_v4.as_ref().map(|e| e.get_socket_fd());

        let doq_endpoint_v6 = upstream_v6
            .as_ref()
            .map(|_| Arc::new(DoqEndpoint::new_v6().expect("Failed to create DoQ IPv6 endpoint")));
        let quic_fd_v6 = doq_endpoint_v6.as_ref().map(|e| e.get_socket_fd());

        Arc::new(Self {
            tun_fd,
            quic_fd_v4,
            quic_fd_v6,
            upstream_v4,
            upstream_v6,
            sni_hostname,
            blocklist: Arc::new(RwLock::new(Trie::new())),
            run_slot: Arc::new(Mutex::new(RunSlot::default())),
            doq_endpoint_v4,
            doq_endpoint_v6,
        })
    }

    pub fn get_quic_fd_v4(&self) -> Option<i32> {
        self.quic_fd_v4
    }

    pub fn get_quic_fd_v6(&self) -> Option<i32> {
        self.quic_fd_v6
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
        let upstream_v4 = self.upstream_v4.clone();
        let upstream_v6 = self.upstream_v6.clone();
        let sni_hostname = self.sni_hostname.clone();
        let blocklist = self.blocklist.clone();
        let doq_endpoint_v4 = self.doq_endpoint_v4.clone();
        let doq_endpoint_v6 = self.doq_endpoint_v6.clone();
        let run_slot = self.run_slot.clone();

        get_runtime().spawn(async move {
            run_proxy(
                tun_fd,
                upstream_v4,
                upstream_v6,
                sni_hostname,
                blocklist,
                cancel_token,
                doq_endpoint_v4,
                doq_endpoint_v6,
            )
            .await;
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
        let requested = domains.len();
        let mut inserted = 0;
        let mut trie = Trie::new();
        for domain in domains {
            if let Some(wire_format) = domain_to_wire_format(&domain) {
                trie.insert(wire_format, ());
                inserted += 1;
            }
        }
        let mut lock = self.blocklist.write().unwrap_or_else(|e| e.into_inner());
        log_trace!(&format!(
            "update_blocklist: {} entries requested, {} inserted",
            requested, inserted
        ));
        *lock = trie;
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

/// Debug logging to Android logcat (tag: freeblock-rust). Temporarily
/// wired into log_trace below for on-device regression diagnosis.
#[cfg(all(target_os = "android", feature = "dns-trace"))]
mod android_log {
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }

    const LOG_DEBUG: i32 = 3;

    pub fn write(msg: &str) {
        let tag = c"freeblock-rust";
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
#[cfg_attr(not(all(target_os = "android", feature = "dns-trace")), allow(dead_code))]
fn qname_str(payload: &[u8]) -> String {
    domain::base::message::Message::from_slice(payload)
        .ok()
        .and_then(|m| m.sole_question().ok())
        .map(|q| q.qname().to_string())
        .unwrap_or_else(|| "<malformed>".into())
}

// ---------------------------------------------------------------------------
// DoQ connection state
// ---------------------------------------------------------------------------

/// Shared upstream-connection state for one proxy run.
///
/// `Connecting` makes connection establishment explicit: concurrent queries
/// wait on `changed` (no polling loop, no timestamp-as-lock), and exactly one
/// task performs the dial. Everyone else is woken when it succeeds or fails.
enum ConnState {
    /// No connection; the next query that needs one dials.
    Idle,
    /// A connection attempt is in flight.
    Connecting,
    /// An established, presumably-alive connection.
    Connected(Arc<DoqClient>),
}

struct DoqShared {
    state: TokioMutex<ConnState>,
    changed: Notify,
}

impl DoqShared {
    /// Mark the current connection dead so the next query re-dials.
    /// Never interrupts an in-flight dial.
    async fn invalidate(&self) {
        let mut st = self.state.lock().await;
        if matches!(&*st, ConnState::Connected(_)) {
            *st = ConnState::Idle;
        }
        self.changed.notify_waiters();
    }
}

/// Returns a live DoQ client, dialing (or waiting for an in-flight dial) as
/// needed. Returns `None` if no client is available by `deadline`.
async fn get_doq_client(
    shared: &DoqShared,
    endpoint_v4: &Option<Arc<DoqEndpoint>>,
    endpoint_v6: &Option<Arc<DoqEndpoint>>,
    upstream_v4: &Option<std::net::IpAddr>,
    upstream_v6: &Option<std::net::IpAddr>,
    sni: &str,
    deadline: tokio::time::Instant,
) -> Option<Arc<DoqClient>> {
    loop {
        // Register for change notification BEFORE inspecting state so a
        // state transition between inspection and waiting cannot be missed.
        let notified = shared.changed.notified();

        enum Step {
            Ready(Arc<DoqClient>),
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

                let result = tokio::time::timeout_at(
                    deadline,
                    connect_doq(endpoint_v4, endpoint_v6, upstream_v4, upstream_v6, sni),
                )
                .await;

                let mut st = shared.state.lock().await;
                match result {
                    Ok(Ok(client)) => {
                        let arc = Arc::new(client);
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
/// upstreams are configured; otherwise dial whichever exists.
async fn connect_doq(
    endpoint_v4: &Option<Arc<DoqEndpoint>>,
    endpoint_v6: &Option<Arc<DoqEndpoint>>,
    upstream_v4: &Option<std::net::IpAddr>,
    upstream_v6: &Option<std::net::IpAddr>,
    sni: &str,
) -> Result<DoqClient, Box<dyn std::error::Error + Send + Sync>> {
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
    upstream_v4: Option<std::net::IpAddr>,
    upstream_v6: Option<std::net::IpAddr>,
    sni_hostname: String,
    blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    cancel_token: CancellationToken,
    doq_endpoint_v4: Option<Arc<DoqEndpoint>>,
    doq_endpoint_v6: Option<Arc<DoqEndpoint>>,
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
        match AsyncFd::new(tun_fd) {
            Ok(fd) => {
                async_fd_opt = Some(fd);
                break;
            }
            Err(e) if e.raw_os_error() == Some(libc::EEXIST) => {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            Err(_e) => {
                log_trace!(&format!("AsyncFd::new failed: {}", _e));
                return;
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
    let doq_shared = Arc::new(DoqShared {
        state: TokioMutex::new(ConnState::Idle),
        changed: Notify::new(),
    });

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
                                let blocked = {
                                    let lock = blocklist.read().unwrap_or_else(|e| e.into_inner());
                                    lock.get_ancestor_value(&trie_key).is_some()
                                };

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
                                    // Forward via DoQ (spawned task owns all it needs)
                                    log_trace!(&format!("FORWARD {} via DoQ", qname_str(payload)));
                                    match semaphore.clone().try_acquire_owned() {
                                        Ok(permit) => {
                                            let doq_shared = doq_shared.clone();
                                        let doq_endpoint_v4 = doq_endpoint_v4.clone();
                                        let doq_endpoint_v6 = doq_endpoint_v6.clone();
                                        let upstream_v4_clone = upstream_v4.clone();
                                        let upstream_v6_clone = upstream_v6.clone();
                                        let sni_clone = sni_hostname.clone();
                                        let payload_vec = payload.to_vec();
                                        let req_ip = pkt.to_vec();
                                        let tx_clone = tx.clone();
                                        let task_token = cancel_token.clone();
                                        tokio::spawn(async move {
                                            let _permit = permit;
                                            tokio::select! {
                                                _ = task_token.cancelled() => {}
                                                _ = forward_query(
                                                    &doq_shared,
                                                    &doq_endpoint_v4,
                                                    &doq_endpoint_v6,
                                                    &upstream_v4_clone,
                                                    &upstream_v6_clone,
                                                    &sni_clone,
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

/// Forwards one DNS query upstream via DoQ and delivers the response on
/// `tx`. Bounded by a single hard deadline covering dial + query + send,
/// so a request can never exceed QUERY_DEADLINE end to end.
async fn forward_query(
    doq_shared: &DoqShared,
    doq_endpoint_v4: &Option<Arc<DoqEndpoint>>,
    doq_endpoint_v6: &Option<Arc<DoqEndpoint>>,
    upstream_v4: &Option<std::net::IpAddr>,
    upstream_v6: &Option<std::net::IpAddr>,
    sni: &str,
    payload: &[u8],
    req_ip: &[u8],
    tx: &mpsc::Sender<Vec<u8>>,
) {
    let deadline = tokio::time::Instant::now() + QUERY_DEADLINE;

    let client = match get_doq_client(
        doq_shared,
        doq_endpoint_v4,
        doq_endpoint_v6,
        upstream_v4,
        upstream_v6,
        sni,
        deadline,
    )
    .await
    {
        Some(c) => c,
        None => {
            send_servfail(req_ip, payload, tx).await;
            return;
        }
    };

    let query = client.send_query(payload);
    match tokio::time::timeout_at(deadline, query).await {
        Ok(Ok(resp_payload)) => {
            if let Ok(sliced) = SlicedPacket::from_ip(req_ip) {
                if let Some(resp) = create_forwarded_response(&sliced, payload, &resp_payload) {
                    // Bounded by the surrounding cancel-token select; fails
                    // immediately if the proxy loop has exited (rx dropped).
                    let _ = tx.send(resp).await;
                }
            }
        }
        _ => {
            // Query failed or timed out: drop the connection so the next
            // query re-dials, and answer SERVFAIL so the client isn't left
            // waiting on its own timeout.
            doq_shared.invalidate().await;
            send_servfail(req_ip, payload, tx).await;
        }
    }
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
