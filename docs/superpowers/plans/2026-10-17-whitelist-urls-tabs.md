# Whitelist Screen: URLs + Apps Tabs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the "Whitelisted Apps" screen into a "Whitelist" screen with two tabs — URLs (default, whitelist by URL/domain) and Apps (existing VPN bypass list).

**Architecture:** All UI changes live in `fyi.justfreesoftware.justblock.ui`. A new `WhitelistScreen` composable hosts the header, a Material3 `TabRow`, and delegates to two tab composables (`UrlsTab`, `AppsTab`). URL→domain extraction is a pure JVM helper (`WhitelistUrlParser`) called by a new `VpnViewModel.addWhitelistedUrl`. Persistence, blocklist exclusion, live `DnsFilter` updates, and DNS cache flushing already exist and are reused unchanged.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, BOM-managed), DataStore Preferences, JUnit 4, Gradle wrapper (run from `android/`).

**Spec:** `docs/superpowers/specs/2026-10-17-whitelist-urls-tabs-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParser.kt` | Create | Pure URL→domain extraction (JVM-testable) |
| `android/app/src/test/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParserTest.kt` | Create | Unit tests for the parser |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/VpnViewModel.kt` | Modify | Add `AddUrlResult` enum + `addWhitelistedUrl()` in the "Whitelisted domains" section |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsTab.kt` | Create | App bypass list content (moved from `AppsScreen.kt`) |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/UrlsTab.kt` | Create | Whitelist-by-URL input + domain list |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistScreen.kt` | Create | Header + tabs + shared snackbar + info dialog |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsScreen.kt` | Delete | Superseded by the three new files |
| `android/app/src/main/java/fyi/justfreesoftware/justblock/MainActivity.kt` | Modify | Tab 2 calls `WhitelistScreen` instead of `AppsScreen` |

All gradle commands below run from the `android/` directory.

---

### Task 1: WhitelistUrlParser + unit tests

**Files:**
- Create: `android/app/src/test/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParserTest.kt`
- Create: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParser.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParserTest.kt`:

```kotlin
package fyi.justfreesoftware.justblock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhitelistUrlParserTest {

    @Test
    fun fullHttpsUrlWithPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://example.com/article/123"))
    }

    @Test
    fun bareDomain() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("example.com"))
    }

    @Test
    fun bareDomainWithPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("example.com/some/page"))
    }

    @Test
    fun wwwPrefixStrippedFromUrl() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://www.example.com/article"))
    }

    @Test
    fun wwwPrefixStrippedFromBareDomain() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("www.example.com"))
    }

    @Test
    fun mixedCaseHostLowercased() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("https://WWW.Example.COM/"))
    }

    @Test
    fun surroundingWhitespaceTrimmed() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("  https://example.com  "))
    }

    @Test
    fun httpWithPortAndPath() {
        assertEquals("example.com", WhitelistUrlParser.extractDomain("http://www.example.com:8080/x"))
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("   "))
    }

    @Test
    fun emptyInputReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain(""))
    }

    @Test
    fun unparseableFreeTextReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("not a url"))
    }

    @Test
    fun schemeOnlyReturnsNull() {
        assertNull(WhitelistUrlParser.extractDomain("http://"))
    }

    @Test
    fun literalWwwComKeptIntact() {
        assertEquals("www.com", WhitelistUrlParser.extractDomain("www.com"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "fyi.justfreesoftware.justblock.ui.WhitelistUrlParserTest"`
Expected: FAIL — compilation error, `Unresolved reference: WhitelistUrlParser`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParser.kt`:

```kotlin
package fyi.justfreesoftware.justblock.ui

import java.net.URI
import java.net.URISyntaxException

/**
 * Extracts the domain to whitelist from raw user input, which may be a full
 * URL (`https://example.com/article`) or a bare domain (`example.com`).
 *
 * A leading `www.` is stripped so the entry matches blocklist entries keyed
 * by the bare domain. Returns null when no usable host can be parsed.
 */
object WhitelistUrlParser {

    fun extractDomain(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = try {
            URI(withScheme).host
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val lowered = host.lowercase()
        // Only strip "www." when what remains is still a multi-label domain
        // (keeps a literal "www.com" intact).
        val stripped = lowered.removePrefix("www.")
        val cleaned = if (lowered.startsWith("www.") && stripped.contains(".")) stripped else lowered
        return cleaned.ifBlank { null }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "fyi.justfreesoftware.justblock.ui.WhitelistUrlParserTest"`
Expected: PASS — 13 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/test/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParserTest.kt android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistUrlParser.kt
git commit -m "feat: add WhitelistUrlParser for URL-to-domain extraction"
```

---

### Task 2: VpnViewModel.addWhitelistedUrl

**Files:**
- Modify: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/VpnViewModel.kt` (in the "Whitelisted domains" section, lines ~363–392)

- [ ] **Step 1: Add the enum and function**

In `VpnViewModel.kt`, inside the class body, directly after the existing `removeWhitelistedDomain` function (which ends around line 392), add:

```kotlin
    /** Outcome of adding a URL/domain via the Whitelist screen's URLs tab. */
    enum class AddUrlResult { Added, AlreadyWhitelisted, InvalidUrl }

    /**
     * Whitelists the domain of [raw], which may be a full URL or a bare
     * domain. Returns the outcome so the UI can show an appropriate
     * snackbar.
     */
    fun addWhitelistedUrl(raw: String): AddUrlResult {
        val domain = WhitelistUrlParser.extractDomain(raw)
            ?: return AddUrlResult.InvalidUrl
        if (domain in whitelistedDomains.value) return AddUrlResult.AlreadyWhitelisted
        addWhitelistedDomain(domain)
        return AddUrlResult.Added
    }
```

No new imports needed — `WhitelistUrlParser` is in the same package.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/fyi/justfreesoftware/justblock/ui/VpnViewModel.kt
git commit -m "feat: add addWhitelistedUrl to VpnViewModel"
```

---

### Task 3: Extract the app list into AppsTab

**Files:**
- Create: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsTab.kt`

This is a code move: the content of `AppsScreen.kt` (app loading, search, list, toggle snackbar) becomes the `AppsTab` composable. The `Scaffold`, header row, and info dialog stay behind — they move to `WhitelistScreen` in Task 5. `AppsScreen.kt` keeps compiling untouched until Task 5 deletes it; the `private` helpers (`AppInfo`, `toBitmap`, `AppRow`) don't clash across files.

- [ ] **Step 1: Create the file**

Create `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsTab.kt`:

```kotlin
package fyi.justfreesoftware.justblock.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

/**
 * Data class holding the display-relevant info for a single installed app.
 * Resolved eagerly from [PackageManager] so the lazy list doesn't hit
 * the PM on every recomposition.
 */
private data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
)

/** Converts any [Drawable] to a [Bitmap]. Handles both BitmapDrawable and other types. */
private fun Drawable.toBitmap(size: Int = 96): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val bmp = createBitmap(
        if (intrinsicWidth > 0) intrinsicWidth else size,
        if (intrinsicHeight > 0) intrinsicHeight else size,
    )
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

@Composable
fun AppsTab(
    viewModel: VpnViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bypassedApps by viewModel.bypassedApps.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Resolve installed apps off the main thread
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }  // Exclude ourselves
                .map { appInfo ->
                    AppInfo(
                        packageName = appInfo.packageName,
                        label       = appInfo.loadLabel(pm).toString(),
                        icon        = appInfo.loadIcon(pm),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    var searchQuery by remember { mutableStateOf("") }

    val displayedApps = remember(installedApps, searchQuery, bypassedApps) {
        installedApps
            .filter { app ->
                if (searchQuery.isBlank()) return@filter true
                app.label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith(compareByDescending<AppInfo> { it.packageName in bypassedApps }
                .thenBy { it.label.lowercase() })
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Search bar ────────────────────────────────────────────────────
        OutlinedTextField(
            value         = searchQuery,
            onValueChange = { searchQuery = it },
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder   = { Text("Search apps…") },
            leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine    = true,
            shape         = RoundedCornerShape(12.dp),
        )

        HorizontalDivider()

        // ── App list ──────────────────────────────────────────────────────
        if (installedApps.isEmpty()) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text  = "Loading apps... This may take a few seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = displayedApps,
                    key   = { it.packageName },
                ) { app ->
                    AppRow(
                        app           = app,
                        isBypassed    = app.packageName in bypassedApps,
                        onToggle      = { checked ->
                            viewModel.setAppBypassed(app.packageName, checked)
                            coroutineScope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    message = if (checked) "${app.label} added to bypass list" else "${app.label} removed from bypass list",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isBypassed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isBypassed) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment   = Alignment.CenterVertically,
    ) {
        val bitmap = remember(app.icon) { app.icon.toBitmap() }
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = app.label,
            modifier           = Modifier.size(40.dp),
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = app.label,
                style     = MaterialTheme.typography.bodyLarge,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
            Text(
                text      = app.packageName,
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Checkbox(
            checked    = isBypassed,
            onCheckedChange = null,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (unused-code warnings are fine).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsTab.kt
git commit -m "refactor: extract app list into AppsTab composable"
```

---

### Task 4: UrlsTab

**Files:**
- Create: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/UrlsTab.kt`

- [ ] **Step 1: Create the file**

Create `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/UrlsTab.kt`:

```kotlin
package fyi.justfreesoftware.justblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun UrlsTab(
    viewModel: VpnViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val whitelisted by viewModel.whitelistedDomains.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    fun addUrl() {
        if (input.isBlank()) return
        when (viewModel.addWhitelistedUrl(input)) {
            VpnViewModel.AddUrlResult.Added -> input = ""
            VpnViewModel.AddUrlResult.AlreadyWhitelisted -> {
                coroutineScope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = "Already whitelisted",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            VpnViewModel.AddUrlResult.InvalidUrl -> {
                coroutineScope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = "Couldn't read a domain from that — try e.g. example.com",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Add field ─────────────────────────────────────────────────────
        OutlinedTextField(
            value            = input,
            onValueChange    = { input = it },
            modifier         = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder      = { Text("Add URL or domain…") },
            leadingIcon      = { Icon(Icons.Default.Add, contentDescription = null) },
            singleLine       = true,
            shape            = RoundedCornerShape(12.dp),
            keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions  = KeyboardActions(onDone = { addUrl() }),
        )

        HorizontalDivider()

        // ── Whitelisted domains ───────────────────────────────────────────
        if (whitelisted.isEmpty()) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text  = "No whitelisted URLs yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = whitelisted.sorted(),
                    key   = { it },
                ) { domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text     = domain,
                            style    = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.removeWhitelistedDomain(domain) },
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/fyi/justfreesoftware/justblock/ui/UrlsTab.kt
git commit -m "feat: add UrlsTab for whitelisting by URL"
```

---

### Task 5: WhitelistScreen, MainActivity swap, delete AppsScreen

**Files:**
- Create: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistScreen.kt`
- Modify: `android/app/src/main/java/fyi/justfreesoftware/justblock/MainActivity.kt:42` and `:218-221`
- Delete: `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsScreen.kt`

These three changes go in one commit so the tree compiles at every commit point.

- [ ] **Step 1: Create WhitelistScreen.kt**

Create `android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistScreen.kt`:

```kotlin
package fyi.justfreesoftware.justblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun WhitelistScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    // 0 = URLs (default), 1 = Apps
    var selectedTab by remember { mutableStateOf(0) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    if (showInfoDialog) {
        Dialog(onDismissRequest = { showInfoDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Whitelist Info",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = """
                            URLs — Whitelisted domains are never blocked, no matter which blocklist they appear in. Paste a full URL or just a domain; the domain part is what gets whitelisted.

                            Apps — Selected apps bypass the VPN entirely and use your normal connection. All other apps will have their traffic routed through the VPN to be filtered.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showInfoDialog = false }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { scaffoldPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text  = "Whitelist",
                    style = MaterialTheme.typography.displayLarge,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showInfoDialog = true },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // ── Tabs ──────────────────────────────────────────────────────
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    text     = { Text("URLs") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    text     = { Text("Apps") },
                )
            }

            // ── Content ───────────────────────────────────────────────────
            when (selectedTab) {
                0 -> UrlsTab(
                    viewModel         = viewModel,
                    snackbarHostState = snackbarHostState,
                )
                1 -> AppsTab(
                    viewModel         = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Update MainActivity**

In `android/app/src/main/java/fyi/justfreesoftware/justblock/MainActivity.kt`:

Replace the import (line 42):

```kotlin
import fyi.justfreesoftware.justblock.ui.AppsScreen
```

with:

```kotlin
import fyi.justfreesoftware.justblock.ui.WhitelistScreen
```

Replace the navigation branch (lines 218–221):

```kotlin
                        2 -> AppsScreen(
                            viewModel = viewModel,
                            modifier  = Modifier.padding(padding),
                        )
```

with:

```kotlin
                        2 -> WhitelistScreen(
                            viewModel = viewModel,
                            modifier  = Modifier.padding(padding),
                        )
```

- [ ] **Step 3: Delete AppsScreen.kt**

```bash
git rm android/app/src/main/java/fyi/justfreesoftware/justblock/ui/AppsScreen.kt
```

- [ ] **Step 4: Full verification**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` — compiles, all unit tests pass (14 total: 13 parser + 1 template), debug APK builds.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/fyi/justfreesoftware/justblock/ui/WhitelistScreen.kt android/app/src/main/java/fyi/justfreesoftware/justblock/MainActivity.kt
git commit -m "feat: Whitelist screen with URLs and Apps tabs"
```

---

## Manual Verification (after all tasks)

On a device/emulator:

1. Bottom nav → "Whitelist" tab: header reads "Whitelist" with "URLs" tab selected by default
2. Type `https://www.example.com/some/article`, hit Done → list shows `example.com`
3. Type `example.com` again → "Already whitelisted" snackbar
4. Type `not a url` → "Couldn't read a domain from that…" snackbar
5. Delete icon on a row removes it
6. "Apps" tab: same search/list/toggle behaviour as before
7. Info dialog explains both tabs
