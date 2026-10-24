# Just Block

Just Block is a website blocker for Android that works by filtering DNS queries with a local VPN. Users can block individual websites, or load in entire blocklists.  
  
AI DISCLOSURE: This was made with the help of AI.

## How it Works

Just Block uses Android's `VpnService` API to establish a local TUN interface. It redirects all DNS traffic into this tunnel. The traffic is filtered by the Rust core integrated via UniFFI.

1. **Traffic Interception:** The TUN file descriptor is passed to the Rust `DnsProxy`.
2. **DNS Extraction:** The Rust proxy uses `etherparse` to parse IP/UDP packets and extract raw DNS queries.
3. **Filtering:** Queries are evaluated against a compiled Radix Trie in Rust containing blocked domains. Temporarily paused domains live in a second trie in Rust; their expiry is enforced lazily at query time, so a pause ends (and blocking resumes) even when the app's UI process — and its countdown timers — are gone. Whitelisted domains live in a third trie that is checked first and overrides both.
4. **Resolution or Blocking:**
   - **Blocked:** If the domain matches the trie, the proxy immediately responds with a null/unreachable response, preventing the ad/tracker from loading.
   - **Allowed:** If the domain is safe, the query is forwarded to an upstream DNS resolver using DNS-over-QUIC (DoQ) powered by the `quinn` crate in Rust. 

### Blocklist format
Every blocklist entry is treated as a **wildcard**: an entry `example.com` blocks `example.com` and all of its subdomains (`*.example.com`), whatever format the list uses. `FilterParser` in `BlocklistRepository.kt` reduces each line to a bare domain:

| Line | Becomes | Note |
|---|---|---|
| `example.com` | `example.com` | |
| `0.0.0.0 example.com` | `example.com` | A real hosts file blocks only the exact name; here subdomains are blocked too. Only the first host on a line is read. |
| `\|\|example.com^` | `example.com` | Same meaning as adblock syntax. |
| `*.example.com` | `example.com` | The apex domain is blocked as well. |
| `example.com##.ad`, `@@\|\|example.com^`, `\|\|example.com^$third-party`, `\|\|example.com/path^` | skipped | Cosmetic rules, exceptions, and rules with options or paths can't be expressed as a DNS block. |

### Core (`just_block_rust` & `fyi.justfreesoftware.justblock.core`)
The VPN orchestration and packet processing happens across Kotlin and Rust boundaries.
- `MyVpnService.kt`: The `VpnService` implementation. It manages the TUN interface, handles start/stop intents, and orchestrates the VPN lifecycle.
- `lib.rs` / `quic.rs` / `proxy.rs`: The Rust backend doing asynchronous packet reading/writing (via `tokio`), packet parsing (`etherparse`), DoQ connections (`quinn`), and blocklist enforcement (`radix_trie`).
- `DnsFilter.kt`: Manages the state of the blocklist in Kotlin (including paused/resumed domains) and syncs it with the Rust proxy.

### Data (`fyi.justfreesoftware.justblock.data`)
Manages blocklists and user preferences.
- `BlocklistRepository.kt`: Manages the downloading, parsing, and compilation of blocklist files from various sources.
- `BlocklistFetcher.kt`: Fetches blocklist raw text from URLs.
- `UserPreferences.kt`: DataStore wrapper for user settings, whitelisted apps, custom upstream configs, and manual domain rules.

### UI (`fyi.justfreesoftware.justblock.ui`)
Built entirely in Jetpack Compose with Material 3.
- `MainActivity.kt`: The single-activity entry point hosting the Compose navigation.
- `VpnViewModel.kt`: The main ViewModel bridging the UI and the core services. Exposes state via `StateFlow`.
- `HomeScreen.kt`: Shows the home tab with the main controls (VPN status, toggles, theme).
- `BlockingScreen.kt`: The blocking tab with two sub-tabs — `ManualTab.kt` (manually blocked/paused websites) and `BlocklistsTab.kt` (blocklist source URLs).
- `WhitelistScreen.kt`: The whitelist tab with two sub-tabs — `UrlsTab.kt` and `AppsTab.kt`.

### Prerequisites
- Android Studio Koala (or newer)
- Rust toolchain (cargo) and UniFFI bindings
- Minimum SDK: API 34 (Android 14)
- Target SDK: API 37

## Planned Features
- Support for other languages would be nice.
