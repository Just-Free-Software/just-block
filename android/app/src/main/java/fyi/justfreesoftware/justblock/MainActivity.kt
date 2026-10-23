package fyi.justfreesoftware.justblock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import fyi.justfreesoftware.justblock.ui.WhitelistScreen
import fyi.justfreesoftware.justblock.ui.HomeScreen
import fyi.justfreesoftware.justblock.ui.BlockingScreen
import fyi.justfreesoftware.justblock.ui.VpnViewModel
import fyi.justfreesoftware.justblock.ui.theme.JustBlockTheme
import fyi.justfreesoftware.justblock.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_STOP_AND_CLOSE = "fyi.justfreesoftware.justblock.STOP_AND_CLOSE"
    }

    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.setVpnEnabled(true)
        }
    }

    /**
     * Requests POST_NOTIFICATIONS at runtime (required on Android 13+ / API 33+).
     * After the user responds (grant or deny), we proceed to start the VPN.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whether granted or denied, continue starting the VPN.
        // The foreground service will still run; the notification simply won't
        // appear if the user chose "Don't allow".
        requestVpnPermissionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_STOP_AND_CLOSE) {
            stopVpnAndClose()
            return
        }

        // The permission check is now deferred until after the welcome dialog is resolved,
        // or immediately if the user has already seen it.

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            val themeMode by viewModel.themeMode.collectAsState()
            val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsState()

            var showWelcomeDialog by remember { mutableStateOf(false) }
            var permissionsRequested by remember { mutableStateOf(false) }

            LaunchedEffect(hasSeenWelcome) {
                if (hasSeenWelcome == false && !permissionsRequested) {
                    showWelcomeDialog = true
                } else if (hasSeenWelcome == true && !permissionsRequested) {
                    permissionsRequested = true
                    checkAndRequestPermissions()
                }
            }

            if (showWelcomeDialog) {
                var dontShowAgain by remember { mutableStateOf(false) }
                JustBlockTheme(themeMode = ThemeMode.Dark) {
                    AlertDialog(
                    onDismissRequest = {
                        // User dismissed by clicking outside or back button
                        showWelcomeDialog = false
                        if (dontShowAgain) {
                            viewModel.setHasSeenWelcome(true)
                        }
                        permissionsRequested = true
                        checkAndRequestPermissions()
                    },
                    title = {
                        Text(
                            text = "Welcome!",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column {
                            Text(
                                "This app requires two permissions:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                "Notification Permission",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "This notifies you when the blocker is active and more importantly, ensures that you phone doesn't kill the app in the background.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                "VPN Permission",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "We use a local VPN to filter your network traffic and block selected domains.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Checkbox(
                                    checked = dontShowAgain,
                                    onCheckedChange = { dontShowAgain = it }
                                )
                                Text("Don't show again", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showWelcomeDialog = false
                            if (dontShowAgain) {
                                viewModel.setHasSeenWelcome(true)
                            }
                            permissionsRequested = true
                            checkAndRequestPermissions()
                        }) {
                            Text("OK")
                        }
                    }
                )
                }
            }

            JustBlockTheme(themeMode = themeMode) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick  = { selectedTab = 0 },
                                icon     = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                label    = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick  = { selectedTab = 1 },
                                icon     = { Icon(Icons.Default.Block, contentDescription = "Blocking") },
                                label    = { Text("Blocking") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick  = { selectedTab = 2 },
                                icon     = { Icon(Icons.Default.VerifiedUser, contentDescription = "") },
                                label    = { Text("Whitelist") },
                            )
                        }
                    }
                ) { padding ->
                    when (selectedTab) {
                        0 -> HomeScreen(
                            viewModel  = viewModel,
                            themeMode = themeMode,
                            onThemeModeChange = { viewModel.setThemeMode(it) },
                            onAddRule = { selectedTab = 1 },
                            onCloseApp = ::stopVpnAndClose,
                            modifier   = Modifier.padding(padding),
                        )
                        1 -> BlockingScreen(
                            viewModel = viewModel,
                            modifier  = Modifier.padding(padding),
                        )
                        2 -> WhitelistScreen(
                            viewModel = viewModel,
                            modifier  = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_STOP_AND_CLOSE) {
            stopVpnAndClose()
        }
    }

    fun stopVpnAndClose() {
        viewModel.setVpnEnabled(false)
        finishAndRemoveTask()
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermissionIfNeeded()
        }
    }

    fun requestVpnPermissionIfNeeded() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            viewModel.setVpnEnabled(true)
        }
    }
}