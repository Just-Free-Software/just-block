package fyi.justfreesoftware.justblock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fyi.justfreesoftware.justblock.data.BlocklistState
import fyi.justfreesoftware.justblock.ui.theme.HeaderTextStyle
import fyi.justfreesoftware.justblock.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenBlockingRules: () -> Unit,
    onOpenWhitelist: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVpnEnabled      by viewModel.isVpnEnabled.collectAsState()
    val isBlockingEnabled by viewModel.isBlockingEnabled.collectAsState()
    val isStartOnBoot     by viewModel.isStartOnBoot.collectAsState()
    val blocklistState    by viewModel.blocklistState.collectAsState()
    val pendingRestart    by viewModel.pendingRestartReason.collectAsState()
    val upstream          by viewModel.upstreamConfig.collectAsState()
    val customUrls        by viewModel.customSourceUrls.collectAsState()
    val manualBlocked     by viewModel.manualBlockedDomains.collectAsState()
    val whitelisted       by viewModel.whitelistedDomains.collectAsState()
    val snackbarHost      = remember { SnackbarHostState() }
    var showInfoDialog    by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showInfoDialog = false }) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = """
                            The VPN automatically starts with the app and runs in the background. To stop the app completely, press the Stop VPN & Close button.
                            
                            This app will not work properly with private DNS enabled. If you have not touched this Android setting before, you don't need to worry about this.
                            
                            If the app stops working after long periods it could be due to battery optimization. Consider changing this under Settings > Apps > Just Block to Unrestricted.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showInfoDialog = false }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(pendingRestart) {
        if (pendingRestart != null) {
            val result = snackbarHost.showSnackbar(
                message     = "Restart VPN to apply changes",
                actionLabel = "Restart",
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restartVpn()
            else viewModel.dismissRestartBanner()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── App Header ────────────────────────────────────────────────────
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = "Just Block",
                        style = HeaderTextStyle
                    )
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.IconButton(
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
            }

            // ── Status card ───────────────────────────────────────────────────
            item {
                VpnStatusCard(
                    isVpnEnabled        = isVpnEnabled,
                    isBlockingEnabled   = isBlockingEnabled,
                    isStartOnBoot       = isStartOnBoot,
                    blocklistState      = blocklistState,
                    hasCustomUrls       = customUrls.isNotEmpty(),
                    onBlockingToggle    = { viewModel.setBlockingEnabled(it) },
                    onStartOnBootToggle = { viewModel.setStartOnBoot(it) },
                    onCloseApp          = onCloseApp,
                )
            }

            // ── Blocking Rules ────────────────────────────────────────────────
            item {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier  = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier             = Modifier.fillMaxWidth(),
                        ) {
                            BlockingRulesBox(
                                label = if (manualBlocked.size == 1) "blocked site"
                                        else "blocked sites",
                                count = manualBlocked.size,
                                modifier = Modifier.weight(1f),
                                onClick = onOpenBlockingRules,
                            )
                            BlockingRulesBox(
                                label = if (whitelisted.size == 1) "whitelisted site"
                                        else "whitelisted sites",
                                count = whitelisted.size,
                                modifier = Modifier.weight(1f),
                                onClick = onOpenWhitelist,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenBlockingRules,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add a rule")
                        }
                    }
                }
            }

            // ── Theme Selection ───────────────────────────────────────────────
            item {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier  = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ThemeMode.values().forEachIndexed { index, mode ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.values().size),
                                    onClick = { onThemeModeChange(mode) },
                                    selected = mode == themeMode,
                                    icon = {} // text only
                                ) {
                                    Text(mode.name)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── Blocking rules stat box ───────────────────────────────────────────────────

@Composable
private fun BlockingRulesBox(
    label    : String,
    count    : Int,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        shape  = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color  = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier           = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
        ) {
            Text(
                text  = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── VPN status card ───────────────────────────────────────────────────────────

@Composable
private fun VpnStatusCard(
    isVpnEnabled        : Boolean,
    isBlockingEnabled   : Boolean,
    isStartOnBoot       : Boolean,
    blocklistState      : BlocklistState,
    hasCustomUrls       : Boolean,
    onBlockingToggle    : (Boolean) -> Unit,
    onStartOnBootToggle : (Boolean) -> Unit,
    onCloseApp          : () -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {

            // ── Enable blocking row ──────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = "Enable blocking",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked         = isBlockingEnabled,
                    onCheckedChange = onBlockingToggle,
                )
            }

            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(2.dp))

            // ── Start on boot row ─────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = "Start on boot",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked         = isStartOnBoot,
                    onCheckedChange = onStartOnBootToggle,
                )
            }
            
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

            Spacer(Modifier.height(8.dp))

            // ── VPN status + Stop button ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Status:",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    val dotColor = if (isVpnEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(color = dotColor)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isVpnEnabled) "ON" else "OFF",
                        style = MaterialTheme.typography.bodyLarge,
                        color = dotColor,
                    )
                    
                    val blocklistText = if (hasCustomUrls) {
                        when (blocklistState) {
                            is BlocklistState.Loading -> " (updating blocklists…)"
                            is BlocklistState.Error   -> " (⚠ error updating blocklists)"
                            else                      -> ""
                        }
                    } else {
                        ""
                    }
                    if (blocklistText.isNotEmpty()) {
                        Text(
                            text = blocklistText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onCloseApp,
                ) {
                    Text("Stop & Close")
                }
            }

            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    text = "WEBSITE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { uriHandler.openUri("https://justfreesoftware.fyi/just-block/info/") }
                        .padding(4.dp),
                )
                Text(
                    text = "PRIVACY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { uriHandler.openUri("https://justfreesoftware.fyi/just-block/privacy-policy/") }
                        .padding(4.dp),
                )
            }
        }
    }
}
