use etherparse::{PacketBuilder, SlicedPacket, TransportSlice};
use domain::base::message::Message;
use domain::base::name::{ToLabelIter, ToName};
use domain::base::iana::{Class, Rcode};
use domain::base::message_builder::MessageBuilder;
use domain::base::name::Name;
use domain::base::{Serial, Ttl};
use domain::dep::octseq::octets::Octets;
use domain::rdata::Soa;

/// Returns the minimum TTL over all answer records of a DNS response.
/// `None` if the payload is not a parseable message or has no answers —
/// callers treat that as "do not cache".
pub fn min_ttl(payload: &[u8]) -> Option<u32> {
    let msg = Message::from_slice(payload).ok()?;
    msg.answer().ok()?.flatten().map(|rec| rec.ttl().as_secs()).min()
}

/// Parses a DNS query payload and derives all keys the proxy needs.
///
/// Returns `(cache_key, trie_key, qtype_int)`:
/// - `cache_key`: lowercased forward wire-format qname, then QTYPE and QCLASS
///   appended (same layout as the old code in `run_proxy` built manually).
/// - `trie_key`: lowercased, label-reversed qname with no root byte — byte-
///   identical to the output of the old `to_trie_key`.
/// - `qtype_int`: the big-endian u16 QTYPE (e.g. 1 = A).
///
/// Returns `None` for malformed packets. Unlike the old `extract_dns_name`,
/// compressed question names are handled correctly instead of being rejected.
pub fn parse_query(payload: &[u8]) -> Option<(Vec<u8>, Vec<u8>, u16)> {
    let msg = Message::from_slice(payload).ok()?;
    let question = msg.sole_question().ok()?;
    let qname = question.qname();

    // Rebuild the forward wire-format name from labels (this resolves
    // compression pointers). Lowercasing the whole buffer is safe: label
    // length bytes are 1..=63 and `to_ascii_lowercase` only affects A-Z.
    let mut wire = Vec::with_capacity(64);
    for label in qname.iter_labels() {
        if label.is_root() {
            break;
        }
        wire.push(label.len() as u8);
        wire.extend_from_slice(label.as_slice());
    }
    wire.push(0);
    wire.make_ascii_lowercase();

    // Trie key: labels in reverse order, each label's bytes (incl. length
    // byte) kept intact, no root byte — identical to old `to_trie_key` output.
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    let mut idx = 0;
    while wire[idx] != 0 {
        let len = 1 + wire[idx] as usize;
        ranges.push((idx, idx + len));
        idx += len;
    }
    let mut trie_key: Vec<u8> = Vec::with_capacity(wire.len() - 1);
    for (start, end) in ranges.into_iter().rev() {
        trie_key.extend_from_slice(&wire[start..end]);
    }

    let mut cache_key = wire;
    cache_key.extend_from_slice(&question.qtype().to_int().to_be_bytes());
    cache_key.extend_from_slice(&question.qclass().to_int().to_be_bytes());
    Some((cache_key, trie_key, question.qtype().to_int()))
}


pub fn create_forwarded_response(sliced: &SlicedPacket, req_payload: &[u8], dns_resp: &[u8]) -> Option<Vec<u8>> {
    let builder = match sliced.net.as_ref()? {
        etherparse::InternetSlice::Ipv4(ipv4) => {
            PacketBuilder::ipv4(ipv4.header().destination(), ipv4.header().source(), 64)
        }
        etherparse::InternetSlice::Ipv6(ipv6) => {
            PacketBuilder::ipv6(ipv6.header().destination(), ipv6.header().source(), 64)
        }
        _ => return None,
    };

    let udp = match sliced.transport.as_ref()? {
        TransportSlice::Udp(udp) => udp,
        _ => return None,
    };

    let builder = builder.udp(udp.destination_port(), udp.source_port());

    let mut patched_resp = dns_resp.to_vec();
    if patched_resp.len() >= 2 && req_payload.len() >= 2 {
        patched_resp[0] = req_payload[0];
        patched_resp[1] = req_payload[1];
    }

    let mut result = Vec::with_capacity(builder.size(patched_resp.len()));
    builder.write(&mut result, &patched_resp).ok()?;

    Some(result)
}

pub fn create_tcp_rst(sliced: &SlicedPacket) -> Option<Vec<u8>> {
    use etherparse::{Ipv4Header, Ipv6Header, TcpHeader, InternetSlice, TransportSlice};
    
    let tcp = match sliced.transport.as_ref()? {
        TransportSlice::Tcp(tcp) => tcp,
        _ => return None,
    };

    let mut tcp_resp = TcpHeader::new(tcp.destination_port(), tcp.source_port(), 0, 0);
    tcp_resp.rst = true;

    if tcp.ack() {
        tcp_resp.sequence_number = tcp.acknowledgment_number();
        tcp_resp.ack = false;
    } else {
        tcp_resp.acknowledgment_number = tcp.sequence_number().wrapping_add(1);
        tcp_resp.ack = true;
    }

    let mut buf = Vec::with_capacity(128);
    match sliced.net.as_ref()? {
        InternetSlice::Ipv4(ipv4) => {
            let ipv4_resp = Ipv4Header::new(
                tcp_resp.header_len() as u16,
                64,
                etherparse::IpNumber::TCP,
                ipv4.header().destination(),
                ipv4.header().source(),
            ).ok()?;
            ipv4_resp.write(&mut buf).ok()?;
            tcp_resp.checksum = tcp_resp.calc_checksum_ipv4(&ipv4_resp, &[]).unwrap_or(0);
        }
        InternetSlice::Ipv6(ipv6) => {
            let ipv6_resp = Ipv6Header {
                traffic_class: 0,
                flow_label: etherparse::Ipv6FlowLabel::ZERO,
                payload_length: tcp_resp.header_len() as u16,
                next_header: etherparse::IpNumber::TCP,
                hop_limit: 64,
                source: ipv6.header().destination(),
                destination: ipv6.header().source(),
            };
            ipv6_resp.write(&mut buf).ok()?;
            tcp_resp.checksum = tcp_resp.calc_checksum_ipv6(&ipv6_resp, &[]).unwrap_or(0);
        }
        _ => return None,
    }
    
    tcp_resp.write(&mut buf).ok()?;
    Some(buf)
}

#[allow(dead_code)]
pub fn create_icmp_unreachable(sliced: &SlicedPacket, raw_packet: &[u8]) -> Option<Vec<u8>> {
    use etherparse::{Ipv4Header, Ipv6Header, Icmpv4Header, Icmpv4Type, icmpv4, Icmpv6Header, Icmpv6Type, icmpv6, InternetSlice};
    
    let payload_len = std::cmp::min(raw_packet.len(), 576);
    let original_bytes = &raw_packet[..payload_len];
    
    let mut buf = Vec::with_capacity(payload_len + 64);
    
    match sliced.net.as_ref()? {
        InternetSlice::Ipv4(ipv4) => {
            let icmp = Icmpv4Header {
                icmp_type: Icmpv4Type::DestinationUnreachable(icmpv4::DestUnreachableHeader::Port),
                checksum: 0,
            };
            let mut icmp_bytes = icmp.to_bytes();
            let cksum = etherparse::checksum::Sum16BitWords::new()
                .add_slice(&icmp_bytes)
                .add_slice(original_bytes)
                .ones_complement();
            let cksum_bytes = cksum.to_be_bytes();
            icmp_bytes[2] = cksum_bytes[0];
            icmp_bytes[3] = cksum_bytes[1];
            
            let ipv4_resp = Ipv4Header::new(
                (icmp_bytes.len() + original_bytes.len()) as u16,
                64,
                etherparse::IpNumber::ICMP,
                ipv4.header().destination(),
                ipv4.header().source(),
            ).ok()?;
            ipv4_resp.write(&mut buf).ok()?;
            buf.extend_from_slice(&icmp_bytes);
            buf.extend_from_slice(original_bytes);
        }
        InternetSlice::Ipv6(ipv6) => {
            let icmp = Icmpv6Header {
                icmp_type: Icmpv6Type::DestinationUnreachable(icmpv6::DestUnreachableCode::Port),
                checksum: 0,
            };
            
            let ipv6_resp = Ipv6Header {
                traffic_class: 0,
                flow_label: etherparse::Ipv6FlowLabel::ZERO,
                payload_length: (8 + original_bytes.len()) as u16,
                next_header: etherparse::IpNumber::IPV6_ICMP,
                hop_limit: 64,
                source: ipv6.header().destination(),
                destination: ipv6.header().source(),
            };
            
            let mut icmp_bytes = icmp.to_bytes();
            let cksum = etherparse::checksum::Sum16BitWords::new()
                .add_16bytes(ipv6_resp.source)
                .add_16bytes(ipv6_resp.destination)
                .add_4bytes((ipv6_resp.payload_length as u32).to_be_bytes())
                .add_2bytes([0, 0])
                .add_2bytes([0, etherparse::IpNumber::IPV6_ICMP.0])
                .add_slice(&icmp_bytes)
                .add_slice(original_bytes)
                .ones_complement();
                
            let cksum_bytes = cksum.to_be_bytes();
            icmp_bytes[2] = cksum_bytes[0];
            icmp_bytes[3] = cksum_bytes[1];
            
            ipv6_resp.write(&mut buf).ok()?;
            buf.extend_from_slice(&icmp_bytes);
            buf.extend_from_slice(original_bytes);
        }
        _ => return None,
    }
    Some(buf)
}

/// Builds the DNS payload for a blocked query: NXDOMAIN, authoritative,
/// with an SOA (owner = question name, TTL 1) in the authority section.
/// Matches the shape of the old handrolled `create_null_response`
/// (ANCOUNT=0, NSCOUNT=1).
pub fn build_null_response<Octs: Octets + ?Sized>(
    query: &Message<Octs>,
) -> Option<Vec<u8>> {
    let question = query.sole_question().ok()?;
    // Legacy `create_null_response` used the question name as the SOA owner
    // (a 0xC00C pointer to the question). Keep that shape.
    let owner = question.qname().to_name::<Vec<u8>>();

    let mut answer = MessageBuilder::new_vec()
        .start_answer(query, Rcode::NXDOMAIN)
        .ok()?;
    answer.header_mut().set_aa(true);
    let root = Name::root_ref();
    let soa = Soa::new(
        root.clone(),
        root.clone(),
        Serial(0),
        Ttl::from_secs(0),
        Ttl::from_secs(0),
        Ttl::from_secs(0),
        Ttl::from_secs(1),
    );
    let mut authority = answer.authority();
    authority
        .push((owner, Class::IN, Ttl::from_secs(1), soa))
        .ok()?;
    Some(authority.finish())
}

/// Builds the DNS payload for an unforwardable query: SERVFAIL, empty
/// sections, echoing the question and transaction ID.
pub fn build_servfail_response<Octs: Octets + ?Sized>(
    query: &Message<Octs>,
) -> Option<Vec<u8>> {
    let builder = MessageBuilder::new_vec()
        .start_answer(query, Rcode::SERVFAIL)
        .ok()?;
    Some(builder.finish())
}

#[cfg(test)]
mod wire_tests {
    use super::*;
    use crate::wire_test_util::{make_query, trie_key_for};

    #[test]
    fn parse_query_builds_unchanged_keys() {
        let q = make_query("ads.Example.COM");
        let (cache_key, trie_key, qtype) = parse_query(&q).unwrap();
        // cache key: lowercased forward wire format + A (1) IN (1)
        assert_eq!(
            &cache_key[..],
            b"\x03ads\x07example\x03com\x00\x00\x01\x00\x01"
        );
        // trie key: reversed labels, no root — identical to old to_trie_key output
        assert_eq!(&trie_key[..], trie_key_for("ads.example.com"));
        assert_eq!(qtype, 1); // A
    }

    #[test]
    fn parse_query_handles_single_label_and_case() {
        let q = make_query("Example.com");
        let (cache_key, trie_key, _) = parse_query(&q).unwrap();
        assert_eq!(&cache_key[..13], b"\x07example\x03com\x00");
        assert_eq!(&trie_key[..], trie_key_for("example.com"));
    }

    #[test]
    fn parse_query_rejects_garbage() {
        assert!(parse_query(&[0u8; 3]).is_none());
        assert!(parse_query(&[]).is_none());
    }

    use crate::wire_test_util::build_query_message;
    use domain::base::iana::Rcode;

    #[test]
    fn null_response_matches_legacy_shape() {
        let q = make_query("ads.example.com");
        let msg = domain::base::message::Message::from_slice(&q).unwrap();
        let resp = build_null_response(&msg).unwrap();
        let parsed = domain::base::message::Message::from_slice(&resp).unwrap();
        assert_eq!(parsed.header().rcode(), Rcode::NXDOMAIN);
        assert!(parsed.header().aa());
        assert_eq!(parsed.header_counts().ancount(), 0);
        assert_eq!(parsed.header_counts().nscount(), 1);
        // Answer section is empty; SOA sits in the authority section
        assert!(parsed.answer().unwrap().next().is_none());
        let auth: Vec<_> = parsed.authority().unwrap().collect();
        assert_eq!(auth.len(), 1);
        let rec = auth[0].as_ref().unwrap();
        assert_eq!(rec.ttl(), domain::base::Ttl::from_secs(1));
        // SOA owner is the question name (matches legacy 0xC00C pointer)
        let expected_owner = msg
            .sole_question()
            .unwrap()
            .qname()
            .to_name::<Vec<u8>>();
        assert_eq!(rec.owner().to_name::<Vec<u8>>(), expected_owner);
    }

    #[test]
    fn servfail_response_has_empty_sections() {
        let q = make_query("ads.example.com");
        let msg = domain::base::message::Message::from_slice(&q).unwrap();
        let resp = build_servfail_response(&msg).unwrap();
        let parsed = domain::base::message::Message::from_slice(&resp).unwrap();
        assert_eq!(parsed.header().rcode(), Rcode::SERVFAIL);
        assert_eq!(parsed.header_counts().ancount(), 0);
        assert_eq!(parsed.header_counts().nscount(), 0);
        assert_eq!(parsed.header_counts().qdcount(), 1);
        // Transaction ID echoes the query
        assert_eq!(parsed.header().id(), msg.header().id());
    }

    #[test]
    fn min_ttl_takes_minimum_over_answers() {
        use domain::base::iana::{Class, Rtype};
        use domain::base::message_builder::MessageBuilder;
        use domain::base::name::Name;
        use domain::base::Ttl;
        use domain::rdata::A;
        use std::net::Ipv4Addr;

        let msg = build_query_message("ads.example.com");
        let mut rb = MessageBuilder::new_vec()
            .start_answer(&msg, Rcode::NOERROR)
            .unwrap();
        let name = Name::vec_from_str("ads.example.com").unwrap();
        rb.push((name.clone(), Class::IN, Ttl::from_secs(300), A::new(Ipv4Addr::new(1, 2, 3, 4))))
            .unwrap();
        rb.push((name, Class::IN, Ttl::from_secs(60), A::new(Ipv4Addr::new(5, 6, 7, 8))))
            .unwrap();
        let resp = rb.finish();
        assert_eq!(min_ttl(&resp), Some(60));
    }

    #[test]
    fn min_ttl_none_for_garbage_or_empty_answers() {
        assert_eq!(min_ttl(&[0u8; 3]), None);
        let msg = build_query_message("ads.example.com");
        // A query itself has no answer section records
        assert_eq!(min_ttl(msg.as_slice()), None);
    }

    #[test]
    fn parse_query_handles_root_qname() {
        use domain::base::iana::{Class, Rtype};
        use domain::base::name::Name;

        let mut qb = MessageBuilder::new_vec().question();
        qb.push((Name::root_ref(), Rtype::A, Class::IN)).unwrap();
        let p = qb.finish();
        let (cache_key, trie_key, qtype) = parse_query(&p).unwrap();
        assert!(trie_key.is_empty());
        assert_eq!(&cache_key[..], b"\x00\x00\x01\x00\x01");
        assert_eq!(qtype, 1);
    }

    #[test]
    fn parse_query_rejects_multiple_questions() {
        // Hand-craft: header with QDCOUNT=2, two uncompressed questions.
        let mut p = vec![0x12, 0x34, 0x01, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        for name in ["\x07example\x03com", "\x04test\x03org"] {
            p.extend_from_slice(name.as_bytes());
            p.push(0);
            p.extend_from_slice(&[0, 1, 0, 1]); // A, IN
        }
        assert!(parse_query(&p).is_none());
    }
}
