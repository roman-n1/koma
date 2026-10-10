package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.PendingActionPolicy
import actron.core.State
import actron.core.Store
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [StateChartConformance] with compound states: `stateIdOf` returns the active leaf, transitions
 * are looked up on the leaf and its ancestors, and a transition into a compound state matches a
 * change to any leaf it enters.
 *
 * ```
 * [*] --> Online
 * state Online {
 *     [*] --> Idle
 *     Idle --Open--> Chat
 *     state Chat {
 *         [*] --> Reading
 *         Reading --Type--> Typing
 *         Typing --Send--> Reading
 *     }
 *     Chat --Close--> Idle          taken from Reading or Typing
 *     Chat --Refresh--> Chat        Typing -> Reading, or Reading -> Reading (no change: self-loop)
 * }
 * Online --Disconnect--> Offline   taken from any leaf of Online
 * Offline --Reconnect--> Online    ends in Idle, Online's initial leaf
 * ```
 * The Store below follows it, except that `Jump` moves Offline to Typing and `Discard` moves
 * Typing to Idle, which the chart does not declare.
 */
@OptIn(ExperimentalActronApi::class)
class StateChartHierarchyConformanceTest {

    sealed interface ChatState : State {
        data object Idle : ChatState
        data object Reading : ChatState
        data class Typing(val draft: String) : ChatState
        data object Offline : ChatState
    }

    sealed interface ChatAction : Action {
        data object Open : ChatAction
        data object Close : ChatAction
        data object Type : ChatAction
        data object Send : ChatAction
        data object Refresh : ChatAction
        data object Disconnect : ChatAction
        data object Reconnect : ChatAction
        data object Jump : ChatAction
        data object Discard : ChatAction
        data object Bump : ChatAction
    }

    sealed interface ChatEvent : Event

    private val online = StateId("Online")
    private val idle = StateId("Idle")
    private val chat = StateId("Chat")
    private val reading = StateId("Reading")
    private val typing = StateId("Typing")
    private val offline = StateId("Offline")

    private val open = Transition(idle, chat, ActionMatcher.of<ChatAction.Open>("Open"))
    private val type = Transition(reading, typing, ActionMatcher.of<ChatAction.Type>("Type"))
    private val send = Transition(typing, reading, ActionMatcher.of<ChatAction.Send>("Send"))
    private val close = Transition(chat, idle, ActionMatcher.of<ChatAction.Close>("Close"))
    private val refresh = Transition(chat, chat, ActionMatcher.of<ChatAction.Refresh>("Refresh"))
    private val disconnect = Transition(online, offline, ActionMatcher.of<ChatAction.Disconnect>("Disconnect"))
    private val reconnect = Transition(offline, online, ActionMatcher.of<ChatAction.Reconnect>("Reconnect"))

    private val chart = StateChartDefinition(
        initial = online,
        states = listOf(
            CompoundState(online, initial = idle),
            AtomicState(idle, parent = online),
            CompoundState(chat, initial = reading, parent = online),
            AtomicState(reading, parent = chat),
            AtomicState(typing, parent = chat),
            AtomicState(offline),
        ),
        transitions = listOf(open, type, send, close, refresh, disconnect, reconnect),
    )

    private val stateIdOf: (ChatState) -> StateId = { state ->
        when (state) {
            ChatState.Idle -> idle
            ChatState.Reading -> reading
            is ChatState.Typing -> typing
            ChatState.Offline -> offline
        }
    }

    private fun createStore(): Store<ChatState, ChatAction, ChatEvent> = Store(ChatState.Idle) {
        coroutineContext(Dispatchers.Unconfined)
        state<ChatState> {
            action<ChatAction.Disconnect> { if (state != ChatState.Offline) nextState { ChatState.Offline } }
        }
        state<ChatState.Idle> {
            action<ChatAction.Open> { nextState { ChatState.Reading } }
        }
        state<ChatState.Reading> {
            action<ChatAction.Type> { nextState { ChatState.Typing("") } }
            action<ChatAction.Close> { nextState { ChatState.Idle } }
        }
        state<ChatState.Typing> {
            action<ChatAction.Send> { nextState { ChatState.Reading } }
            action<ChatAction.Close> { nextState { ChatState.Idle } }
            action<ChatAction.Refresh> { nextState { ChatState.Reading } }
            action<ChatAction.Discard> { nextState { ChatState.Idle } }
            action<ChatAction.Bump> { nextState { state.copy(draft = state.draft + "x") } }
        }
        state<ChatState.Offline> {
            action<ChatAction.Reconnect> { nextState { ChatState.Idle } }
            action<ChatAction.Jump> { nextState { ChatState.Typing("") } }
        }
    }

    private fun conformingStore() = StateChartConformance<ChatState, ChatAction, ChatEvent>(chart, stateIdOf).let { conformance ->
        createStore().patch { plugin(conformance) } to conformance
    }

    @Test
    fun transitionsFromAncestorsAndIntoCompoundStatesAreRecognised() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(ChatAction.Open) // Idle -> Reading: Open enters Chat, then Reading
        store.dispatchAndAwait(ChatAction.Type)
        store.dispatchAndAwait(ChatAction.Bump) // data change inside Typing
        store.dispatchAndAwait(ChatAction.Refresh) // Typing -> Reading through Chat's self-loop
        store.dispatchAndAwait(ChatAction.Disconnect) // Reading -> Offline through Online
        store.dispatchAndAwait(ChatAction.Reconnect) // Offline -> Idle: Online's initial leaf

        assertEquals(ChatState.Idle, store.currentState)
        assertEquals(emptyList(), conformance.violations)
        assertEquals(listOf(open, type, refresh, disconnect, reconnect), conformance.coveredTransitions.toList())
        assertEquals(listOf(send, close), conformance.uncoveredTransitions)
    }

    @Test
    fun selfLoopOfAnAncestorThatEndsInTheSameLeafIsCoveredByItsAction() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(ChatAction.Open)
        store.dispatchAndAwait(ChatAction.Refresh) // Reading stays Reading: Chat exits and re-enters
        assertEquals(listOf(open, refresh), conformance.coveredTransitions.toList())

        store.dispatchAndAwait(ChatAction.Close)
        assertEquals(listOf(open, refresh, close), conformance.coveredTransitions.toList())
        assertEquals(emptyList(), conformance.violations)
    }

    @Test
    fun changeToALeafNoTransitionEntersIsUndeclared() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(ChatAction.Disconnect)
        store.dispatchAndAwait(ChatAction.Jump) // Offline -> Typing: Reconnect only enters Online, Idle

        assertEquals(listOf(ConformanceViolation.UndeclaredTransition(offline, typing, ChatAction.Jump)), conformance.violations)
        assertEquals(listOf(disconnect), conformance.coveredTransitions.toList())
    }

    @Test
    fun declaredChangeWithTheWrongActionIsAnUnexpectedTrigger() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(ChatAction.Open)
        store.dispatchAndAwait(ChatAction.Type)
        store.dispatchAndAwait(ChatAction.Discard) // Typing -> Idle is declared, through Chat, for Close only

        assertEquals(listOf(ConformanceViolation.UnexpectedTrigger(typing, idle, ChatAction.Discard)), conformance.violations)
        assertEquals(listOf(open, type), conformance.coveredTransitions.toList())
    }

    // region random charts

    private sealed interface NoEvent : Event

    private val guards: Map<String, (RandomState, Action) -> Boolean> = mapOf(
        "even" to { _, a -> a is RandomAction.Go && a.n % 2 == 0 },
        "positive" to { _, a -> a is RandomAction.Go && a.n > 0 },
        "never" to { _, _ -> false },
        "always" to { _, _ -> true },
    )

    private fun guardHolds(label: String, action: RandomAction) = guards.getValue(label)(RandomState(StateId("any")), action)

    /** A Store that follows [chart] with the leaf-only [StateChartRuntime.step], except at [wrongAt]. */
    private fun storeFor(chart: StateChartDefinition, start: StateId, wrongAt: Int?, wrongLeaf: StateId?, scope: TestScope) =
        StateChartRuntime(chart, { s: RandomState -> s.id }, guards).let { runtime ->
            var index = 0
            Store<RandomState, RandomAction, NoEvent>(RandomState(start)) {
                coroutineContext(StandardTestDispatcher(scope.testScheduler))
                pendingActionPolicy(PendingActionPolicy.Keep)
                state<RandomState> {
                    action<RandomAction> {
                        val leaf = if (index++ == wrongAt) {
                            wrongLeaf!!
                        } else {
                            when (val result = runtime.step(state, action)) {
                                is StepResult.Transitioned -> result.entered.last()
                                StepResult.Ignored -> state.id
                            }
                        }
                        if (leaf != state.id) nextState { RandomState(leaf) }
                    }
                }
            }
        }

    private data class Change(val from: StateId, val to: StateId, val action: RandomAction)

    /** The plugin's documented report for [changes], computed with [HierarchyReference]. */
    private fun expectedReport(reference: HierarchyReference, changes: List<Change>): Pair<List<ConformanceViolation>, List<Transition>> {
        val violations = mutableListOf<ConformanceViolation>()
        val covered = mutableListOf<Transition>()
        for ((from, to, action) in changes) {
            if (from == to) {
                val first = reference.priority(from).firstOrNull { HierarchyReference.matches(it.on, action) }
                if (first != null && reference.leaf(reference.fire(reference.configurationOf(from), first).after) == from && first !in covered) covered += first
                continue
            }
            if (reference.chart.states.none { it.id == to }) violations += ConformanceViolation.UndeclaredState(to)
            val candidates = reference.priority(from).filter { to in reference.fire(reference.configurationOf(from), it).entered }
            val taken = candidates.firstOrNull { HierarchyReference.matches(it.on, action) }
            when {
                taken != null -> if (taken !in covered) covered += taken
                candidates.isEmpty() -> violations += ConformanceViolation.UndeclaredTransition(from, to, action)
                else -> violations += ConformanceViolation.UnexpectedTrigger(from, to, action)
            }
        }
        return violations to covered
    }

    /** Replays [actions] with the reference, moving to [wrongLeaf] instead at dispatch [wrongAt]. */
    private fun referenceWalk(reference: HierarchyReference, start: StateId, actions: List<RandomAction>, wrongAt: Int? = null, wrongLeaf: StateId? = null): List<Change> {
        var leaf = start
        return actions.mapIndexed { index, action ->
            val next = if (index == wrongAt) {
                wrongLeaf!!
            } else {
                reference.step(reference.configurationOf(leaf), action, ::guardHolds)?.let { reference.leaf(it.after) } ?: leaf
            }
            Change(leaf, next, action).also { leaf = next }
        }
    }

    @Test
    fun storesFollowingRandomHierarchicalChartsConformAndOneWrongLeafIsReported() = runTest {
        var moves = 0
        var ancestorMoves = 0
        var mutants = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.hierarchicalChart(random)
            val reference = HierarchyReference(chart)
            val start = reference.leaf(reference.initialConfiguration())
            // Mostly actions that change the leaf, so walks go deep.
            var at = start
            val actions = List(random.nextInt(0, 41)) {
                fun nextLeaf(action: RandomAction) = reference.step(reference.configurationOf(at), action, ::guardHolds)?.let { reference.leaf(it.after) } ?: at
                val moving = RandomCharts.actions.filter { nextLeaf(it) != at }
                val action = if (moving.isNotEmpty() && random.nextInt(4) != 0) moving.random(random) else RandomCharts.actions.random(random)
                at = nextLeaf(action)
                action
            }
            val walk = referenceWalk(reference, start, actions)
            for (change in walk) {
                val fired = reference.step(reference.configurationOf(change.from), change.action, ::guardHolds)
                if (fired != null && fired.transition.source != change.from) ancestorMoves++
            }

            // One wrong leaf, one that no transition from the current leaf enters.
            val wrongAt = if (actions.isEmpty()) null else random.nextInt(actions.size)
            val wrongFrom = wrongAt?.let { walk[it].from }
            val wrongLeaf = wrongFrom?.let { from ->
                chart.states.filterIsInstance<AtomicState>().map { it.id }
                    .filter { l -> l != from && reference.priority(from).none { l in reference.fire(reference.configurationOf(from), it).entered } }
                    .randomOrNull(random)
            }

            for (mutated in listOf(false, true)) {
                if (mutated && wrongLeaf == null) continue
                val conformance = StateChartConformance<RandomState, RandomAction, NoEvent>(chart) { it.id }
                val store = storeFor(chart, start, wrongAt.takeIf { mutated }, wrongLeaf, this).patch { plugin(conformance) }
                store.startAndAwait()
                for (action in actions) store.dispatchAndAwait(action)

                val changes = if (mutated) referenceWalk(reference, start, actions, wrongAt, wrongLeaf) else walk
                val (violations, covered) = expectedReport(reference, changes)
                assertEquals(violations, conformance.violations, "seed $seed")
                assertEquals(covered, conformance.coveredTransitions.toList(), "seed $seed")
                assertEquals(changes.lastOrNull()?.to ?: start, store.currentState.id, "seed $seed")
                if (mutated) {
                    mutants++
                    assertEquals(ConformanceViolation.UndeclaredTransition(wrongFrom!!, wrongLeaf!!, actions[wrongAt!!]), violations.firstOrNull(), "seed $seed")
                } else {
                    assertEquals(emptyList(), violations, "seed $seed: the reference itself must conform")
                    moves += walk.count { it.from != it.to }
                }
                store.close()
            }
        }
        assertTrue(moves >= 800, "only $moves leaf changes")
        assertTrue(ancestorMoves >= 250, "only $ancestorMoves transitions taken from an ancestor")
        assertTrue(mutants >= 150, "only $mutants mutants")
    }

    // endregion
}
