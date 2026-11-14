//! The filtering rules: blocklist, allowlist and pauses, and the decision
//! they add up to.
//!
//! Downloaded blocklists run to hundreds of thousands of entries, so no
//! domain names are kept. Each entry's key (see
//! [`crate::domain_to_wire_format`]) is hashed with SipHash under a random
//! per-process key and only the 64-bit hash is stored: 8 bytes per domain
//! in a single allocation. A trie cost about 400 bytes per domain.
//!
//! The price is that two different names with the same hash cannot be told
//! apart, so a collision would treat an unrelated domain as listed. With
//! 3,000,000 entries the chance is about 1 in 6 * 10^12 per probe.
//!
//! Every entry is a wildcard: a query matches an entry when the entry is
//! the queried name or one of its parent domains. A lookup walks the
//! query's key one label at a time, TLD first, and probes each prefix.

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::hash::{BuildHasher, BuildHasherDefault, Hasher, RandomState};
use std::io::{self, BufRead, BufReader, Read};
use std::path::Path;
use std::sync::OnceLock;

/// Passes an already-hashed `u64` straight through, so the tables do not
/// hash the SipHash output a second time.
#[derive(Default)]
pub(crate) struct Prehashed(u64);

impl Hasher for Prehashed {
    fn write(&mut self, bytes: &[u8]) {
        // Not reached for u64 keys; folded rather than panicking.
        for &b in bytes {
            self.0 = self.0.rotate_left(8) ^ u64::from(b);
        }
    }

    fn write_u64(&mut self, value: u64) {
        self.0 = value;
    }

    fn finish(&self) -> u64 {
        self.0
    }
}

type Hashed = BuildHasherDefault<Prehashed>;

/// Hashes a key under the process-wide SipHash key. All three lists share
/// it, so a query's prefixes are hashed once and probed in each list.
fn hash_key(key: &[u8]) -> u64 {
    static STATE: OnceLock<RandomState> = OnceLock::new();
    STATE.get_or_init(RandomState::new).hash_one(key)
}

/// The hash a list entry is stored under, or `None` if `domain` is not a
/// valid DNS name (or is empty).
fn hash_domain(domain: &str) -> Option<u64> {
    let key = crate::domain_to_wire_format(domain)?;
    (!key.is_empty()).then(|| hash_key(&key))
}

/// The hashes of `key`'s label-boundary prefixes, shortest first: the
/// queried name's TLD, then each longer parent, then the name itself.
/// `key` is the label-reversed wire form produced by `parse_query`.
fn ancestor_hashes(key: &[u8]) -> impl Iterator<Item = u64> + '_ {
    let mut end = 0;
    std::iter::from_fn(move || {
        if end >= key.len() {
            return None;
        }
        end += 1 + key[end] as usize;
        (end <= key.len()).then(|| hash_key(&key[..end]))
    })
}

const READ_BUFFER_BYTES: usize = 64 * 1024;

/// Lines in the file at `path`, counting a last line with no newline.
fn count_lines(path: &Path) -> io::Result<usize> {
    let mut file = File::open(path)?;
    let mut buffer = vec![0u8; READ_BUFFER_BYTES];
    let mut lines = 0;
    let mut last = b'\n';
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            return Ok(lines + usize::from(last != b'\n'));
        }
        lines += buffer[..read].iter().filter(|&&b| b == b'\n').count();
        last = buffer[read - 1];
    }
}

/// A set of domains, held as hashes.
#[derive(Default)]
pub(crate) struct DomainSet(HashSet<u64, Hashed>);

impl DomainSet {
    /// Adds every valid domain in `domains`; the rest are skipped.
    pub(crate) fn extend(&mut self, domains: impl IntoIterator<Item = String>) {
        self.0
            .extend(domains.into_iter().filter_map(|d| hash_domain(&d)));
    }

    /// Adds every valid domain in the file at `path`, one per line, unless
    /// its line count would take the set past `limit` entries; returns
    /// whether it was added. Nothing is added when it returns `Ok(false)`.
    ///
    /// This is how the Android side's cached lists are loaded: reading them
    /// here avoids passing every domain across the FFI as a string.
    pub(crate) fn extend_from_file(&mut self, path: &Path, limit: usize) -> io::Result<bool> {
        if self.len().saturating_add(count_lines(path)?) > limit {
            return Ok(false);
        }
        let mut reader = BufReader::with_capacity(READ_BUFFER_BYTES, File::open(path)?);
        let mut line = Vec::new();
        loop {
            line.clear();
            if reader.read_until(b'\n', &mut line)? == 0 {
                return Ok(true);
            }
            if let Ok(domain) = std::str::from_utf8(&line) {
                self.insert(domain.trim());
            }
        }
    }

    pub(crate) fn insert(&mut self, domain: &str) {
        if let Some(hash) = hash_domain(domain) {
            self.0.insert(hash);
        }
    }

    pub(crate) fn remove(&mut self, domain: &str) {
        if let Some(hash) = hash_domain(domain) {
            self.0.remove(&hash);
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.0.len()
    }

    pub(crate) fn shrink_to_fit(&mut self) {
        self.0.shrink_to_fit();
    }
}

impl FromIterator<String> for DomainSet {
    fn from_iter<I: IntoIterator<Item = String>>(domains: I) -> Self {
        let mut set = Self::default();
        set.extend(domains);
        set
    }
}

/// Pure expiry predicate: true when a pause lapsed by `now_ms`.
pub(crate) fn pause_expired(now_ms: i64, expiry: i64) -> bool {
    now_ms >= expiry
}

pub(crate) struct Rules {
    pub(crate) blocklist: DomainSet,
    pub(crate) allowlist: DomainSet,
    /// Paused domain → expiry in epoch millis (`i64::MAX` = indefinite).
    pauses: HashMap<u64, i64, Hashed>,
    /// When false nothing is blocked; queries are still forwarded.
    pub(crate) blocking_enabled: bool,
}

impl Default for Rules {
    fn default() -> Self {
        Self {
            blocklist: DomainSet::default(),
            allowlist: DomainSet::default(),
            pauses: HashMap::default(),
            blocking_enabled: true,
        }
    }
}

impl Rules {
    pub(crate) fn set_pauses(&mut self, pauses: HashMap<String, i64>) {
        self.pauses = pauses
            .into_iter()
            .filter_map(|(domain, expiry)| Some((hash_domain(&domain)?, expiry)))
            .collect();
    }

    /// Empties all three lists. `blocking_enabled` is left as it is.
    pub(crate) fn clear(&mut self) {
        self.blocklist = DomainSet::default();
        self.allowlist = DomainSet::default();
        self.pauses = HashMap::default();
    }

    /// Query-path block decision for `key`, the label-reversed wire form
    /// produced by `parse_query`.
    ///
    /// - The allowlist wins over everything: a whitelisted domain (or any
    ///   subdomain of it) is never blocked.
    /// - A blocklist entry blocks the name and its subdomains, unless that
    ///   exact entry is paused. A paused entry counts as absent from the
    ///   blocklist, whether or not the pause has expired.
    /// - The closest paused parent of the name blocks it once its pause has
    ///   expired. Expiry is checked here, at query time, so a pause ends
    ///   even when the Android UI process and its timers are gone. The
    ///   clock is only read when a pause actually covers the name.
    pub(crate) fn should_block(&self, key: &[u8]) -> bool {
        if !self.blocking_enabled {
            return false;
        }
        if self.blocklist.0.is_empty() && self.pauses.is_empty() {
            return false;
        }
        let mut listed = false;
        let mut closest_pause = None;
        for hash in ancestor_hashes(key) {
            if self.allowlist.0.contains(&hash) {
                return false;
            }
            match self.pauses.get(&hash) {
                Some(&expiry) => closest_pause = Some(expiry),
                None => listed |= self.blocklist.0.contains(&hash),
            }
        }
        listed || closest_pause.is_some_and(|expiry| pause_expired(crate::now_epoch_ms(), expiry))
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::now_epoch_ms;
    use crate::wire_test_util::trie_key_for as key;

    pub(crate) fn rules(block: &[&str], allow: &[&str], pauses: &[(&str, i64)]) -> Rules {
        let mut rules = Rules {
            blocklist: block.iter().map(|d| d.to_string()).collect(),
            allowlist: allow.iter().map(|d| d.to_string()).collect(),
            ..Rules::default()
        };
        rules.set_pauses(pauses.iter().map(|(d, e)| (d.to_string(), *e)).collect());
        rules
    }

    fn blocks(domains: &[&str]) -> Rules {
        rules(domains, &[], &[])
    }

    fn pauses(pauses: &[(&str, i64)]) -> Rules {
        rules(&[], &[], pauses)
    }

    // ── Blocklist ─────────────────────────────────────────────────────

    #[test]
    fn entry_covers_itself_and_all_subdomains() {
        let r = blocks(&["example.com"]);
        assert!(r.should_block(&key("example.com")));
        assert!(r.should_block(&key("ads.example.com")));
        assert!(r.should_block(&key("a.b.c.example.com")));
    }

    #[test]
    fn entry_does_not_cover_parents_siblings_or_lookalikes() {
        let r = blocks(&["ads.example.com"]);
        assert!(!r.should_block(&key("example.com")));
        assert!(!r.should_block(&key("com")));
        assert!(!r.should_block(&key("cdn.example.com")));
        assert!(!r.should_block(&key("notads.example.com")));
        let r = blocks(&["example.com"]);
        assert!(!r.should_block(&key("notexample.com")));
        assert!(!r.should_block(&key("example.com.evil.net")));
        assert!(!r.should_block(&key("other.com")));
    }

    #[test]
    fn redundant_child_entry_does_not_hide_its_parent() {
        let r = blocks(&["example.com", "ads.example.com"]);
        assert!(r.should_block(&key("cdn.example.com")));
        assert!(r.should_block(&key("zzz.example.com")));
    }

    #[test]
    fn entries_are_case_insensitive_and_deduplicated() {
        let r = blocks(&["EXAMPLE.com", "example.COM", "example.com"]);
        assert_eq!(r.blocklist.len(), 1);
        assert!(r.should_block(&key("example.com")));
    }

    #[test]
    fn invalid_and_empty_entries_are_skipped() {
        // A 64-byte label exceeds the DNS label limit and must be dropped,
        // not panic.
        let long = format!("{}.com", "a".repeat(64));
        let r = rules(&[&long, "", "."], &[&long, ""], &[(&long, 0), ("", 0)]);
        assert_eq!(r.blocklist.len(), 0);
        assert_eq!(r.allowlist.len(), 0);
        assert!(r.pauses.is_empty());
        assert!(!r.should_block(&key("example.com")));
    }

    #[test]
    fn empty_rules_and_empty_or_malformed_keys_never_match() {
        assert!(!Rules::default().should_block(&key("example.com")));
        let r = blocks(&["example.com"]);
        assert!(!r.should_block(&[]));
        // Length byte runs past the end of the key.
        assert!(!r.should_block(&[3, b'c', b'o']));
    }

    /// A file in the system temp dir, removed on drop.
    struct TempFile(std::path::PathBuf);

    impl TempFile {
        fn new(name: &str, contents: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "just_block_{}_{name}_{}",
                std::process::id(),
                now_epoch_ms()
            ));
            std::fs::write(&path, contents).unwrap();
            Self(path)
        }
    }

    impl Drop for TempFile {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    #[test]
    fn file_of_domains_is_loaded_line_by_line() {
        // CRLF, a blank line, an invalid name, a duplicate, and no final newline.
        let long = "a".repeat(64);
        let file = TempFile::new(
            "load",
            &format!("example.com\r\n\nADS.example.net\n{long}.com\nexample.com\nlast.org"),
        );
        let mut r = Rules::default();
        assert!(r.blocklist.extend_from_file(&file.0, 1000).unwrap());
        assert_eq!(r.blocklist.len(), 3);
        assert!(r.should_block(&key("www.example.com")));
        assert!(r.should_block(&key("ads.example.net")));
        assert!(r.should_block(&key("last.org")));
        assert!(!r.should_block(&key("example.net")));
    }

    #[test]
    fn file_over_the_limit_adds_nothing() {
        let file = TempFile::new("limit", "a.com\nb.com\nc.com\n");
        let mut set = DomainSet::default();
        set.insert("already.com");
        // 1 held + 3 lines = 4: over a limit of 3, exactly at a limit of 4.
        assert!(!set.extend_from_file(&file.0, 3).unwrap());
        assert_eq!(set.len(), 1);
        assert!(set.extend_from_file(&file.0, 4).unwrap());
        assert_eq!(set.len(), 4);
    }

    #[test]
    fn lines_are_counted_with_or_without_a_final_newline() {
        for (contents, lines) in [
            ("", 0),
            ("a.com", 1),
            ("a.com\n", 1),
            ("a.com\nb.com", 2),
            ("\n\n", 2),
        ] {
            let file = TempFile::new("count", contents);
            assert_eq!(count_lines(&file.0).unwrap(), lines, "{contents:?}");
        }
    }

    #[test]
    fn missing_file_is_an_error() {
        let mut set = DomainSet::default();
        assert!(
            set.extend_from_file(Path::new("/nonexistent/just_block_list"), 10)
                .is_err()
        );
    }

    #[test]
    fn single_entries_can_be_added_and_removed() {
        let mut r = blocks(&["example.com"]);
        r.blocklist.insert("Other.com");
        assert!(r.should_block(&key("www.other.com")));
        r.blocklist.remove("example.com");
        assert!(!r.should_block(&key("example.com")));
        assert!(r.should_block(&key("other.com")));
    }

    #[test]
    fn nothing_is_blocked_while_blocking_is_disabled() {
        let mut r = rules(&["example.com"], &[], &[("paused.com", now_epoch_ms() - 1)]);
        r.blocking_enabled = false;
        assert!(!r.should_block(&key("example.com")));
        assert!(!r.should_block(&key("paused.com")));
        r.blocking_enabled = true;
        assert!(r.should_block(&key("example.com")));
        assert!(r.should_block(&key("paused.com")));
    }

    #[test]
    fn clear_empties_every_list() {
        let mut r = rules(&["a.com"], &["b.com"], &[("c.com", 0)]);
        r.clear();
        assert!(!r.should_block(&key("a.com")));
        assert!(!r.should_block(&key("c.com")));
        assert_eq!(r.allowlist.len(), 0);
    }

    // ── Pauses ────────────────────────────────────────────────────────

    #[test]
    fn expiry_boundary_is_inclusive() {
        assert!(pause_expired(1000, 1000));
        assert!(pause_expired(1001, 1000));
        assert!(!pause_expired(999, 1000));
    }

    #[test]
    fn active_pause_does_not_block() {
        let r = pauses(&[("example.com", now_epoch_ms() + 60_000)]);
        assert!(!r.should_block(&key("example.com")));
        assert!(!r.should_block(&key("sub.example.com")));
    }

    #[test]
    fn expired_pause_blocks_the_domain_and_its_subdomains() {
        let r = pauses(&[("example.com", now_epoch_ms() - 1)]);
        assert!(r.should_block(&key("example.com")));
        assert!(r.should_block(&key("sub.example.com")));
        assert!(!r.should_block(&key("other.com")));
    }

    #[test]
    fn indefinite_pause_never_expires() {
        let r = pauses(&[("example.com", i64::MAX)]);
        assert!(!r.should_block(&key("example.com")));
    }

    #[test]
    fn pause_keys_are_case_insensitive() {
        let r = pauses(&[("EXAMPLE.com", now_epoch_ms() - 1)]);
        assert!(r.should_block(&key("example.com")));
    }

    #[test]
    fn active_pause_lifts_its_own_blocklist_entry() {
        let r = rules(
            &["example.com"],
            &[],
            &[("example.com", now_epoch_ms() + 60_000)],
        );
        assert!(!r.should_block(&key("example.com")));
        assert!(!r.should_block(&key("www.example.com")));
    }

    #[test]
    fn pause_lifts_only_the_exact_entry() {
        // Pausing example.com leaves a separately listed subdomain blocked,
        // and pausing a subdomain leaves a listed parent blocking it.
        let later = now_epoch_ms() + 60_000;
        let r = rules(
            &["example.com", "ads.example.com"],
            &[],
            &[("example.com", later)],
        );
        assert!(!r.should_block(&key("www.example.com")));
        assert!(r.should_block(&key("ads.example.com")));
        let r = rules(&["example.com"], &[], &[("www.example.com", later)]);
        assert!(r.should_block(&key("www.example.com")));
    }

    #[test]
    fn closest_pause_decides() {
        let now = now_epoch_ms();
        let r = pauses(&[("example.com", now - 1), ("sub.example.com", now + 60_000)]);
        assert!(r.should_block(&key("example.com")));
        assert!(r.should_block(&key("other.example.com")));
        assert!(!r.should_block(&key("sub.example.com")));
        assert!(!r.should_block(&key("x.sub.example.com")));
    }

    // ── Allowlist ─────────────────────────────────────────────────────

    #[test]
    fn whitelist_overrides_identical_block() {
        let r = rules(&["example.com"], &["example.com"], &[]);
        assert!(!r.should_block(&key("example.com")));
        assert!(!r.should_block(&key("www.example.com")));
    }

    #[test]
    fn whitelisted_parent_overrides_blocked_subdomain() {
        // Blocklists often list "www.example.com" separately; whitelisting
        // the bare domain (what DomainInputParser produces) must cover it.
        let r = rules(
            &["www.example.com", "cdn.example.com"],
            &["example.com"],
            &[],
        );
        assert!(!r.should_block(&key("www.example.com")));
        assert!(!r.should_block(&key("cdn.example.com")));
    }

    #[test]
    fn whitelisted_subdomain_overrides_blocked_parent() {
        let r = rules(&["example.com"], &["docs.example.com"], &[]);
        assert!(!r.should_block(&key("docs.example.com")));
        assert!(r.should_block(&key("ads.example.com")));
        assert!(r.should_block(&key("example.com")));
    }

    #[test]
    fn whitelist_overrides_expired_pause() {
        let r = rules(
            &[],
            &["example.com"],
            &[("example.com", now_epoch_ms() - 1)],
        );
        assert!(!r.should_block(&key("example.com")));
    }

    #[test]
    fn whitelist_does_not_leak_to_lookalike_domains() {
        let r = rules(&["example.com", "notexample.com"], &["example.com"], &[]);
        assert!(r.should_block(&key("notexample.com")));
    }

    // ── Against a reference model ─────────────────────────────────────

    /// The decision restated over plain strings: what the three tries this
    /// module replaced computed.
    fn reference(
        block: &[String],
        allow: &[String],
        pauses: &[(String, i64)],
        query: &str,
        now: i64,
    ) -> bool {
        // The name and each parent, closest (longest) first.
        let labels: Vec<&str> = query.split('.').collect();
        let ancestors: Vec<String> = (0..labels.len()).map(|i| labels[i..].join(".")).collect();
        if ancestors.iter().any(|a| allow.contains(a)) {
            return false;
        }
        let paused = |d: &String| pauses.iter().find(|(p, _)| p == d).map(|(_, e)| *e);
        let listed = ancestors
            .iter()
            .any(|a| block.contains(a) && paused(a).is_none());
        let closest_pause = ancestors.iter().find_map(paused);
        listed || closest_pause.is_some_and(|e| now >= e)
    }

    #[test]
    fn agrees_with_the_reference_model_on_generated_rules() {
        let labels = ["a", "ads", "b", "cdn", "example", "notexample", "x", "www"];
        let tlds = ["com", "net"];
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move |n: usize| {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed % n as u64) as usize
        };
        let name = |next: &mut dyn FnMut(usize) -> usize| {
            let mut parts: Vec<&str> = (0..next(4)).map(|_| labels[next(labels.len())]).collect();
            parts.push(tlds[next(tlds.len())]);
            parts.join(".")
        };
        let now = now_epoch_ms();
        // Far enough from `now` that the clock moving mid-test cannot
        // change an answer.
        let expiries = [now - 3_600_000, now + 3_600_000, i64::MAX];
        for _ in 0..200 {
            let block: Vec<String> = (0..next(12)).map(|_| name(&mut next)).collect();
            let allow: Vec<String> = (0..next(3)).map(|_| name(&mut next)).collect();
            let mut paused: Vec<(String, i64)> = Vec::new();
            for _ in 0..next(5) {
                let domain = name(&mut next);
                if paused.iter().all(|(d, _)| *d != domain) {
                    paused.push((domain, expiries[next(expiries.len())]));
                }
            }
            let mut r = Rules {
                blocklist: block.iter().cloned().collect(),
                allowlist: allow.iter().cloned().collect(),
                ..Rules::default()
            };
            r.set_pauses(paused.iter().cloned().collect());
            for _ in 0..200 {
                let query = name(&mut next);
                assert_eq!(
                    r.should_block(&key(&query)),
                    reference(&block, &allow, &paused, &query, now),
                    "query {query}, block {block:?}, allow {allow:?}, pauses {paused:?}"
                );
            }
        }
    }
}
