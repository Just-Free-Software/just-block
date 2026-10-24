package fyi.justfreesoftware.justblock.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun ManualTab(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val manualBlocked by viewModel.manualBlockedDomains.collectAsState()
    val pausedDomains by viewModel.pausedDomains.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { focusManager.clearFocus() }
            }
    ) {
        // ── Add field ─────────────────────────────────────────────────────
        DomainInputRow(
            placeholder = "e.g. example.com",
            onAdd       = { viewModel.addManualBlockedDomain(it) },
            modifier    = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // ── Blocked domains ───────────────────────────────────────────────
        if (manualBlocked.isEmpty()) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector        = Icons.Default.Block,
                    contentDescription = null,
                    tint              = MaterialTheme.colorScheme.primary,
                    modifier          = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text  = "Add a website here to block it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(manualBlocked.toList().sorted(), key = { "blocked_$it" }) { domain ->
                    Column {
                        BlockedDomainRow(
                            domain    = domain,
                            expiresAt = pausedDomains[domain],
                            onPause   = { durationMs -> viewModel.pauseBlockedDomain(domain, durationMs) },
                            onResume  = { viewModel.resumeBlockedDomain(domain) },
                            onDelete  = { viewModel.removeManualBlockedDomain(domain) },
                        )
                        if (!viewModel.isValidManualBlock(domain)) {
                            Text(
                                text  = "Not blocked: this isn't a valid website. Enter a domain like example.com.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Blocked domain row with SplitButton ──────────────────────────────────────

/**
 * Pause duration choices exposed in the split-button dropdown.
 */
private data class PauseDuration(val label: String, val millis: Long?)

private val PAUSE_DURATIONS = listOf(
    PauseDuration("15 minutes",  15 * 60 * 1_000L),
    PauseDuration("1 hour",      60 * 60 * 1_000L),
    PauseDuration("1 day",       24 * 60 * 60 * 1_000L),
    PauseDuration("Indefinitely", null),
)

/**
 * Formats a remaining-time in millis into a compact human string.
 * e.g. "14m left", "23h left", "<1m left"
 */
private fun formatRemaining(remainingMs: Long): String {
    val totalMinutes = remainingMs / 60_000
    return when {
        totalMinutes < 1   -> "<1m left"
        totalMinutes < 60  -> "${totalMinutes}m left"
        totalMinutes < 1440 -> "${totalMinutes / 60}h left"
        else               -> "${totalMinutes / 1440}d left"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlockedDomainRow(
    domain: String,
    expiresAt: Long?,
    onPause: (durationMs: Long?) -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val isPaused = expiresAt != null
    var menuExpanded by remember { mutableStateOf(false) }

    // Live countdown: re-read current time every second while paused with a timer
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (isPaused && expiresAt != Long.MAX_VALUE) {
        LaunchedEffect(expiresAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val subtitle = when {
        !isPaused                  -> null
        expiresAt == Long.MAX_VALUE -> "paused indefinitely"
        else                       -> "paused · ${formatRemaining(expiresAt - now)}"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
    ) {
        // Domain label
        var isExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { isExpanded = !isExpanded }
        ) {
            Text(
                text      = domain,
                style     = MaterialTheme.typography.bodyMedium,
                maxLines  = if (isExpanded) Int.MAX_VALUE else 1,
                overflow  = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color     = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Pause / Resume split button
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = { if (isPaused) onResume() else onPause(PAUSE_DURATIONS.first().millis) },
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = menuExpanded,
                    onCheckedChange = { menuExpanded = it },
                ) {
                    // Standard M3 split button behaviour: the arrow flips
                    // while the menu is open.
                    val rotation by animateFloatAsState(
                        targetValue = if (menuExpanded) 180f else 0f,
                        label       = "Trailing Icon Rotation",
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = if (menuExpanded) "Collapse menu" else "Expand menu",
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .graphicsLayer { rotationZ = rotation },
                    )
                }
            },
        )

        // Dropdown menu anchored to the split button
        DropdownMenu(
            expanded         = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape            = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier         = Modifier.padding(4.dp)
        ) {
            PAUSE_DURATIONS.forEach { duration ->
                DropdownMenuItem(
                    text    = { Text(duration.label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        menuExpanded = false
                        onPause(duration.millis)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}
