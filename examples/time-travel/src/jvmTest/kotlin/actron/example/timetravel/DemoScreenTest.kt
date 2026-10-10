@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.example.timetravel

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
import kotlin.test.Test

class DemoScreenTest {
    @Test
    fun theButtonsRecordReopenCompareAndExperiment_onRealFiles() {
        val parent = Files.createTempDirectory("actron-demo-ui-")
        try {
            runComposeUiTest {
                setContent { MaterialTheme { DemoScreen(parent) } }
                onNodeWithTag("demo-record").performClick()
                awaitStatus("Live price handler ran 1 time")
                onNodeWithTag("group-replay-verdict").assertTextContains("no divergence", substring = true)
                onNodeWithTag("demo-original").performClick()
                awaitStatus("Original reducer: Verify matches")
                onNodeWithTag("demo-changed").performClick()
                awaitStatus("Changed reducer: forward stops")
                onNodeWithTag("group-replay-why").assertTextContains("cart-1", substring = true)
                onNodeWithTag("group-replay-why").assertTextContains("context", substring = true)
                onNodeWithTag("time-travel-branch").performClick()
                onNodeWithText("Price 100").performClick()
                onNodeWithTag("branch-cart-1").assertTextContains("total=80", substring = true)
                onNodeWithTag("branch-summary-1").assertTextContains("total=80", substring = true)
                onNodeWithTag("time-travel-return").performClick()
                onNodeWithTag("group-replay-why").assertTextContains("context", substring = true)
                onNodeWithTag("demo-original").performClick()
                awaitStatus("Original reducer: Verify matches")
                onNodeWithTag("group-replay-verdict").assertTextContains("no divergence", substring = true)
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.awaitStatus(expected: String) {
        try {
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val status = onNodeWithTag("demo-status").fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString { it.text }
            throw AssertionError("Expected '$expected'; actual demo status: $status", timeout)
        }
    }

}
