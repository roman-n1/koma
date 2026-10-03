@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package koma.example.timetravel

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
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
        val parent = Files.createTempDirectory("koma-demo-ui-")
        try {
            runComposeUiTest {
                setContent { MaterialTheme { DemoScreen(parent) } }
                onNodeWithTag("demo-record").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Live price handler ran 1 time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("group-replay-verdict").assertTextContains("no divergence", substring = true)
                onNodeWithTag("demo-original").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Original reducer: Verify matches", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("demo-changed").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Changed reducer: forward stops", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("group-replay-why").assertTextContains("cart-1", substring = true)
                onNodeWithTag("group-replay-why").assertTextContains("context", substring = true)
                onNodeWithTag("time-travel-branch").performClick()
                onNodeWithText("Price 100").performClick()
                onNodeWithTag("branch-cart-1").assertTextContains("total=80", substring = true)
                onNodeWithTag("branch-summary-1").assertTextContains("total=80", substring = true)
                onNodeWithTag("time-travel-return").performClick()
                onNodeWithTag("group-replay-why").assertTextContains("context", substring = true)
                onNodeWithTag("demo-original").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Original reducer: Verify matches", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("group-replay-verdict").assertTextContains("no divergence", substring = true)
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
