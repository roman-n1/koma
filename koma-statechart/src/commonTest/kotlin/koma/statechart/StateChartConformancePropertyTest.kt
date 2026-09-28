package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.PendingActionPolicy
import koma.core.PluginExecutionPolicy
import koma.core.State
import koma.core.Store
import koma.test.dispatchAndAwait
import koma.test.patch
import koma.test.startAndAwait
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Property-based and timing tests for [StateChartConformance] against *real* Koma Stores.
 *
 * For each seed a random chart over the fixed states `N0..N7` is generated, and a Store is built
 * whose single catch-all `state<Node> { action<RandomAction> { ... } }` handler follows that chart
 * (optionally with one deliberate wrong target). A random walk is dispatched with `koma-test`, and
 * the plugin's report is compared with a reference computed from the walk.
 *
 * ```
 * [*] --> N0
 * N0 --Go [even]--> N3      the Store picks targets with StateChartRuntime
 * N0 --Anything--> N0       self-loop: no state change, never covered
 * N3 --Ping--> N7
 * N7 --Go--> N7(n + 1)      data change inside one variant: not a transition
 * N3 ~~mutant~~> N5         wrong target: exactly one UndeclaredTransition
 * ```
 */
@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartConformancePropertyTest {

    sealed interface Node : State {
        data object N0 : Node
        data object N1 : Node
        data object N2 : Node
        data object N3 : Node
        data object N4 : Node
        data object N5 : Node
        data object N6 : Node
        data class N7(val n: Int = 0) : Node
    }

    sealed interface NoEvent : Event

    private val nodes: List<Node> = listOf(Node.N0, Node.N1, Node.N2, Node.N3, Node.N4, Node.N5, Node.N6, Node.N7())
    private val nodeIds: List<StateId> = listOf("N0", "N1", "N2", "N3", "N4", "N5", "N6", "N7").map(::StateId)

    private fun nodeOf(id: StateId): Node = nodes[nodeIds.indexOf(id)]

    private val guards: Map<String, (Action) -> Boolean> = mapOf(
        "even" to { a -> a is RandomAction.Go && a.n % 2 == 0 },
        "positive" to { a -> a is RandomAction.Go && a.n > 0 },
        "never" to { _ -> false },
        "always" to { _ -> true },
    )

    private val matcherNames = mapOf(RandomAction.Ping to "Ping", RandomAction.Pong to "Pong", RandomAction.Reset to "Reset")

    /** Hard-coded matcher semantics, independent of the library's `matches`. */
    private fun referenceMatches(matcher: ActionMatcher, action: RandomAction): Boolean = when (matcher.type) {
        null -> matcher.name == (matcherNames[action] ?: "Go")
        RandomAction::class -> true
        RandomAction.Go::class -> action is RandomAction.Go
        RandomAction.Ping::class -> action == RandomAction.Ping
        RandomAction.Pong::class -> action == RandomAction.Pong
        else -> error("unexpected matcher $matcher")
    }

    private fun chart(random: Random): StateChartDefinition {
        val ids = nodeIds.shuffled(random).take(random.nextInt(1, nodeIds.size + 1))
        val transitions = mutableListOf<Transition>()
        if (random.nextBoolean()) {
            // A cycle through every state, so long walks are possible.
            ids.forEachIndexed { i, id -> transitions += Transition(id, ids[(i + 1) % ids.size], RandomCharts.matchers.random(random)) }
            transitions.shuffle(random)
        }
        repeat(random.nextInt(0, 3 * ids.size + 3)) {
            transitions += if (transitions.isNotEmpty() && random.nextInt(10) == 0) {
                transitions.random(random)
            } else {
                Transition(
                    source = ids.random(random),
                    target = ids.random(random),
                    on = RandomCharts.matchers.random(random),
                    guard = if (random.nextInt(3) == 0) guards.keys.random(random) else null,
                )
            }
        }
        return StateChartDefinition(ids.random(random), ids.map(::AtomicState), transitions)
    }

    private data class Change(val from: StateId, val to: StateId, val action: RandomAction?)

    /** A wrong target to use for the dispatch at [index]. */
    private data class Mutation(val index: Int, val target: StateId)

    /**
     * A Store implementing [chart] with one catch-all handler. `N7` also bumps its counter on `Go`
     * when the chart ignores the action, which is a data change inside one variant.
     */
    private fun storeFor(
        chart: StateChartDefinition,
        dispatcher: CoroutineDispatcher,
        mutation: Mutation? = null,
    ): Store<Node, RandomAction, NoEvent> {
        val runtime = StateChartRuntime(chart, guards)
        var dispatchIndex = 0
        return Store(initialState = nodeOf(chart.initial)) {
            coroutineContext(dispatcher)
            pendingActionPolicy(PendingActionPolicy.Keep)
            state<Node> {
                action<RandomAction> {
                    val from = defaultStateId(state)
                    val index = dispatchIndex++
                    val stepped = (runtime.step(from, action) as? StepResult.Transitioned)?.target ?: from
                    val to = if (mutation?.index == index) mutation.target else stepped
                    val current = state
                    val dispatched = action
                    when {
                        to != from -> nextState { nodeOf(to) }
                        current is Node.N7 && dispatched is RandomAction.Go -> nextState { current.copy(n = current.n + dispatched.n) }
                    }
                }
            }
        }
    }

    private fun walk(random: Random, chart: StateChartDefinition): List<RandomAction> {
        val runtime = StateChartRuntime(chart, guards)
        var current = chart.initial
        return List(random.nextInt(0, 41)) {
            val moving = RandomCharts.actions.filter { (runtime.step(current, it) as? StepResult.Transitioned)?.target.let { t -> t != null && t != current } }
            val action = if (moving.isNotEmpty() && random.nextInt(4) != 0) moving.random(random) else RandomCharts.actions.random(random)
            current = (runtime.step(current, action) as? StepResult.Transitioned)?.target ?: current
            action
        }
    }

    /** Replays [actions] with a naive step (first matching transition with true guard). */
    private fun referenceChanges(chart: StateChartDefinition, actions: List<RandomAction>, mutation: Mutation? = null): Pair<List<Change>, List<Transition>> {
        var current = chart.initial
        val changes = mutableListOf<Change>()
        val taken = mutableListOf<Transition>()
        actions.forEachIndexed { index, action ->
            val transition = chart.transitions.firstOrNull { t ->
                t.source == current && referenceMatches(t.on, action) && (t.guard == null || guards.getValue(t.guard)(action))
            }
            val to = if (mutation?.index == index) mutation.target else transition?.target ?: current
            if (mutation?.index != index && transition != null && to != current) taken += transition
            if (to != current) changes += Change(current, to, action)
            current = to
        }
        return changes to taken
    }

    /** The plugin's documented report for [changes]. */
    private fun expectedReport(chart: StateChartDefinition, changes: List<Change>): Pair<List<ConformanceViolation>, List<Transition>> {
        val declared = chart.states.map { it.id }
        val violations = mutableListOf<ConformanceViolation>()
        val covered = mutableListOf<Transition>()
        for (change in changes) {
            if (change.to !in declared) violations += ConformanceViolation.UndeclaredState(change.to)
            val candidates = chart.transitions.filter { it.source == change.from && it.target == change.to }
            val taken = candidates.firstOrNull { change.action != null && referenceMatches(it.on, change.action) } ?: candidates.firstOrNull()
            when {
                taken == null -> violations += ConformanceViolation.UndeclaredTransition(change.from, change.to, change.action)
                taken !in covered -> covered += taken
            }
        }
        return violations to covered
    }

    /** True when no two different transitions between the same states match the same action. */
    private fun attributionIsUnambiguous(chart: StateChartDefinition): Boolean =
        chart.transitions.groupBy { it.source to it.target }.values.all { group ->
            val distinct = group.distinct()
            RandomCharts.actions.all { action -> distinct.count { referenceMatches(it.on, action) } <= 1 }
        }

    private suspend fun TestScope.runWalk(
        chart: StateChartDefinition,
        actions: List<RandomAction>,
        mutation: Mutation? = null,
    ): Pair<Store<Node, RandomAction, NoEvent>, StateChartConformance<Node, RandomAction, NoEvent>> {
        val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
        val store = storeFor(chart, StandardTestDispatcher(testScheduler), mutation).patch { plugin(conformance) }
        store.startAndAwait()
        for (action in actions) store.dispatchAndAwait(action)
        return store to conformance
    }

    @Test
    fun conformingStoreFollowingRandomWalksHasNoViolationsAndCoversExactlyTheWalk() = runTest {
        var unambiguous = 0
        var nonTrivial = 0
        for (seed in RandomCharts.seeds.take(150)) {
            val random = Random(seed)
            val chart = chart(random)
            val actions = walk(random, chart)
            val (store, conformance) = runWalk(chart, actions)

            val (changes, taken) = referenceChanges(chart, actions)
            val (violations, covered) = expectedReport(chart, changes)
            assertEquals(emptyList(), violations, "seed $seed: the reference itself must conform")
            assertEquals(emptyList(), conformance.violations, "seed $seed")
            assertEquals(covered, conformance.coveredTransitions.toList(), "seed $seed")
            assertEquals(chart.transitions.filter { it !in covered }, conformance.uncoveredTransitions, "seed $seed")
            assertEquals(changes.lastOrNull()?.to ?: chart.initial, defaultStateId(store.currentState), "seed $seed")
            assertTrue(conformance.coveredTransitions.none { it.source == it.target }, "seed $seed: self-loops never change state")

            if (attributionIsUnambiguous(chart)) {
                unambiguous++
                assertEquals(taken.distinct(), conformance.coveredTransitions.toList(), "seed $seed: covered != walked")
            }
            if (changes.size >= 3) nonTrivial++
            store.close()
        }
        assertTrue(unambiguous >= 50, "only $unambiguous unambiguous charts")
        assertTrue(nonTrivial >= 50, "only $nonTrivial walks with 3+ transitions")
    }

    @Test
    fun oneWrongTargetYieldsExactlyOneViolation() = runTest {
        var mutated = 0
        var undeclaredTargets = 0
        for (seed in RandomCharts.seeds.take(150)) {
            val random = Random(seed)
            val chart = chart(random)
            val actions = walk(random, chart)
            if (actions.isEmpty()) continue
            val index = random.nextInt(actions.size)
            // State before the mutated dispatch, from the unmutated prefix.
            val from = referenceChanges(chart, actions.take(index)).first.lastOrNull()?.to ?: chart.initial
            val wrong = nodeIds.filter { it != from && chart.transitions.none { t -> t.source == from && t.target == it } }
            if (wrong.isEmpty()) continue
            val mutation = Mutation(index, wrong.random(random))
            mutated++

            val (store, conformance) = runWalk(chart, actions, mutation)

            val undeclared = chart.states.none { it.id == mutation.target }
            if (undeclared) undeclaredTargets++
            val exact = listOfNotNull(
                ConformanceViolation.UndeclaredState(mutation.target).takeIf { undeclared },
                ConformanceViolation.UndeclaredTransition(from, mutation.target, actions[index]),
            )
            assertEquals(exact, conformance.violations, "seed $seed")
            val (violations, covered) = expectedReport(chart, referenceChanges(chart, actions, mutation).first)
            assertEquals(violations, conformance.violations, "seed $seed")
            assertEquals(covered, conformance.coveredTransitions.toList(), "seed $seed")
            store.close()
        }
        assertTrue(mutated >= 80, "only $mutated mutants")
        assertTrue(undeclaredTargets >= 10, "only $undeclaredTargets mutants into undeclared states")
    }

    @Test
    fun hundredsOfQueuedDispatchesAreCheckedInDispatchOrder() = runTest {
        for (seed in RandomCharts.seeds.take(30)) {
            val random = Random(seed)
            val chart = chart(random)
            val actions = List(200) { RandomCharts.actions.random(random) }
            val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
            val store = storeFor(chart, StandardTestDispatcher(testScheduler)).patch { plugin(conformance) }

            // Fire-and-forget from many coroutines; the Store queues them in launch order.
            coroutineScope { actions.forEach { action -> launch { store.dispatch(action) } } }
            advanceUntilIdle()

            val (changes, _) = referenceChanges(chart, actions)
            val (violations, covered) = expectedReport(chart, changes)
            assertEquals(violations, conformance.violations, "seed $seed")
            assertEquals(covered, conformance.coveredTransitions.toList(), "seed $seed")
            assertEquals(changes.lastOrNull()?.to ?: chart.initial, defaultStateId(store.currentState), "seed $seed")
            store.close()
        }
    }

    @Test
    fun trulyConcurrentDispatchersStillConform() = runTest {
        for (seed in RandomCharts.seeds.take(10)) {
            val random = Random(seed)
            val chart = chart(random)
            val actions = List(100) { RandomCharts.actions.random(random) }
            val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
            val store = storeFor(chart, Dispatchers.Default).patch { plugin(conformance) }

            withContext(Dispatchers.Default) {
                coroutineScope { actions.forEach { action -> launch { store.dispatchAndAwait(action) } } }
            }

            // Whatever order the dispatches ran in, every step followed the chart.
            assertEquals(emptyList(), conformance.violations, "seed $seed")
            assertTrue(chart.transitions.containsAll(conformance.coveredTransitions), "seed $seed")
            assertTrue(defaultStateId(store.currentState) in chart.reachableStates(), "seed $seed")
            store.close()
        }
    }

    // region timing edge cases

    private val n0 = StateId("N0")
    private val n1 = StateId("N1")
    private val n2 = StateId("N2")
    private val n3 = StateId("N3")
    private val n7 = StateId("N7")
    private val ping = ActionMatcher.of<RandomAction.Ping>()
    private val pong = ActionMatcher.of<RandomAction.Pong>()
    private val go = ActionMatcher.of<RandomAction.Go>()
    private val reset = ActionMatcher("Reset")
    private val auto = ActionMatcher("Auto")

    private fun chartOf(initial: StateId, vararg transitions: Transition) = StateChartDefinition(
        initial = initial,
        states = (listOf(initial) + transitions.flatMap { listOf(it.source, it.target) }).distinct().map(::AtomicState),
        transitions = transitions.toList(),
    )

    private fun TestScope.delayedStore(chart: StateChartDefinition): Pair<Store<Node, RandomAction, NoEvent>, StateChartConformance<Node, RandomAction, NoEvent>> {
        val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
        val store = Store<Node, RandomAction, NoEvent>(initialState = Node.N0) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            pendingActionPolicy(PendingActionPolicy.Keep)
            state<Node.N0> {
                action<RandomAction.Ping> {
                    launch {
                        delay(1_000)
                        transaction { nextState { Node.N1 } }
                    }
                }
                action<RandomAction.Go> { nextState { Node.N2 } }
            }
        }.patch { plugin(conformance) }
        return store to conformance
    }

    @Test
    fun launchedTransitionIsAttributedToTheLatestActionWhenThatActionAlsoMatches() = runTest {
        val viaPing = Transition(n0, n1, ping)
        val viaPong = Transition(n0, n1, pong)
        val (store, conformance) = delayedStore(chartOf(n0, viaPing, viaPong, Transition(n0, n2, go)))

        store.dispatchAndAwait(RandomAction.Ping)
        store.dispatchAndAwait(RandomAction.Pong) // no handler in N0: ignored, but seen by the plugin
        advanceTimeBy(999)
        runCurrent()
        assertEquals(Node.N0, store.currentState)
        assertEquals(emptySet(), conformance.coveredTransitions)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(Node.N1, store.currentState)
        // The change came from Ping's launch, but the plugin only knows Pong was last.
        assertEquals(setOf(viaPong), conformance.coveredTransitions)
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    @Test
    fun launchedTransitionFallsBackToAnyDeclaredTransitionBetweenTheStates() = runTest {
        val viaPing = Transition(n0, n1, ping)
        val (store, conformance) = delayedStore(chartOf(n0, viaPing, Transition(n0, n2, go)))

        store.dispatchAndAwait(RandomAction.Ping)
        store.dispatchAndAwait(RandomAction.Reset)
        advanceUntilIdle()

        assertEquals(setOf(viaPing), conformance.coveredTransitions)
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    @Test
    fun launchCancelledByLeavingTheStateNeverReachesThePlugin() = runTest {
        val viaGo = Transition(n0, n2, go)
        val (store, conformance) = delayedStore(chartOf(n0, Transition(n0, n1, ping), viaGo))

        store.dispatchAndAwait(RandomAction.Ping)
        advanceTimeBy(500)
        store.dispatchAndAwait(RandomAction.Go(1))
        advanceUntilIdle()

        assertEquals(Node.N2, store.currentState)
        assertEquals(setOf(viaGo), conformance.coveredTransitions)
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    @Test
    fun undeclaredLaunchedTransitionReportsTheUnrelatedLastAction() = runTest {
        val (store, conformance) = delayedStore(chartOf(n0, Transition(n0, n2, go)).let { it.copy(states = it.states + AtomicState(n1)) })

        store.dispatchAndAwait(RandomAction.Ping)
        store.dispatchAndAwait(RandomAction.Pong)
        store.dispatchAndAwait(RandomAction.Reset)
        val before = conformance.violations
        advanceUntilIdle()

        assertEquals(listOf(ConformanceViolation.UndeclaredTransition(n0, n1, RandomAction.Reset)), conformance.violations)
        assertEquals(emptyList(), before, "an earlier report is a snapshot")
        store.close()
    }

    private fun TestScope.chainedStore(chart: StateChartDefinition): Pair<Store<Node, RandomAction, NoEvent>, StateChartConformance<Node, RandomAction, NoEvent>> {
        val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
        val store = Store<Node, RandomAction, NoEvent>(initialState = Node.N0) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Node.N0> { enter { nextState { Node.N1 } } }
            state<Node.N1> { enter { nextState { Node.N2 } } }
            state<Node.N2> { action<RandomAction.Reset> { nextState { Node.N0 } } }
        }.patch { plugin(conformance) }
        return store to conformance
    }

    @Test
    fun enterChainsAtStartupAndAfterAnActionAreAllChecked() = runTest {
        val first = Transition(n0, n1, auto)
        val second = Transition(n1, n2, auto)
        val back = Transition(n2, n0, reset)
        val (store, conformance) = chainedStore(chartOf(n0, first, second, back))

        store.startAndAwait()
        assertEquals(Node.N2, store.currentState)
        assertEquals(listOf(first, second), conformance.coveredTransitions.toList())

        store.dispatchAndAwait(RandomAction.Reset)
        assertEquals(Node.N2, store.currentState)
        assertEquals(listOf(first, second, back), conformance.coveredTransitions.toList())
        assertEquals(emptyList(), conformance.violations)
        assertEquals(emptyList(), conformance.uncoveredTransitions)
        store.close()
    }

    @Test
    fun startupIsJudgedBeforeEnterMovesOn() = runTest {
        // The chart claims the Store starts in N2, where it settles; the plugin sees N0 first.
        val (store, conformance) = chainedStore(chartOf(n2, Transition(n2, n0, reset), Transition(n0, n1, auto), Transition(n1, n2, auto)))

        store.startAndAwait()

        assertEquals(listOf(ConformanceViolation.UnexpectedInitialState(expected = n2, actual = n0)), conformance.violations)
        assertEquals(2, conformance.coveredTransitions.size)
        store.close()
    }

    @Test
    fun startingInAnUndeclaredStateReportsBothProblems() = runTest {
        val (store, conformance) = chainedStore(chartOf(n1, Transition(n1, n2, auto)))

        store.startAndAwait()

        assertEquals(
            listOf(
                ConformanceViolation.UndeclaredState(n0),
                ConformanceViolation.UnexpectedInitialState(expected = n1, actual = n0),
                ConformanceViolation.UndeclaredTransition(from = n0, to = n1, lastAction = null),
            ),
            conformance.violations,
        )
        assertEquals(listOf(Transition(n1, n2, auto)), conformance.coveredTransitions.toList())
        store.close()
    }

    private fun TestScope.recoveringStore(chart: StateChartDefinition): Pair<Store<Node, RandomAction, NoEvent>, StateChartConformance<Node, RandomAction, NoEvent>> {
        val conformance = StateChartConformance<Node, RandomAction, NoEvent>(chart)
        val store = Store<Node, RandomAction, NoEvent>(initialState = Node.N0) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<Node.N0> {
                action<RandomAction.Go> {
                    require(action.n >= 0) { "negative" }
                    nextState { Node.N1 }
                }
                recover<IllegalArgumentException> { nextState { Node.N3 } }
            }
        }.patch { plugin(conformance) }
        return store to conformance
    }

    @Test
    fun stateChangeFromRecoverIsCheckedWithTheFailingAction() = runTest {
        val ok = Transition(n0, n1, go)
        val failed = Transition(n0, n3, go)
        val (store, conformance) = recoveringStore(chartOf(n0, ok, failed))

        store.dispatchAndAwait(RandomAction.Go(-1))

        assertEquals(Node.N3, store.currentState)
        assertEquals(listOf(failed), conformance.coveredTransitions.toList())
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    @Test
    fun undeclaredRecoveryIsReported() = runTest {
        val (store, conformance) = recoveringStore(chartOf(n0, Transition(n0, n1, go)).let { it.copy(states = it.states + AtomicState(n3)) })

        store.dispatchAndAwait(RandomAction.Go(-7))

        assertEquals(listOf(ConformanceViolation.UndeclaredTransition(n0, n3, RandomAction.Go(-7))), conformance.violations)
        store.close()
    }

    @Test
    fun dataChangesInsideOneVariantAreNeverTransitionsButCustomIdsCanMakeThemSo() = runTest {
        val random = Random(99)
        val bumps = List(50) { RandomAction.Go(random.nextInt(-5, 6)) }
        val chart = chartOf(n0, Transition(n0, n7, ping), Transition(n7, n0, reset))

        // Default ids: every Go only changes N7's counter.
        val (store, conformance) = runWalk(chart, listOf(RandomAction.Ping) + bumps)
        assertEquals(Node.N7(bumps.sumOf { it.n }), store.currentState)
        assertEquals(listOf(chart.transitions[0]), conformance.coveredTransitions.toList())
        assertEquals(emptyList(), conformance.violations)
        store.close()

        // Custom ids split N7 by counter parity, so a parity change becomes a chart transition.
        val even = StateId("N7 even")
        val odd = StateId("N7 odd")
        val parityChart = chartOf(n0, Transition(n0, even, ping), Transition(even, odd, go), Transition(odd, even, go))
        val parity = StateChartConformance<Node, RandomAction, NoEvent>(parityChart) { node ->
            if (node is Node.N7) (if (node.n % 2 == 0) even else odd) else defaultStateId(node)
        }
        val parityStore = storeFor(chart, StandardTestDispatcher(testScheduler)).patch { plugin(parity) }
        parityStore.dispatchAndAwait(RandomAction.Ping)
        bumps.forEach { parityStore.dispatchAndAwait(it) }

        val sums = bumps.runningFold(0) { sum, action -> sum + action.n }
        val flips = sums.zipWithNext().count { (a, b) -> a % 2 != b % 2 }
        assertEquals(emptyList(), parity.violations)
        assertEquals(
            listOfNotNull(parityChart.transitions[0], parityChart.transitions[1].takeIf { flips > 0 }, parityChart.transitions[2].takeIf { flips > 1 }),
            parity.coveredTransitions.toList(),
        )
        parityStore.close()
    }

    @Test
    fun closedStoreStopsReporting() = runTest {
        val chart = chartOf(n0, Transition(n0, n2, go))
        val (store, conformance) = delayedStore(chart.copy(states = chart.states + AtomicState(n1)))

        store.dispatchAndAwait(RandomAction.Ping) // launches the delayed, undeclared N0 -> N1
        store.close()
        store.dispatch(RandomAction.Go(2))
        advanceUntilIdle()

        assertEquals(Node.N0, store.currentState)
        assertEquals(emptyList(), conformance.violations)
        assertEquals(emptySet(), conformance.coveredTransitions)
        assertEquals(listOf(Transition(n0, n2, go)), conformance.uncoveredTransitions)
    }

    @Test
    fun twoPluginsWithDifferentChartsJudgeTheSameStoreIndependently() = runTest {
        for (policy in PluginExecutionPolicy.entries) {
            val strict = StateChartConformance<Node, RandomAction, NoEvent>(chartOf(n0, Transition(n0, n1, ping)).let { it.copy(states = it.states + AtomicState(n2)) })
            val loose = StateChartConformance<Node, RandomAction, NoEvent>(chartOf(n0, Transition(n0, n1, ping), Transition(n0, n2, go)))
            val store = Store<Node, RandomAction, NoEvent>(initialState = Node.N0) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                pluginExecutionPolicy(policy)
                state<Node.N0> { action<RandomAction.Go> { nextState { Node.N2 } } }
            }.patch { plugin(strict, loose) }

            store.dispatchAndAwait(RandomAction.Go(4))

            assertEquals(listOf(ConformanceViolation.UndeclaredTransition(n0, n2, RandomAction.Go(4))), strict.violations, "$policy")
            assertEquals(emptyList(), loose.violations, "$policy")
            assertEquals(setOf(Transition(n0, n2, go)), loose.coveredTransitions, "$policy")
            store.close()
        }
    }

    @Test
    fun defaultStateIdIsTheClassNameForEveryVariant() {
        assertEquals(nodeIds, nodes.map(::defaultStateId))
        assertEquals(n7, defaultStateId(Node.N7(n = -42)))
    }

    @Test
    fun defaultStateIdOfAnAnonymousStateFailsWhereThePlatformHasNoSimpleName() {
        val anonymous = object : State {}
        val name = anonymous::class.simpleName
        val result = runCatching { defaultStateId(anonymous) }
        if (name == null) {
            val error = assertFailsWith<IllegalArgumentException> { result.getOrThrow() }
            assertTrue(error.message!!.startsWith("[Koma] State type must have a simple name"))
        } else {
            assertEquals(StateId(name), result.getOrThrow())
        }
    }

    // endregion
}
