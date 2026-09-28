package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.PendingActionPolicy
import koma.core.State
import koma.core.Store
import koma.test.dispatchAndAwait
import koma.test.patch
import koma.test.startAndAwait
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property-based tests for parallel states over [RandomCharts.parallelChart] (with
 * [RandomCharts.withHistory]): random trees up to four levels deep of atomic, compound and parallel
 * states, with atomic, compound and nested parallel regions, shallow and deep history states under
 * compound and parallel parents, and groups of transitions that share a matcher across regions;
 * and [RandomCharts.malformedParallel] variants.
 *
 * The reference ([ParallelReference]) restates the design note naively: SCXML selection over all
 * active leaves, then a configuration filled in until it is legal. Independently of both, every
 * configuration is checked for the invariants below.
 *
 * ```
 * [*] --> P
 * state P {
 *     state A {
 *         [*] --> A1
 *         A1 --Ping--> A2                one Ping fires in both regions
 *     }
 *     --
 *     state B {
 *         [*] --> B1
 *         B1 --Ping--> B2
 *         B1 --Pong--> A2                 to another region: exits and re-enters P
 *     }
 * }
 * P --Reset--> Q                          exits every region
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartParallelPropertyTest {

    /** A random truth value per guard label and action. */
    private class GuardTable(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }

        fun holds(label: String, action: RandomAction): Boolean = table.getValue(label).getValue(action)

        fun implementations(): Map<String, (RandomState, Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { _: RandomState, action: Action -> holds(label, action as RandomAction) }
        }
    }

    private fun HistoryConfiguration.asConfiguration() = StateConfiguration(active, history)

    private fun StateConfiguration.asReference() = HistoryConfiguration(active, history)

    private fun ParallelFired.asResult() = StepResult.Transitioned(transitions, exited, entered, after.asConfiguration())

    private fun ValidationIssue.isStructural() = this is ValidationIssue.UnknownParent || this is ValidationIssue.AtomicParent ||
        this is ValidationIssue.HistoryParent || this is ValidationIssue.ParentCycle || this is ValidationIssue.InitialNotChild ||
        this is ValidationIssue.EmptyCompoundState || this is ValidationIssue.HistoryAsInitial || this is ValidationIssue.InvalidHistoryDefault

    /**
     * Every active node's parent is active; an active compound state has exactly one active child,
     * an active parallel state all its regions; no history state is active.
     */
    private fun checkLegal(seed: Int, chart: StateChartDefinition, active: Set<StateId>) {
        for (id in active) {
            val node = chart.node(id)
            assertTrue(node != null && node !is HistoryState, "seed $seed: $id active")
            node.parent?.let { assertTrue(it in active, "seed $seed: $id active without its parent $it") }
            val activeChildren = chart.childrenOf(id).filter { it.id in active }
            when (node) {
                is CompoundState -> assertEquals(1, activeChildren.size, "seed $seed: $id has active children $activeChildren")
                is ParallelState -> assertEquals(
                    chart.childrenOf(id).filter { it !is HistoryState }.map { it.id },
                    activeChildren.map { it.id },
                    "seed $seed: parallel $id",
                )
                else -> assertTrue(activeChildren.isEmpty(), "seed $seed")
            }
        }
    }

    // region generator

    @Test
    fun generatorProducesWellFormedChartsWithRegionsAndSharedMatchers() {
        var withParallel = 0
        var nested = 0
        var atomicRegions = 0
        var parallelHistory = 0
        var nestedInitial = 0
        RandomCharts.forEachParallelChart { seed, _, chart ->
            assertEquals(emptyList(), chart.validate().filter { it.isStructural() || it is ValidationIssue.TooFewRegions }, "seed $seed")
            val parallels = chart.states.filterIsInstance<ParallelState>()
            if (parallels.isNotEmpty()) withParallel++
            if (parallels.any { p -> chart.ancestorsOf(p.id).any { chart.node(it) is ParallelState } }) nested++
            if (parallels.any { p -> chart.childrenOf(p.id).any { it is AtomicState } }) atomicRegions++
            if (chart.states.any { it is HistoryState && chart.node(it.parent) is ParallelState }) parallelHistory++
            if (chart.ancestorsOf(chart.initial).any { chart.node(it) is ParallelState }) nestedInitial++
            checkLegal(seed, chart, StateChartRuntime(chart, { s: RandomState -> s.id }, GuardTable(Random(seed)).implementations()).initialConfiguration().active)
        }
        assertTrue(withParallel >= 230, "only $withParallel charts with a parallel state")
        assertTrue(nested >= 15, "only $nested nested parallel states")
        assertTrue(atomicRegions >= 30, "only $atomicRegions charts with an atomic region")
        assertTrue(parallelHistory >= 60, "only $parallelHistory charts with a history of a parallel state")
        assertTrue(nestedInitial >= 20, "only $nestedInitial charts starting inside a region")
    }

    // endregion

    // region runtime

    @Test
    fun randomWalksAgreeWithTheReferenceAndKeepTheInvariants() {
        var steps = 0
        var together = 0
        var conflicts = 0
        var restores = 0
        RandomCharts.forEachParallelChart { seed, random, chart ->
            val table = GuardTable(random)
            val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())
            val reference = ParallelReference(chart)
            var configuration = runtime.initialConfiguration()
            assertEquals(reference.initialConfiguration().asConfiguration(), configuration, "seed $seed")
            repeat(30) {
                checkLegal(seed, chart, configuration.active)
                val action = RandomCharts.actions.random(random)
                val leaves = chart.activeLeaves(configuration)
                assertEquals(reference.leaves(configuration.active), leaves, "seed $seed")
                val result = runtime.step(configuration, RandomState(leaves.first()), action)
                val expected = reference.step(configuration.asReference(), action, table::holds)
                assertEquals(expected?.asResult() ?: StepResult.Ignored, result, "seed $seed: $leaves on $action")
                if (result !is StepResult.Transitioned) return@repeat
                steps++
                if (result.transitions.size > 1) together++
                val picks = leaves.mapNotNull { leaf -> chart.candidatesFor(leaf).firstOrNull { t -> t.on?.matches(action) == true && t.guard.let { it == null || table.holds(it, action) } } }.distinct()
                if (picks.size > result.transitions.size) conflicts++
                if (result.transitions.any { t -> (chart.node(t.target) as? HistoryState)?.let { chart.node(it.parent) is ParallelState } == true }) restores++
                // Taken transitions never conflict: their exit sets are disjoint.
                val exitSets = result.transitions.map { t -> configuration.active.filter { a -> chart.domainOf(t).let { d -> d == null || chart.isDescendant(a, d) } }.toSet() }
                for (i in exitSets.indices) for (j in i + 1 until exitSets.size) assertTrue(exitSets[i].none { it in exitSets[j] }, "seed $seed")
                // Exit innermost first, entry outermost first; exited nodes not re-entered are inactive; entered ones active.
                val exitDepths = result.exited.map { chart.ancestorsOf(it).size }
                assertEquals(exitDepths.sortedDescending(), exitDepths, "seed $seed")
                val entryDepths = result.entered.map { chart.ancestorsOf(it).size }
                assertEquals(entryDepths.sorted(), entryDepths, "seed $seed")
                assertTrue((result.exited - result.entered.toSet()).none { it in result.configuration.active }, "seed $seed")
                assertTrue(result.configuration.active.containsAll(result.entered), "seed $seed")
                assertTrue(configuration.active.containsAll(result.exited), "seed $seed")
                assertEquals(configuration.active - result.exited.toSet() + result.entered, result.configuration.active, "seed $seed")
                configuration = result.configuration
            }
        }
        assertTrue(steps >= 2500, "only $steps steps")
        assertTrue(together >= 150, "only $together steps with transitions in several regions")
        assertTrue(conflicts >= 100, "only $conflicts steps where a conflict dropped a transition")
        assertTrue(restores >= 20, "only $restores restores of a parallel state's history")
    }

    @Test
    fun runtimeAcceptsExactlyTheChartsWithoutStructuralIssues() {
        var rejected = 0
        var warned = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedParallel(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random)))
            val issues = chart.validate()
            if (issues.any { it is ValidationIssue.TooFewRegions }) warned++
            val structural = issues.filter { it.isStructural() }
            val guards = RandomCharts.guards.associateWith { { _: RandomState, _: Action -> true } }
            if (structural.isEmpty()) {
                val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, guards)
                checkLegal(seed, chart, runtime.initialConfiguration().active)
            } else {
                rejected++
                val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartRuntime(chart, { s: RandomState -> s.id }, guards) }
                assertEquals("[Koma] Malformed state hierarchy: ${structural.joinToString()}", error.message, "seed $seed")
            }
        }
        assertTrue(rejected >= 100, "only $rejected rejected charts")
        assertTrue(warned >= 150, "only $warned charts with too few regions")
    }

    // endregion

    // region validation

    @Test
    fun parallelIssuesEqualTheReferenceAndComeBetweenHistoryAndEndpoints() {
        var tooFew = 0
        var badDefaults = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedParallel(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random)))
            val first = LinkedHashMap<StateId, StateNode>()
            chart.states.forEach { if (it.id !in first) first[it.id] = it }
            val expected = first.values.filterIsInstance<ParallelState>().mapNotNull { p ->
                val regions = first.values.filter { it.parent == p.id && it !is HistoryState }.map { it.id }
                if (regions.size < 2) ValidationIssue.TooFewRegions(p.id, regions) else null
            }
            val issues = chart.validate()
            assertEquals(expected, issues.filterIsInstance<ValidationIssue.TooFewRegions>(), "seed $seed")
            tooFew += expected.size
            // A shallow history of a parallel state needs a region as default.
            for (h in first.values.filterIsInstance<HistoryState>()) {
                val default = h.default ?: continue
                if (first[h.parent] !is ParallelState || h.deep) continue
                val ok = first[default].let { it != null && it !is HistoryState && it.parent == h.parent }
                assertEquals(!ok, ValidationIssue.InvalidHistoryDefault(h.id, default) in issues, "seed $seed: ${h.id}")
                if (!ok) badDefaults++
            }
            // Order: structural and history issues, then too few regions, then endpoints and the rest.
            val positions = issues.withIndex().filter { it.value is ValidationIssue.TooFewRegions }.map { it.index }
            if (positions.isNotEmpty()) {
                assertEquals((positions.first()..positions.last()).toList(), positions, "seed $seed")
                assertTrue(issues.take(positions.first()).all { it.isStructural() || it is ValidationIssue.DuplicateStateId || it is ValidationIssue.UnknownInitialState }, "seed $seed")
                assertTrue(issues.drop(positions.last() + 1).none { it.isStructural() }, "seed $seed")
            }
        }
        assertTrue(tooFew >= 200, "only $tooFew parallel states with too few regions")
        assertTrue(badDefaults >= 30, "only $badDefaults invalid defaults of a parallel state's history")
    }

    @Test
    fun malformedParallelChartsStillValidateExportAndSearchWithoutLooping() {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformed(random, RandomCharts.malformedParallel(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random))))
            val reachable = chart.reachableStates()
            for (id in reachable) assertNotNull(chart.shortestPathTo(id), "seed $seed")
            assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), chart.transitionCoveragePaths().flatMap { it.transitions }.toSet(), "seed $seed")
            assertEquals(chart.toMermaid(), chart.toMermaid(), "seed $seed")
            chart.validate()
        }
    }

    // endregion

    // region paths

    @Test
    fun reachabilityAndShortestPathsFollowTheReferenceAndReplayOnTheRuntime() {
        var multi = 0
        RandomCharts.forEachParallelChart { seed, _, chart ->
            val reference = ParallelReference(chart)
            val distances = reference.nodeDistances()
            assertEquals(distances.keys, chart.reachableStates(), "seed $seed")
            val order = chart.reachableStates().map { distances.getValue(it) }
            assertEquals(order.sorted(), order, "seed $seed")
            assertEquals(
                chart.states.map { it.id }.filter { it !in distances && chart.node(it) !is HistoryState }.map { ValidationIssue.UnreachableState(it) },
                chart.validate().filterIsInstance<ValidationIssue.UnreachableState>(),
                "seed $seed",
            )
            for (id in chart.states.map { it.id }) {
                val path = chart.shortestPathTo(id)
                val distance = distances[id]
                if (distance == null) {
                    assertNull(path, "seed $seed: $id")
                    continue
                }
                assertNotNull(path, "seed $seed: $id")
                assertEquals(distance, path.transitions.size, "seed $seed: $id")
                val end = replay(seed, chart, reference, path)
                assertTrue(id in end.active, "seed $seed: $id not active at the end of $path")
                if (path.activeLeaves.any { it.size > 1 }) multi++
            }
        }
        assertTrue(multi >= 500, "only $multi paths through several regions")
    }

    /**
     * Replays [path] with the reference's graph step and checks each step against the runtime: on a
     * copy of the chart where every transition has its own guard and one common matcher, the guards
     * enable exactly what an action matching the trigger's matcher, and nothing else, would with
     * every guard true, minus what conflicts with the trigger. Returns the final configuration.
     */
    private fun replay(seed: Int, chart: StateChartDefinition, reference: ParallelReference, path: StateChartPath): HistoryConfiguration {
        var configuration = reference.initialConfiguration()
        assertEquals(reference.leaves(configuration.active), path.startLeaves, "seed $seed")
        assertEquals(path.startLeaves.first(), path.start, "seed $seed")
        var trigger: Transition? = null
        val fire = ActionMatcher("Fire", Fire::class)
        val relabelled = chart.copy(transitions = chart.transitions.mapIndexed { i, t -> Transition(t.source, t.target, fire, "t$i") })
        val guards = chart.transitions.withIndex().associate { (i, original) ->
            "t$i" to { _: RandomState, _: Action ->
                val t = trigger!!
                original == t || (original.on == t.on && reference.exitSet(configuration.active, original).none { it in reference.exitSet(configuration.active, t) })
            }
        }
        val runtime = StateChartRuntime(relabelled, { s: RandomState -> s.id }, guards)
        path.transitions.forEachIndexed { i, t ->
            assertTrue(t.source in configuration.active, "seed $seed: ${t.source} is not active before step $i")
            val fired = reference.graphStep(configuration, t)
            assertTrue(t in fired.transitions, "seed $seed")
            trigger = t
            val stepped = runtime.step(configuration.asConfiguration(), RandomState(t.source), Fire)
            assertEquals(fired.after.asConfiguration(), (stepped as? StepResult.Transitioned)?.configuration, "seed $seed: step $i")
            configuration = fired.after
            assertEquals(reference.leaves(configuration.active), path.activeLeaves[i], "seed $seed: leaves after step $i")
            assertEquals(path.activeLeaves[i].first(), path.leaves[i], "seed $seed")
        }
        return configuration
    }

    /** The one action of the relabelled chart in [replay]. */
    private data object Fire : Action

    @Test
    fun coveragePathsCoverEveryTransitionWhoseSourceCanBeActiveAndReplay() = RandomCharts.forEachParallelChart(150) { seed, _, chart ->
        val reference = ParallelReference(chart)
        val reachable = reference.nodeDistances().keys
        val paths = chart.transitionCoveragePaths()
        assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), paths.flatMap { it.transitions }.toSet(), "seed $seed")
        for (path in paths) replay(seed, chart, reference, path)
    }

    // endregion

    // region mermaid

    private val blockLine = Regex("^( *)state (?:\"[^\"]*\" as )?([A-Za-z_][A-Za-z0-9_]*) \\{$")
    private val labelled = Regex("^ *state \"([^\"]*)\" as ([A-Za-z_][A-Za-z0-9_]*)(?: \\{)?$")

    @Test
    fun mermaidDrawsEachRegionInItsOwnSectionOfTheParallelBlock() = RandomCharts.forEachParallelChart { seed, _, chart ->
        val text = chart.toMermaid()
        val lines = text.lines()
        val refOf = mutableMapOf<String, String>()
        for (line in lines) labelled.matchEntire(line)?.let { m -> if (!m.groupValues[1].startsWith("[H")) refOf[m.groupValues[1].replace("'", "\"")] = m.groupValues[2] }
        fun ref(id: StateId) = refOf[id.value] ?: id.value
        // For each block: its sections, each the refs drawn directly in it (a nested block counts as its ref).
        val sections = mutableMapOf<String, MutableList<MutableList<String>>>()
        val stack = ArrayDeque<String>()
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            val block = blockLine.matchEntire(line)
            when {
                block != null -> {
                    stack.lastOrNull()?.let { sections.getValue(it).last() += block.groupValues[2] }
                    stack.addLast(block.groupValues[2])
                    sections[block.groupValues[2]] = mutableListOf(mutableListOf())
                }
                trimmed == "}" -> stack.removeLast()
                trimmed == "--" -> sections.getValue(stack.last()) += mutableListOf<String>()
                " --> " in trimmed -> Unit
                else -> stack.lastOrNull()?.let { owner ->
                    val drawn = labelled.matchEntire(line)?.groupValues?.get(2) ?: trimmed
                    sections.getValue(owner).last() += drawn
                }
            }
        }
        for (parallel in chart.states.filterIsInstance<ParallelState>()) {
            val regions = chart.childrenOf(parallel.id).filter { it !is HistoryState }
            val drawn = sections.getValue(ref(parallel.id))
            assertEquals(regions.size, drawn.size, "seed $seed: sections of ${parallel.id}\n$text")
            val histories = chart.childrenOf(parallel.id).filterIsInstance<HistoryState>()
            drawn.forEachIndexed { i, section ->
                // The region first, then (first section only) the parallel state's history states.
                assertEquals(ref(regions[i].id), section.first(), "seed $seed\n$text")
                assertEquals(if (i == 0) 1 + histories.size else 1, section.size, "seed $seed\n$text")
            }
        }
        // No transition is written inside a parallel block, and none is lost.
        val edges = lines.count { " --> " in it && !it.trim().startsWith("[*]") }
        assertEquals(chart.transitions.size + chart.states.count { it is HistoryState && it.default != null }, edges, "seed $seed")
        stack.clear()
        for (line in lines.drop(1)) {
            blockLine.matchEntire(line)?.let { stack.addLast(it.groupValues[2]) }
            if (line.trim() == "}") stack.removeLast()
            if (" --> " in line && !line.trim().startsWith("[*]")) {
                val owner = stack.lastOrNull() ?: continue
                assertTrue(chart.states.none { it is ParallelState && ref(it.id) == owner }, "seed $seed: $line in parallel $owner")
            }
        }
    }

    // endregion

    // region conformance

    private sealed interface NoEvent : Event

    private data class Leaves(val leaves: Set<StateId>) : State

    private val guards: Map<String, (Leaves, Action) -> Boolean> = mapOf(
        "even" to { _, a -> a is RandomAction.Go && a.n % 2 == 0 },
        "positive" to { _, a -> a is RandomAction.Go && a.n > 0 },
        "never" to { _, _ -> false },
        "always" to { _, _ -> true },
    )

    /** A Store that keeps the configuration and follows the runtime; at [wrongAt] it swaps [wrong]'s first leaf for its second instead. */
    private fun storeFor(chart: StateChartDefinition, wrongAt: Int?, wrong: Pair<StateId, StateId>?, taken: MutableSet<Transition>, scope: TestScope): Store<Leaves, RandomAction, NoEvent> {
        val runtime = StateChartRuntime(chart, { s: Leaves -> s.leaves.first() }, guards)
        var configuration = runtime.initialConfiguration()
        var index = 0
        return Store(Leaves(chart.activeLeaves(configuration).toSet())) {
            coroutineContext(StandardTestDispatcher(scope.testScheduler))
            pendingActionPolicy(PendingActionPolicy.Keep)
            state<Leaves> {
                action<RandomAction> {
                    if (index++ == wrongAt) {
                        val (from, to) = wrong!!
                        configuration = chart.configurationOf(chart.activeLeaves(configuration) - from + to).copy(history = configuration.history)
                    } else {
                        (runtime.step(configuration, state, action) as? StepResult.Transitioned)?.let {
                            configuration = it.configuration
                            taken += it.transitions
                        }
                    }
                    val leaves = chart.activeLeaves(configuration).toSet()
                    if (leaves != state.leaves) nextState { Leaves(leaves) }
                }
            }
        }
    }

    /**
     * An active leaf in a compound state and an atomic sibling that no transition exiting the leaf
     * enters from [configuration], found with the reference. Siblings below the parent of a history
     * state such a transition targets are skipped: the plugin's own record of that history may
     * differ from the runtime's (it sees no self-loops), and with none it accepts any restore.
     */
    private fun unenterable(chart: StateChartDefinition, reference: ParallelReference, configuration: HistoryConfiguration): Pair<StateId, StateId>? {
        for (leaf in reference.leaves(configuration.active)) {
            val parent = reference.parent(leaf) ?: continue
            if (chart.node(parent) !is CompoundState) continue
            val exiting = chart.transitions.filter { it.source in configuration.active && leaf in reference.exitSet(configuration.active, it) }
            val sibling = chart.states.filter { it is AtomicState && it.parent == parent && it.id != leaf }.firstOrNull { w ->
                exiting.none { t ->
                    val target = chart.node(t.target)
                    (target is HistoryState && reference.inside(w.id, target.parent)) || w.id in reference.fire(configuration, listOf(t)).entered
                }
            }
            if (sibling != null) return leaf to sibling.id
        }
        return null
    }

    @Test
    fun storesFollowingRandomParallelChartsConformAndOneWrongRegionIsReported() = runTest {
        var multi = 0
        var mutants = 0
        var covered = 0
        for (seed in RandomCharts.seeds.take(150)) {
            val random = Random(seed)
            val chart = RandomCharts.withHistory(random, RandomCharts.parallelChart(random))
            val runtime = StateChartRuntime(chart, { s: Leaves -> s.leaves.first() }, guards)
            val reference = ParallelReference(chart)
            // A walk of actions, and where along it one region could jump somewhere undeclared.
            var configuration = runtime.initialConfiguration()
            val actions = mutableListOf<RandomAction>()
            var wrongAt: Int? = null
            var wrong: Pair<StateId, StateId>? = null
            repeat(random.nextInt(0, 31)) { i ->
                val action = RandomCharts.actions.random(random)
                if (wrong == null && random.nextBoolean()) {
                    unenterable(chart, reference, HistoryConfiguration(configuration.active, configuration.history))?.let {
                        wrong = it
                        wrongAt = i
                    }
                }
                actions += action
                (runtime.step(configuration, Leaves(emptySet()), action) as? StepResult.Transitioned)?.let {
                    if (it.transitions.size > 1) multi++
                    configuration = it.configuration
                }
            }
            for (mutated in listOf(false, true)) {
                if (mutated && wrong == null) continue
                val taken = mutableSetOf<Transition>()
                val conformance = StateChartConformance.withActiveLeaves<Leaves, RandomAction, NoEvent>(chart) { it.leaves }
                val store = storeFor(chart, wrongAt.takeIf { mutated }, wrong, taken, this).patch { plugin(conformance) }
                store.startAndAwait()
                for (action in actions) store.dispatchAndAwait(action)
                if (mutated) {
                    mutants++
                    val (from, to) = wrong!!
                    assertEquals(ConformanceViolation.UndeclaredTransition(from, to, actions[wrongAt!!]), conformance.violations.firstOrNull(), "seed $seed")
                } else {
                    assertEquals(emptyList(), conformance.violations, "seed $seed: the runtime itself must conform")
                    // Leaf changes are credited to transitions the runtime took; self-loops are credited
                    // by action with guards ignored (see StateChartConformance), so only by matcher.
                    for (t in conformance.coveredTransitions) {
                        assertTrue(t in taken || actions.any { t.on?.matches(it) == true }, "seed $seed: covered $t")
                    }
                    covered += conformance.coveredTransitions.count { it in taken }
                    assertEquals(configuration.active, chart.configurationOf(store.currentState.leaves).active, "seed $seed")
                }
                store.close()
            }
        }
        assertTrue(multi >= 60, "only $multi steps in several regions")
        assertTrue(mutants >= 30, "only $mutants mutants")
        assertTrue(covered >= 200, "only $covered covered transitions")
    }

    @Test
    fun oneLeafMappingAndActiveLeavesMappingAgreeOnChartsWithoutParallelStates() = runTest {
        for (seed in RandomCharts.seeds.take(60)) {
            val random = Random(seed)
            val chart = RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random))
            val runtime = StateChartRuntime(chart, { s: Leaves -> s.leaves.single() }, guards)
            val actions = List(random.nextInt(0, 25)) { RandomCharts.actions.random(random) }
            val reports = listOf(
                StateChartConformance<Leaves, RandomAction, NoEvent>(chart) { it.leaves.single() },
                StateChartConformance.withActiveLeaves<Leaves, RandomAction, NoEvent>(chart) { it.leaves },
            ).map { conformance ->
                // A Store that follows the runtime but sometimes jumps to a random leaf.
                var configuration = runtime.initialConfiguration()
                val jumps = Random(seed)
                val atomic = chart.states.filterIsInstance<AtomicState>().map { it.id }
                val store = Store<Leaves, RandomAction, NoEvent>(Leaves(chart.activeLeaves(configuration).toSet())) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    pendingActionPolicy(PendingActionPolicy.Keep)
                    state<Leaves> {
                        action<RandomAction> {
                            configuration = if (jumps.nextInt(6) == 0) {
                                chart.configurationOf(atomic.random(jumps)).copy(history = configuration.history)
                            } else {
                                (runtime.step(configuration, state, action) as? StepResult.Transitioned)?.configuration ?: configuration
                            }
                            val leaves = chart.activeLeaves(configuration).toSet()
                            if (leaves != state.leaves) nextState { Leaves(leaves) }
                        }
                    }
                }.patch { plugin(conformance) }
                store.startAndAwait()
                for (action in actions) store.dispatchAndAwait(action)
                store.close()
                conformance.violations to conformance.coveredTransitions
            }
            assertEquals(reports[0], reports[1], "seed $seed")
        }
    }

    // endregion
}
