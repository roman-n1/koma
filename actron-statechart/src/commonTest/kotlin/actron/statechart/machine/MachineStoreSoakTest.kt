@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [MachineStore] on [Dispatchers.Default] under a storm of dispatches from several threads:
 * loads that supersede each other, ticks handled without a transition, results racing new
 * loads. Afterwards every tick was counted once, every command the machine holds has ended,
 * every commit belongs to one input, and no failure was reported.
 *
 * ```
 * [*] --> Idle
 * Idle --Load--> Loading                onEnter: command Fetch(n)
 * Loading --Load--> Loading             the previous Fetch is cancelled with its activation
 * Loading --Loaded / store--> Idle
 * Root: onAction Tick / count
 * ```
 */
@OptIn(InternalActronApi::class)
class MachineStoreSoakTest {

    data class Ctx(val ticks: Int = 0, val loaded: Int = -1, val loads: Int = 0)

    sealed interface Act : Action {
        data class Load(val n: Int) : Act
        data class Loaded(val n: Int) : Act
        data object Tick : Act
    }

    data object Nothing : Event

    data class Fetch(val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("countLoad")),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("countLoad")),
            Transition(loading, idle, ActionMatcher.of<Act.Loaded>("Loaded"), effect = actron.statechart.EffectKey("store")),
        ),
    )

    @Test
    fun aStormOfLoadsAndTicks_leavesConsistentStateAndBookkeeping() = runTest {
        val senders = 8
        val perSender = 150
        val machine = Machine<Ctx, Act, Fetch, Nothing>(DefinitionId("soak"), DefinitionVersion("1"), chart) {
            effect("countLoad") { c, _ -> c.copy(loads = c.loads + 1) }
            effect("store") { c, a -> c.copy(loaded = (a as Act.Loaded).n) }
            onEnter(loading) { command(Fetch((action as Act.Load).n)) }
            onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
        }
        val traces = Channel<StoreTrace<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing>>(Channel.UNLIMITED)
        val handled = Channel<Throwable>(Channel.UNLIMITED)
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = CommandHandler<Fetch, Act> { command, results ->
            delay(Random.nextLong(0, 3).milliseconds)
            results.result(Act.Loaded(command.command.n))
        }
        val store = MachineStore(machine, Ctx(), handler, executionScope, coroutineContext = Dispatchers.Default) {
            exceptionHandler(ExceptionHandler { handled.trySend(it) })
            probe(StoreProbe { traces.trySend(it) })
        }
        val ticks = senders * perSender / 2

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            store.dispatch(if (index % 2 == 0) Act.Tick else Act.Load(sender * perSender + index))
                        }
                    }
                }
            }
            withTimeout(20_000) {
                // Quiescent: every tick counted, no command left, the last load answered.
                store.state.first { it.context.ticks == ticks && it.commands.isEmpty() && it.isActive(idle) }
            }
        }
        val final = store.currentState
        store.close()
        executionScope.cancel()

        assertEquals(ticks, final.context.ticks)
        assertEquals(senders * perSender / 2, final.context.loads, "every Load was decided once")
        assertTrue(final.loaded() >= 0, "the last load answered")
        val commits = generateSequence { traces.tryReceive().getOrNull() }.count { it is StoreTrace.StateCommitted<*> }
        assertEquals(final.revision, commits.toLong(), "one commit per handled decision")
        assertEquals(null, handled.tryReceive().getOrNull(), "nothing was reported")
    }

    private fun MachineSnapshot<Ctx>.loaded(): Int = context.loaded
}
