# Whitelist Screen: URL + App Tabs — Design

Date: 2026-10-17
Status: Approved (design phase)

## Goal

Turn the "Whitelisted Apps" screen into a "Whitelist" screen with two tabs:

- **URLs** (default): whitelist domains by entering a URL or bare domain
- **Apps**: the existing per-app VPN bypass list (unchanged behaviour)

## Background

The domain-whitelist backend already exists and is fully wired:

- `UserPreferences.whitelistedDomains` — DataStore persistence (`WHITELISTED` key)
- `BlocklistRepository` — removes whitelisted domains from compiled blocklists on every refresh
- `VpnViewModel.addWhitelistedDomain` / `removeWhitelistedDomain` — persist + push to the live `DnsFilter` + flush DNS cache
- `MyVpnService` / `DnsFilter` — live enforcement

Only the UI and URL→domain extraction are new.

## Decisions

- **URL input resolves to a domain.** DNS filtering operates per-domain, so a
  full URL like `https://example.com/article/123` whitelists `example.com`.
  The list displays the extracted domain, not the raw URL.
- **Host handling:** whitelist the host as typed, but strip a leading `www.`
  (`www.example.com` → `example.com`). No public-suffix-list base-domain
  extraction (rejected: heavy dependency, can over-whitelist).

## UI Structure

### `WhitelistScreen.kt` (new)

- Header row: `"Whitelist"` in `displayLarge` (same styling as the current
  header) + existing info `IconButton`
- Material3 `TabRow` with two tabs: `"URLs"` (index 0, default) and `"Apps"`
- Hosts a single `SnackbarHost` shared by both tabs
- Content switch: index 0 → `UrlsTab`, index 1 → `AppsTab`
- Info dialog text updated to cover both tabs:
  - URLs: whitelisted domains are never blocked by any blocklist
  - Apps: selected apps bypass the VPN entirely and use the normal connection

### `UrlsTab.kt` (new)

- `OutlinedTextField`, placeholder `"Add URL or domain…"`, keyboard Done
  action triggers add
- `LazyColumn` of whitelisted domains, sorted alphabetically; each row shows
  the domain with a delete `IconButton` (`Icons.Default.Delete`, matching the
  `BlockedWebsitesScreen` convention)
- Empty state text when the list is empty

### `AppsTab.kt` (extracted)

- The entire current `AppsScreen.kt` content moves here unchanged: installed
  app loading off the main thread, search bar, sorted lazy list, toggle
  snackbar
- `AppsScreen.kt` is deleted

### `MainActivity.kt`

- Bottom-nav tab 2 calls `WhitelistScreen` instead of `AppsScreen` (label
  "Whitelist" already correct)

## URL → Domain Extraction

Extraction is a pure helper, `WhitelistUrlParser.extractDomain(raw: String): String?`
(plain JVM code, no Android dependencies), called by
`VpnViewModel.addWhitelistedUrl(raw: String): AddUrlResult` with:

```kotlin
enum class AddUrlResult { Added, AlreadyWhitelisted, InvalidUrl }
```

`extractDomain` steps:

1. Trim input; blank → null
2. If no `"://"` present, prepend `"https://"` (so bare `example.com/path` parses)
3. Parse with `java.net.URI`; null host or parse failure → null
4. Lowercase the host; strip a leading `"www."`; blank result → null

`addWhitelistedUrl` steps:

1. Call `extractDomain`; null → `InvalidUrl`
2. If the resulting domain is already in `whitelistedDomains.value` →
   `AlreadyWhitelisted`
3. Otherwise delegate to the existing `addWhitelistedDomain` path (persist,
   live `DnsFilter.removeDomain`, flush DNS cache) → `Added`

Removal uses the existing `removeWhitelistedDomain` unchanged.

## Error Handling

- Invalid URL → snackbar: `"Couldn't read a domain from that — try e.g. example.com"`
- Duplicate → snackbar: `"Already whitelisted"`
- Empty input → ignored silently
- Removal is unconditional; removing an absent entry is a no-op

## Testing

- New JVM unit test for `WhitelistUrlParser.extractDomain` (pure string/URI
  handling, no Android dependencies): covers full URLs, bare domains, `www.`
  stripping, scheme-less input, invalid input
- `gradlew assembleDebug` as the build/lint gate
- No UI test infrastructure exists; tabs and lists verified manually
