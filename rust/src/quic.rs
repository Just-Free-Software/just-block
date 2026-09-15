use quinn::{ClientConfig, Connection, Endpoint};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;

use std::os::fd::AsRawFd;

/// Maximum DNS message size we accept over DoQ. A response larger than
/// this is rejected rather than truncated, so a corrupted or oversized
/// message surfaces as an error (SERVFAIL upstream) instead of a
/// silently truncated packet.
const MAX_DNS_MESSAGE_SIZE: usize = 8192;

/// Long-lived QUIC endpoint that owns the UDP socket.
/// Created once per proxy lifetime; reused across DoQ reconnections
/// to prevent file-descriptor leaks.
pub struct DoqEndpoint {
    endpoint: Endpoint,
    socket_fd: i32,
}

impl DoqEndpoint {
    pub fn new_v4() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        Self::new_with_bind("0.0.0.0:0")
    }

    pub fn new_v6() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        Self::new_with_bind("[::]:0")
    }

    fn new_with_bind(bind_addr: &str) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let mut crypto = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        crypto.alpn_protocols = vec![b"doq".to_vec()];

        let client_config = ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?,
        ));

        let socket = std::net::UdpSocket::bind(bind_addr)?;
        socket.set_nonblocking(true)?;
        let socket_fd = socket.as_raw_fd();

        let mut endpoint = Endpoint::new(
            quinn::EndpointConfig::default(),
            None,
            socket,
            Arc::new(quinn::TokioRuntime),
        )?;
        endpoint.set_default_client_config(client_config);

        Ok(Self {
            endpoint,
            socket_fd,
        })
    }

    /// FD borrowed from the underlying UDP socket owned by `endpoint`.
    /// Caller must not close this FD.
    /// Valid only while this `DoqEndpoint` is alive.
    pub fn get_socket_fd(&self) -> i32 {
        self.socket_fd
    }

    /// Creates a new QUIC connection on the existing endpoint.
    /// Only the connection is replaced on reconnect — the socket stays open.
    pub async fn connect(
        &self,
        server: &IpAddr,
        sni: &str,
    ) -> Result<DoqClient, Box<dyn std::error::Error + Send + Sync>> {
        let addr = SocketAddr::new(*server, 853);
        let connection = self.endpoint.connect(addr, sni)?.await?;
        Ok(DoqClient { connection })
    }
}

/// A single DoQ (DNS over QUIC) session.
/// Lightweight — only holds the connection, not the socket.
pub struct DoqClient {
    connection: Connection,
}

impl Drop for DoqClient {
    fn drop(&mut self) {
        self.connection.close(0u32.into(), b"App Teardown");
    }
}

impl Drop for DoqEndpoint {
    fn drop(&mut self) {
        self.endpoint.close(0u32.into(), b"Endpoint Teardown");
    }
}

impl DoqClient {
    pub fn is_alive(&self) -> bool {
        self.connection.close_reason().is_none()
    }

    pub async fn send_query(
        &self,
        payload: &[u8],
    ) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
        let (mut send, mut recv) = self.connection.open_bi().await?;

        if payload.len() > u16::MAX as usize {
            return Err("DNS query exceeds maximum DoQ message size".into());
        }
        let len = (payload.len() as u16).to_be_bytes();
        send.write_all(&len).await?;
        send.write_all(payload).await?;
        send.finish()?;

        let mut len_buf = [0u8; 2];
        recv.read_exact(&mut len_buf).await?;
        let resp_len = u16::from_be_bytes(len_buf) as usize;
        if resp_len > MAX_DNS_MESSAGE_SIZE {
            return Err(format!("DoQ response too large: {resp_len} bytes").into());
        }

        let mut resp_buf = vec![0u8; resp_len];
        recv.read_exact(&mut resp_buf).await?;

        Ok(resp_buf)
    }
}
