package fyi.justfreesoftware.justblock.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import fyi.justfreesoftware.justblock.data.BlocklistFetcher
import fyi.justfreesoftware.justblock.data.SourceIssue
import fyi.justfreesoftware.justblock.data.SourceProblem

@Composable
fun BlocklistsTab(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
) {
    val customUrls by viewModel.customSourceUrls.collectAsState()
    val sourceIssues by viewModel.sourceIssues.collectAsState()
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
            placeholder = "https://example.com/hosts.txt",
            onAdd       = { viewModel.addCustomSourceUrl(it); true },
            modifier    = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // ── Custom sources ────────────────────────────────────────────────
        if (customUrls.isEmpty()) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text  = "Link to a blocklist to block groups of websites.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(customUrls.toList(), key = { "url_$it" }) { url ->
                    Column {
                        DomainRow(
                            label    = url,
                            onDelete = { viewModel.removeCustomSourceUrl(url) },
                        )
                        sourceIssues[url]?.let { issue ->
                            Text(
                                text  = issue.message(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SourceIssue.message(): String {
    val prefix = if (usingSavedCopy) "Couldn't update, using the last downloaded copy: " else "Not loaded: "
    return prefix + problem.reason()
}

private fun SourceProblem.reason(): String = when (this) {
    SourceProblem.INVALID_LINK ->
        "this isn't a valid https:// link."
    SourceProblem.SITE_NOT_FOUND ->
        "this site doesn't exist, or you're offline."
    SourceProblem.PAGE_NOT_FOUND ->
        "nothing exists at this link."
    SourceProblem.SERVER_ERROR ->
        "the site returned an error. Try again later."
    SourceProblem.DOWNLOAD_FAILED ->
        "the download failed. Check your connection."
    SourceProblem.NOT_A_BLOCKLIST ->
        "this link isn't a blocklist. Link to the raw text file."
    SourceProblem.TOO_LARGE ->
        "this blocklist is too large."
    SourceProblem.OVER_TOTAL_LIMIT ->
        "your blocklists together are over the " +
            "%,d domain limit.".format(BlocklistFetcher.MAX_DOMAINS)
}
