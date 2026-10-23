#[path = "../doh3.rs"]
pub mod doh3;
#[path = "../quic.rs"]
pub mod quic;

use quic::QuicEndpoint;

#[tokio::main]
async fn main() {
    // Query for example.com A record (RFC 8484 wire format)
    let query: [u8; 29] = [
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x',
        b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];

    let targets = [("1.1.1.1", "cloudflare-dns.com"), ("8.8.8.8", "dns.google")];

    for (addr, sni) in targets {
        println!("Querying DoH3 via {sni} ({addr})…");
        let endpoint = QuicEndpoint::new_h3_v4().unwrap();
        let conn = endpoint.connect(&addr.parse().unwrap(), sni).await.unwrap();
        let client = doh3::Doh3Client::new(conn, sni.to_string(), "/dns-query".to_string())
            .await
            .unwrap();
        let resp = client.send_query(&query).await.unwrap();
        println!("  received {} bytes", resp.len());
    }
}
