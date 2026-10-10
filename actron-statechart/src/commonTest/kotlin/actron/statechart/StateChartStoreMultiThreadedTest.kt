package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs a [StateChartStore] on [Dispatchers.Default]: work in one region updates the context while
 * actions step the other region from several threads.
 *
 * ```
 * [*] --> Main
 * state Main {                         parallel
 *     state Sync { [*] --> Syncing }   activity: updateContext 500 times
 *     --
 *     state Ui {
 *         [*] --> Closed
 *         Closed --Toggle / count--> Opened
 *         Opened --Toggle / count--> Closed
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalActronApi::class)
class StateChartStoreMultiThreadedTest {

    sealed interface UiAction : Action {
        data object Toggle : UiAction
    }

    sealed interface UiEvent : Event

    data class Counts(val synced: Int = 0, val toggles: Int = 0)

    private val main = StateId("Main")
    private val sync = StateId("Sync")
    private val syncing = StateId("Syncing")
    private val ui = StateId("Ui")
    private val closed = StateId("Closed")
    private val opened = StateId("Opened")
    private val toggle = ActionMatcher.of<UiAction.Toggle>("Toggle")

    private val chart = StateChartDefinition(
        initial = main,
        states = listOf(
            ParallelState(main),
            CompoundState(sync, initial = syncing, parent = main),
            AtomicState(syncing, sync),
            CompoundState(ui, initial = closed, parent = main),
            AtomicState(closed, ui),
            AtomicState(opened, ui),
        ),
        transitions = listOf(
            Transition(closed, opened, toggle, effect = actron.statechart.EffectKey("count")),
            Transition(opened, closed, toggle, effect = actron.statechart.EffectKey("count")),
        ),
    )

    @Test
    fun workAndStepsFromManyThreads_neverLoseAContextUpdate() = runTest {
        val updates = 500
        val senders = 4
        val perSender = 125
        val store = StateChartStore<Counts, UiAction, UiEvent>(chart, Counts(), Dispatchers.Default) {
            effect("count") { context, _ -> context.copy(toggles = context.toggles + 1) }
            activity(syncing) {
                repeat(updates) { updateContext { it.copy(synced = it.synced + 1) } }
            }
        }
        store.start()

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) {
                    launch { repeat(perSender) { store.dispatch(UiAction.Toggle) } }
                }
            }
            val expected = Counts(synced = updates, toggles = senders * perSender)
            withTimeout(10_000) {
                store.state.first { it.context == expected }
            }
        }

        // An even number of toggles leaves the Ui region where it started.
        assertEquals(listOf(syncing, closed), store.currentState.activeLeaves(chart))
        store.close()
    }
}
