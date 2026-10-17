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
