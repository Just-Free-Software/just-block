//! Shared helpers for wire-format unit tests (not compiled into release builds).

#[cfg(test)]
pub fn make_query(domain: &str) -> Vec<u8> {
    use domain::base::iana::{Class, Rtype};
    use domain::base::message_builder::MessageBuilder;
    use domain::base::name::Name;

    let mut qb = MessageBuilder::new_vec().question();
    qb.push((Name::vec_from_str(domain).unwrap(), Rtype::A, Class::IN))
        .unwrap();
    qb.finish()
}

#[cfg(test)]
pub fn trie_key_for(domain: &str) -> Vec<u8> {
    // Same output format as the old handrolled to_trie_key/domain_to_wire_format:
    // length-prefixed labels, TLD first, no root byte.
    let mut labels: Vec<Vec<u8>> = Vec::new();
    for part in domain.split('.').filter(|p| !p.is_empty()) {
        let mut label = Vec::new();
        label.push(part.len() as u8);
        label.extend_from_slice(part.to_ascii_lowercase().as_bytes());
        labels.push(label);
    }
    labels.reverse();
    let mut out = Vec::new();
    for label in labels {
        out.extend_from_slice(&label);
    }
    out
}
