package fyi.justfreesoftware.justblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BlocklistsTab(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val customUrls by viewModel.customSourceUrls.collectAsState()

    LazyColumn(
        modifier            = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {

        // ── Custom sources ────────────────────────────────────────────────
        item {
            DomainInputRow(
                placeholder = "https://example.com/blocklist.txt",
                onAdd       = { viewModel.addCustomSourceUrl(it) },
                modifier    = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }

        if (customUrls.isEmpty()) {
            item {
                Text(
                    text  = "No custom sources added yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(customUrls.toList(), key = { "url_$it" }) { url ->
            DomainRow(
                label    = url,
                onDelete = { viewModel.removeCustomSourceUrl(url) },
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
