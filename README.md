# Just Block

Just Block is a website blocker for Android that works by filtering DNS queries with a local VPN. Users can block individual websites, or load in entire blocklists.  
  
AI DISCLOSURE: This was made with the help of AI.

## How it Works

Just Block uses Android's `VpnService` API to establish a local TUN interface. It redirects all DNS traffic into this tunnel. The traffic is filtered by the Rust core integrated via UniFFI.

1. **Traffic Interception:** The TUN file descriptor is passed to the Rust `DnsProxy`.
2. **DNS Extraction:** The Rust proxy uses `etherparse` to parse IP/UDP packets and extract raw DNS queries.
3. **Filtering:** Queries are evaluated against a compiled Radix Trie in Rust containing blocked domains.
4. **Resolution or Blocking:**
   - **Blocked:** If the domain matches the trie, the proxy immediately responds with a null/unreachable response, preventing the ad/tracker from loading.
   - **Allowed:** If the domain is safe, the query is forwarded to an upstream DNS resolver using DNS-over-QUIC (DoQ) powered by the `quinn` crate in Rust. 

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
- `BlockedWebsitesScreen.kt`: Shows the home tab with the main controls and blocked/paused websites.
- `BlocklistsScreen.kt`: Allows users to manage blocklist source URLs.
- `AppsScreen.kt`: Allows users to exclude apps from the filter.

### Prerequisites
- Android Studio Koala (or newer)
- Rust toolchain (cargo) and UniFFI bindings
- Minimum SDK: API 34 (Android 14)
- Target SDK: API 37

## Planned Features
- Support for other languages would be nice.
