# Domain Crate Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace handrolled DNS wire handling in the Rust proxy with the `domain` crate, keeping the fast raw-byte forwarding path.

**Architecture:** `proxy.rs` gains three functions (`parse_query`, `build_null_response`, `build_servfail_response`, `min_ttl`) built on `domain`; `lib.rs`'s `run_proxy` calls them. The `radix_trie` blocklist and `etherparse` IP/UDP wrapping are unchanged. Old helpers (`extract_dns_name`, `to_lowercase_wire_format`, `to_trie_key`, `get_min_ttl`) are deleted once nothing references them.

**Tech Stack:** Rust 2024, `domain` 0.12, existing `uniffi`/`tokio`/`radix_trie`/`etherparse`.

**Spec:** `docs/superpowers/specs/2026-09-02-domain-crate-adoption-design.md`

**Verified API note:** All `domain` 0.12.2 API usage below was compile- and behavior-verified against a scratch project before this plan was written. Key gotchas baked into the code: `Rcode` constants are SCREAMING_CASE (`NXDOMAIN`, `SERVFAIL`); `Octets` is imported from `domain::dep::octseq::octets`; `iter_labels()` yields labels *including* the root label and `Label::as_slice()` *excludes* the length byte; the trie key reverses *label order* (not raw bytes), matching the existing `to_trie_key` output format exactly.

---

### Task 1: Add the `domain` dependency

**Files:**
- Modify: `rust/Cargo.toml`

- [ ] **Step 1: Add the dependency**

In `rust/Cargo.toml`, under `[dependencies]`, add:

```toml
domain = "0.12"
```

- [ ] **Step 2: Verify it resolves**

Run: `cargo check` (workdir `rust/`)
Expected: compiles with no errors (crate downloads, nothing uses it yet — there may be an unused-crate warning at most).

- [ ] **Step 3: Commit**

```bash
git add rust/Cargo.toml rust/Cargo.lock
git commit -m "build: add domain crate dependency"
```

---

### Task 2: Query parsing — `parse_query` (replaces `extract_dns_name` + key builders)

**Files:**
- Modify: `rust/src/proxy.rs` (add functions; do not delete old ones yet — Task 5 does that)
- Test: in-file `#[cfg(test)] mod tests` at the bottom of `rust/src/proxy.rs`

- [ ] **Step 1: Write the failing tests**

Append to `rust/src/proxy.rs`:

```rust
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
}
```

Create `rust/src/wire_test_util.rs`:

```rust
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
    let mut out = Vec::new();
    for part in domain.split('.').filter(|p| !p.is_empty()) {
        out.push(part.len() as u8);
        out.extend_from_slice(part.to_ascii_lowercase().as_bytes());
    }
    out.reverse();
    out
}
```

In `rust/src/lib.rs`, add next to `mod proxy;`:

```rust
#[cfg(test)]
mod wire_test_util;
```

Note: `mod wire_test_util;` is declared in `lib.rs`, but the test above lives in `proxy.rs`, which is a child module of the crate root — `crate::wire_test_util` resolves from there.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --lib parse_query` (workdir `rust/`)
Expected: FAIL — `parse_query` does not exist (compile error).

- [ ] **Step 3: Implement `parse_query`**

Add to `rust/src/proxy.rs` (top, after existing imports):

```rust
use domain::base::message::Message;
use domain::base::name::ToLabelIter;

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

    // Trie key: same labels, reversed order, no root byte.
    let mut labels: Vec<&[u8]> = Vec::new();
    let mut idx = 0;
    while idx < wire.len() && wire[idx] != 0 {
        let len = wire[idx] as usize;
        labels.push(&wire[idx..idx + 1 + len]);
        idx += 1 + len;
    }
    labels.reverse();
    let mut trie_key = Vec::with_capacity(wire.len() - 1);
    for label in labels {
        trie_key.extend_from_slice(label);
    }

    let mut cache_key = wire;
    cache_key.extend_from_slice(&question.qtype().to_int().to_be_bytes());
    cache_key.extend_from_slice(&question.qclass().to_int().to_be_bytes());
    Some((cache_key, trie_key, question.qtype().to_int()))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib parse_query` (workdir `rust/`)
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add rust/src/proxy.rs rust/src/wire_test_util.rs rust/src/lib.rs
git commit -m "feat(proxy): parse DNS queries via domain crate (parse_query)"
```

---

### Task 3: Response builders — `build_null_response` / `build_servfail_response`

**Files:**
- Modify: `rust/src/proxy.rs`
- Test: `#[cfg(test)] mod wire_tests` in `rust/src/proxy.rs`

- [ ] **Step 1: Write the failing tests**

Append inside `mod wire_tests` in `rust/src/proxy.rs`:

```rust
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
        // SOA sits in the authority section, name is a pointer to the question
        let soa = parsed.answer().unwrap().next().is_none();
        assert!(soa);
        let auth: Vec<_> = parsed.authority().unwrap().collect();
        assert_eq!(auth.len(), 1);
        let rec = auth[0].as_ref().unwrap();
        assert_eq!(rec.ttl(), domain::base::Ttl::from_secs(1));
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
```

Add to `rust/src/wire_test_util.rs`:

```rust
#[cfg(test)]
pub fn build_query_message(domain: &str) -> std::borrow::Cow<'static, [u8]> {
    // Leak a small test buffer so the returned Message<&[u8]> is 'static.
    let q: &'static [u8] = Box::leak(make_query(domain).into_boxed_slice());
    let _ = domain::base::message::Message::from_slice(q).unwrap();
    std::borrow::Cow::Borrowed(q)
}
```

`build_null_response`/`build_servfail_response` are generic over `Octs`, so `&Message<&[u8]>` works. (This helper is used by the Task 4 tests; the Task 3 tests above can call `make_query` directly since the buffer stays alive in scope.)

```rust
        let qbuf = build_query_message("ads.example.com");
        let msg = domain::base::message::Message::from_slice(&qbuf).unwrap();
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --lib wire_tests` (workdir `rust/`)
Expected: FAIL — `build_null_response` / `build_servfail_response` do not exist.

- [ ] **Step 3: Implement the builders**

Add to `rust/src/proxy.rs`:

```rust
use domain::base::iana::{Class, Rcode};
use domain::base::message_builder::MessageBuilder;
use domain::base::name::Name;
use domain::base::{Serial, Ttl};
use domain::dep::octseq::octets::Octets;
use domain::rdata::Soa;

/// Builds the DNS payload for a blocked query: NXDOMAIN, authoritative,
/// with a root SOA in the authority section (TTL 1). Matches the shape of
/// the old handrolled `create_null_response` (ANCOUNT=0, NSCOUNT=1).
pub fn build_null_response<Octs: Octets + ?Sized>(
    query: &Message<Octs>,
) -> Option<Vec<u8>> {
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
        .push((root, Class::IN, Ttl::from_secs(1), soa))
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib wire_tests` (workdir `rust/`)
Expected: 5 tests PASS (2 from Task 2 + these 2 + `parse_query_rejects_garbage`).

- [ ] **Step 5: Commit**

```bash
git add rust/src/proxy.rs rust/src/wire_test_util.rs
git commit -m "feat(proxy): build NXDOMAIN/SERVFAIL responses via domain crate"
```

---

### Task 4: TTL extraction — `min_ttl` (replaces `get_min_ttl`)

**Files:**
- Modify: `rust/src/proxy.rs`
- Test: `#[cfg(test)] mod wire_tests` in `rust/src/proxy.rs`

- [ ] **Step 1: Write the failing tests**

Append inside `mod wire_tests`:

```rust
    #[test]
    fn min_ttl_takes_minimum_over_answers() {
        use domain::base::iana::{Class, Rtype};
        use domain::base::message_builder::MessageBuilder;
        use domain::base::name::Name;
        use domain::base::Ttl;
        use domain::rdata::A;
        use std::net::Ipv4Addr;

        let qbuf = build_query_message("ads.example.com");
        let msg = domain::base::message::Message::from_slice(&qbuf).unwrap();
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
        let qbuf = build_query_message("ads.example.com");
        let msg = domain::base::message::Message::from_slice(&qbuf).unwrap();
        // A query itself has no answer section records
        assert_eq!(min_ttl(msg.as_slice()), None);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --lib min_ttl` (workdir `rust/`)
Expected: FAIL — `min_ttl` does not exist.

- [ ] **Step 3: Implement `min_ttl`**

Add to `rust/src/proxy.rs`:

```rust
/// Returns the minimum TTL over all answer records of a DNS response.
/// `None` if the payload is not a parseable message or has no answers —
/// callers treat that as "do not cache".
pub fn min_ttl(payload: &[u8]) -> Option<u32> {
    let msg = Message::from_slice(payload).ok()?;
    let mut min: Option<u32> = None;
    for rec in msg.answer().ok()?.flatten() {
        let t = rec.ttl().as_secs();
        min = Some(match min {
            Some(m) => m.min(t),
            None => t,
        });
    }
    min
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib min_ttl` (workdir `rust/`)
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add rust/src/proxy.rs
git commit -m "feat(proxy): extract response TTLs via domain crate (min_ttl)"
```

---

### Task 5: Rewire `run_proxy` and delete the old handrolled code

**Files:**
- Modify: `rust/src/lib.rs:33-67` (delete `get_min_ttl`), `rust/src/lib.rs:135-143` (`update_blocklist` keeps `domain_to_wire_format` — unchanged), `rust/src/lib.rs:244-265` (query handling), `rust/src/lib.rs:414-424` (cache TTL call site)
- Modify: `rust/src/proxy.rs` (delete `extract_dns_name`, `to_lowercase_wire_format`, `to_trie_key`, `create_null_response`, `create_servfail_response`; keep `create_forwarded_response`, `create_tcp_rst`, `create_icmp_unreachable`)

- [ ] **Step 1: Rewire the query path in `run_proxy`**

In `rust/src/lib.rs`, update the import from proxy (line 6) to:

```rust
use proxy::{parse_query, build_null_response, build_servfail_response, min_ttl, create_forwarded_response, create_tcp_rst};
```

Replace the query-handling block (currently `lib.rs:244-265`) with:

```rust
                                if let Some((cache_key, trie_key, _qtype)) = parse_query(payload) {
                                    let full_cache_key = Some(cache_key);

                                    // Reversed key for blocklist trie lookups
                                    let blocked = {
                                        let lock = blocklist.read().unwrap_or_else(|e| e.into_inner());
                                        lock.get_ancestor_value(&trie_key).is_some()
                                    };

                                    if blocked {
                                        // Re-parse is intentional and cheap: the builders need
                                        // the full Message, not just the derived keys.
                                        if let Ok(query) = domain::base::message::Message::from_slice(payload) {
                                            if let Some(resp) = build_null_response(&query) {
                                                let _ = tx.try_send(resp);
                                            }
                                        }
                                    } else {
```

Notes:
- The old block's manual qtype/qclass-append logic (`let mut idx = 12; while idx < payload.len() ...` and the `full_cache_key = if idx + 2 <= payload.len() ...` expression) is deleted — `parse_query` now produces the full cache key.
- The `blocked` branch previously called `create_null_response(&sliced, payload)`; the new builders take the parsed `Message` instead of `&SlicedPacket`.
- The remainder of the `else` branch (cache lookup, DoQ forwarding, `use_cache`/`continue` logic) is unchanged. `full_cache_key` is still `Option<Vec<u8>>` and the existing `if let Some(ck) = &full_cache_key` checks still compile unchanged.

- [ ] **Step 2: Rewire the SERVFAIL and cache-TTL call sites**

There are two SERVFAIL sites in `run_proxy` — the 3-second-timeout one (roughly `lib.rs:439-446`, inside the spawned task, using `payload_vec`/`tx_clone`) and the semaphore-full one (roughly `lib.rs:455-462`, using `payload`/`tx`). Change each from the pattern:

```rust
                                                    if let Some(resp) = create_servfail_response(&sliced, &payload_vec) {
                                                        let _ = tx_clone.try_send(resp);
                                                    }
```

to:

```rust
                                                    if let Ok(query) = domain::base::message::Message::from_slice(&payload_vec) {
                                                        if let Some(resp) = build_servfail_response(&query) {
                                                            let _ = tx_clone.try_send(resp);
                                                        }
                                                    }
```

(matching whichever payload variable and sender each site currently uses).

The cache-TTL site (roughly `lib.rs:415`) changes from `get_min_ttl(&resp_payload)` to:

```rust
                                                                        if let Some(ttl) = min_ttl(&resp_payload) {
```

- [ ] **Step 3: Delete dead code**

- In `rust/src/lib.rs`: delete `get_min_ttl` (lines 33-67).
- In `rust/src/proxy.rs`: delete `extract_dns_name`, `to_lowercase_wire_format`, `to_trie_key`, `create_null_response`, `create_servfail_response`. Keep `create_forwarded_response`, `create_tcp_rst`, `create_icmp_unreachable` and their helpers.

- [ ] **Step 3: Delete dead code**

- In `rust/src/lib.rs`: delete `get_min_ttl` (lines 33-67).
- In `rust/src/proxy.rs`: delete `extract_dns_name`, `to_lowercase_wire_format`, `to_trie_key`, `create_null_response`, `create_servfail_response`. Keep `create_forwarded_response`, `create_tcp_rst`, `create_icmp_unreachable` and their helpers.
- In `rust/src/lib.rs`, update the `use proxy::{...}` line so it lists exactly: `parse_query, build_null_response, build_servfail_response, min_ttl, create_forwarded_response, create_tcp_rst`.

- [ ] **Step 4: Full build and test**

Run: `cargo test && cargo check` (workdir `rust/`)
Expected: all tests pass (old `trie_test.rs` still passes — it is self-contained; new wire tests pass); no warnings about unused functions.

- [ ] **Step 5: Verify Android target still compiles**

Run: `rustup target list --installed` to confirm `aarch64-linux-android` (or the targets the build uses) is present, then:

```bash
cargo check --target aarch64-linux-android
```

(workdir `rust/`). If no Android target/ndk is installed in this environment, note that in the task report and skip — the regular `cargo check` above is the gate.

Expected: compiles cleanly.

- [ ] **Step 6: Commit**

```bash
git add rust/src/lib.rs rust/src/proxy.rs
git commit -m "refactor(proxy): route query handling through domain crate, delete handrolled DNS wire code"
```

---

### Task 6: Behavioral spot-check (optional but recommended)

**Files:**
- Modify: none (verification only)

- [ ] **Step 1: Add a temporary integration assertion**

The existing `rust/tests/trie_test.rs` covers trie semantics. To confirm the new pipeline end-to-end, temporarily append to `rust/src/proxy.rs`'s `mod wire_tests`:

```rust
    #[test]
    fn blocked_subdomain_of_blocked_parent_is_blocked() {
        // Insert "example.com" into a trie the way update_blocklist does,
        // then check that parse_query("ads.example.com") hits it.
        use radix_trie::Trie;
        let mut trie = Trie::new();
        trie.insert(crate::domain_to_wire_format("example.com"), ());
        let q = crate::wire_test_util::make_query("ads.example.com");
        let (_, trie_key, _) = parse_query(&q).unwrap();
        assert!(trie.get_ancestor_value(&trie_key).is_some());
    }
```

Note: `domain_to_wire_format` is a private fn in `lib.rs` — add `pub(crate)` to its signature (`pub(crate) fn domain_to_wire_format(domain: &str) -> Vec<u8>`) and expose `wire_test_util` accordingly (`make_query` is already `pub` within the crate-visible module).

- [ ] **Step 2: Run and keep or drop**

Run: `cargo test --lib` (workdir `rust/`) — Expected: PASS.

Decide: keep the test and the `pub(crate)` change (harmless, improves coverage), or revert this task entirely. If keeping:

```bash
git add rust/src/lib.rs rust/src/proxy.rs
git commit -m "test(proxy): end-to-end trie + parse_query blocking assertion"
```
