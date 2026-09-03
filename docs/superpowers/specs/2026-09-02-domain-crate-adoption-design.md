# Design: Adopt the `domain` crate for DNS wire handling

Date: 2026-09-02

## Problem

The Rust proxy (`rust/src/proxy.rs`, `get_min_ttl` in `rust/src/lib.rs`) hand-rolls DNS
wire parsing and response construction. This works, but is hard to maintain and bails on
edge cases: `extract_dns_name` returns `None` for compressed question names, so those
queries slip past the blocklist unfiltered. No misbehavior has been observed in practice;
the motivation is maintainability and edge-case correctness.

## Decision

Targeted adoption of the `domain` crate (approach B): replace the handrolled DNS-specific
code where correctness risk is highest; keep the fast raw-byte paths that are already
simple and correct.

## Changes

### 1. Dependency

Add `domain = "0.12"` to `rust/Cargo.toml` with the default feature set (provides
`Message`, `MessageBuilder`, and `Name`; nothing beyond defaults is needed). No other new dependencies. `radix_trie` and
`etherparse` remain.

### 2. Query parsing (replaces `extract_dns_name`)

Parse inbound UDP/53 payloads with `Message::from_slice`. Malformed packets are dropped
(matching today's fallback). Use `sole_question()` to obtain the qname (`Name<&[u8]>`)
and qtype/qclass. This removes the compression-pointer bail-out: queries with a
compressed qname are now correctly checked against the blocklist.

Derived values:

- Cache key: `qname.to_lowercase()` composed to bytes (replaces `to_lowercase_wire_format`).
- Trie key: lowercased labels iterated and reversed into the existing wire-key format
  (replaces `to_trie_key`). Output format is unchanged, so trie semantics and the
  Kotlin-supplied blocklist keys are untouched.

### 3. Response generation (replaces `create_null_response` / `create_servfail_response`)

Build DNS payloads with `MessageBuilder`:

- Blocked query: reply marked authoritative with an SOA answer record (root MNAME/RNAME,
  TTL 1), matching today's NXDOMAIN-with-SOA behavior (ANCOUNT=0, NSCOUNT=1).
- Unforwardable query: reply with RCODE=SERVFAIL and empty answer sections.

IP/UDP wrapping stays with `etherparse` (`PacketBuilder`), unchanged.

### 4. TTL extraction (replaces `get_min_ttl`)

Parse the upstream DoQ response with `Message::from_slice` and take the minimum TTL over
answer records. On parse failure, skip caching (forward without caching) — equivalent to
today's `None` path. `get_min_ttl` is deleted.

### 5. Kept as-is

- `create_forwarded_response`: raw byte-patch of the transaction ID; upstream bytes pass
  through untouched. This fast path is preserved.
- `radix_trie` blocklist storage and lookup; `domain_to_wire_format` (Kotlin-side keys).
- `create_tcp_rst` and `create_icmp_unreachable` (TCP/ICMP, not DNS).

## Testing

Unit tests in `rust/tests/` using `domain`'s own `MessageBuilder` to construct queries:

- Well-formed A query for a blocked domain → NXDOMAIN payload with SOA, correct flags/counts.
- Query with compressed qname → correctly blocked (previously forwarded unfiltered).
- Malformed payload → dropped, no panic.
- Upstream response parsing → min TTL extracted correctly, including CNAME chains;
  malformed upstream response → no caching.

For well-formed queries the generated DNS payloads must match current behavior at the
DNS-payload level (header flags, section counts, SOA contents).

## Non-goals

- No Kotlin-side changes.
- No behavior change for well-formed traffic (aside from compressed-qname queries now
  being filtered).
- Whitelist, pause, and blocklist management logic untouched.
