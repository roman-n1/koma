@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.timetravel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import actron.timetravel.GroupMismatch
import actron.timetravel.compose.GroupTimeTravelControls
import actron.timetravel.compose.InspectorScreen
import actron.timetravel.compose.InspectorState
import actron.timetravel.inspect.Inspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** `--check [parent-directory]` exercises the same disk/replay/experiment path without a window. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--check") {
        runBlocking {
            val calls = AtomicInteger()
            val recorded = CheckoutDemo.record(Path.of(args.getOrNull(1) ?: "build/time-travel-demo"), calls)
            val loaded = CheckoutDemo.load(recorded.directory)
            check(loaded.replay(fixed = false).session.verify().isEmpty())
            val controls = loaded.controls(fixed = true)
            val mismatch = controls.replay.session.verify().filterIsInstance<GroupMismatch.Replay>().first()
            controls.replay.seek(mismatch.position)
            controls.replay.stepForward()
            check(controls.replay.divergence != null)
            controls.branchHere()
            val branch = checkNotNull(controls.branch)
            branch.answer(CheckoutDemo.answers.first(), branch.awaiting(CheckoutDemo.cartId).single().id)
            check(branch.snapshot(CheckoutDemo.cartId).context == CartContext(20, 100, 80))
            check(branch.snapshot(CheckoutDemo.summaryId).context == SummaryContext(80))
            controls.returnToReplay()
            check(controls.replay.position == mismatch.position && controls.replay.divergence != null)
            check(calls.get() == 1)
            println("Saved and reopened ${recorded.directory.toAbsolutePath()}")
            println("Original replay matches; changed reducer diverges at group input ${mismatch.position}.")
            println("Experiment: cart 80, summary 80. Replay unchanged. Live command calls: ${calls.get()}.")
        }
        return
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Actron — Time Travel example", state = rememberWindowState(size = DpSize(1400.dp, 900.dp))) {
            MaterialTheme { DemoScreen() }
        }
    }
}

@Composable
internal fun DemoScreen(initialDirectory: Path = Path.of("build/time-travel-demo")) {
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(initialDirectory.toAbsolutePath().toString()) }
    var loaded by remember { mutableStateOf<CheckoutDemo.Loaded?>(null) }
    var controls by remember { mutableStateOf<GroupTimeTravelControls?>(null) }
    var inspector by remember { mutableStateOf<InspectorState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Record the synthetic checkout, then open the same files with the changed reducer.") }

    fun show(run: CheckoutDemo.Loaded, fixed: Boolean) {
        loaded = run
        inspector = InspectorState(Inspector(run.journal.events, run.recording.members))
        controls = run.controls(fixed)
        controls!!.replay.verify()
        if (fixed) {
            val mismatch = controls!!.replay.verification.orEmpty().filterIsInstance<GroupMismatch.Replay>().firstOrNull()
            if (mismatch != null) {
                controls!!.replay.seek(mismatch.position)
                controls!!.replay.stepForward()
            }
        }
        message = if (fixed) "Changed reducer: forward stops before the wrong calculation. Branch here, then answer Price 100: cart and summary become 80. Return keeps the original failure." else "Original reducer: Verify matches the saved run. The live bug charged 100 instead of 80. Seek or step through both machines."
    }

    fun open(fixed: Boolean) {
        busy = true
        scope.launch {
            try {
                val run = withContext(Dispatchers.IO) { CheckoutDemo.load(Path.of(directory)) }
                show(run, fixed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message = e.message ?: "Could not open recording"
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Record → save → reopen → reproduce → experiment", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(directory, { directory = it }, label = { Text("Recording directory (Record creates a fresh child folder)") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("demo-directory"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, modifier = Modifier.testTag("demo-record"), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val recorded = CheckoutDemo.record(Path.of(directory))
                            directory = recorded.directory.toAbsolutePath().toString()
                            val run = withContext(Dispatchers.IO) { CheckoutDemo.load(recorded.directory) }
                            show(run, fixed = false)
                            message += " Live price handler ran ${recorded.commandCalls} time; all live Stores are now closed."
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { message = e.message ?: "Could not record scenario"
                        } finally { busy = false }
                    }
                }) { Text("1. Record checkout") }
                Button(enabled = !busy, modifier = Modifier.testTag("demo-original"), onClick = { open(fixed = false) }) { Text("2. Open original") }
                Button(enabled = !busy, modifier = Modifier.testTag("demo-changed"), onClick = { open(fixed = true) }) { Text("3. Compare changed reducer") }
            }
            Text(if (busy) "Working with recording files…" else message, modifier = Modifier.testTag("demo-status"))
            loaded?.let { Text("${it.recording.length} group inputs saved; ${it.journal.records.size} journal records. All amounts are synthetic.") }
        }
        val state = inspector
        val travel = controls
        if (state != null && travel != null) InspectorScreen(state, timeTravel = travel, modifier = Modifier.weight(1f))
    }
}
