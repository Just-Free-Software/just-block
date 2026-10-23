use bytes::{Buf, Bytes};
use quinn::Connection as QuinnConnection;
use std::error::Error;

/// Maximum DNS message size we accept over DoH3, mirroring the DoQ limit.
const MAX_DNS_MESSAGE_SIZE: usize = 8192;

/// A single DoH3 (DNS over HTTP/3) session on an established QUIC connection.
///
/// The HTTP/3 driver task (control + QPACK streams) is spawned in [new] and
/// runs until the underlying QUIC connection closes.
pub struct Doh3Client {
    quic: QuinnConnection,
    sender: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    authority: String,
    path: String,
}

impl Doh3Client {
    /// Builds the HTTP/3 layer on top of `quic`. `authority` (the SNI
    /// hostname) and `path` (e.g. "/dns-query") form the request target.
    pub async fn new(
        quic: QuinnConnection,
        authority: String,
        path: String,
    ) -> Result<Self, Box<dyn Error + Send + Sync>> {
        let (mut driver, sender) = h3::client::new(h3_quinn::Connection::new(quic.clone())).await?;
        tokio::spawn(async move {
            // Maintain control/QPACK stream state until the connection ends.
            let _ = driver.wait_idle().await;
        });
        Ok(Self {
            quic,
            sender,
            authority,
            path,
        })
    }

    pub fn is_alive(&self) -> bool {
        self.quic.close_reason().is_none()
    }

    /// Sends one DNS query as an HTTP/3 POST (RFC 8484 wire format body)
    /// and returns the DNS response body.
    pub async fn send_query(
        &self,
        payload: &[u8],
    ) -> Result<Vec<u8>, Box<dyn Error + Send + Sync>> {
        let mut sender = self.sender.clone();
        let request = http::Request::builder()
            .method(http::Method::POST)
            .uri(format!("https://{}{}", self.authority, self.path))
            .header("content-type", "application/dns-message")
            .header("content-length", payload.len())
            .header("accept", "application/dns-message")
            .body(())?;

        let mut stream = sender.send_request(request).await?;
        stream.send_data(Bytes::copy_from_slice(payload)).await?;
        stream.finish().await?;

        let response = stream.recv_response().await?;
        if response.status() != http::StatusCode::OK {
            return Err(format!("DoH3 upstream returned {}", response.status()).into());
        }

        let mut body = Vec::new();
        while let Some(chunk) = stream.recv_data().await? {
            body.extend_from_slice(chunk.chunk());
            if body.len() > MAX_DNS_MESSAGE_SIZE {
                return Err("DoH3 response too large".into());
            }
        }
        if body.is_empty() {
            return Err("empty DoH3 response body".into());
        }
        Ok(body)
    }
}

impl Drop for Doh3Client {
    fn drop(&mut self) {
        self.quic.close(0u32.into(), b"App Teardown");
    }
}
