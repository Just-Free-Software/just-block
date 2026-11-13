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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fyi.justfreesoftware.justblock.R
import fyi.justfreesoftware.justblock.data.BlocklistFetcher
import fyi.justfreesoftware.justblock.data.SourceIssue
import fyi.justfreesoftware.justblock.data.SourceProblem
import java.text.NumberFormat

@Composable
fun BlocklistsTab(viewModel: VpnViewModel, modifier: Modifier = Modifier) {
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
            onAdd = { viewModel.addCustomSourceUrl(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        HorizontalDivider()

        // ── Custom sources ────────────────────────────────────────────────
        if (customUrls.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.blocklists_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(customUrls.toList(), key = { "url_$it" }) { url ->
                    Column {
                        DomainRow(
                            label = url,
                            onDelete = { viewModel.removeCustomSourceUrl(url) }
                        )
                        sourceIssues[url]?.let { issue ->
                            Text(
                                text = issue.message(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceIssue.message(): String = stringResource(
    if (usingSavedCopy) R.string.source_issue_using_saved_copy else R.string.source_issue_not_loaded,
    problem.reason()
)

@Composable
private fun SourceProblem.reason(): String = when (this) {
    SourceProblem.INVALID_LINK -> stringResource(R.string.source_problem_invalid_link)
    SourceProblem.SITE_NOT_FOUND -> stringResource(R.string.source_problem_site_not_found)
    SourceProblem.PAGE_NOT_FOUND -> stringResource(R.string.source_problem_page_not_found)
    SourceProblem.SERVER_ERROR -> stringResource(R.string.source_problem_server_error)
    SourceProblem.DOWNLOAD_FAILED -> stringResource(R.string.source_problem_download_failed)
    SourceProblem.NOT_A_BLOCKLIST -> stringResource(R.string.source_problem_not_a_blocklist)
    SourceProblem.TOO_LARGE -> stringResource(R.string.source_problem_too_large)
    SourceProblem.OVER_TOTAL_LIMIT -> stringResource(
        R.string.source_problem_over_total_limit,
        NumberFormat.getIntegerInstance().format(BlocklistFetcher.MAX_DOMAINS)
    )
}
