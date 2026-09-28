package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.PendingActionPolicy
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
 * Property-based tests for history states over [RandomCharts.withHistory]: random trees up to four
 * levels deep with shallow and deep history states (with or without a default) under random
 * compound states and transitions into them; and [RandomCharts.malformedHistory] variants.
 *
 * The reference ([HistoryReference]) restates the design note naively: record every history of an
 * exited node from the configuration before the step, then enter the recorded nodes (or the
 * default, or the parent's initial child) with the chain from the parent. Independently of both,
 * the walks track the last active child and leaf of every exited compound state and check that
 * a history restores exactly those.
 *
 * ```
 * [*] --> P
 * state P {
 *     [*] --> A
 *     state "[H]" as h                   shallow: remembers A or B
 *     state "[H*]" as d                  deep: remembers A, B1 or B2
 *     A --Ping--> B
 *     state B {
 *         [*] --> B1
 *         B1 --Pong--> B2
 *     }
 * }
 * P --Reset--> Q                         records h and d
 * Q --Go--> h                            B2 was active: enters P, B, B1 (B's initial)
 * Q --Ping--> d                          enters P, B, B2
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartHistoryPropertyTest {

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

    private fun HistoryFired.asResult() = StepResult.Transitioned(listOf(transition), exited, entered, after.asConfiguration())

    private fun ValidationIssue.isStructural() = this is ValidationIssue.UnknownParent || this is ValidationIssue.AtomicParent ||
        this is ValidationIssue.HistoryParent || this is ValidationIssue.ParentCycle || this is ValidationIssue.InitialNotChild ||
        this is ValidationIssue.EmptyCompoundState || this is ValidationIssue.HistoryAsInitial || this is ValidationIssue.InvalidHistoryDefault

    /** No history state is active; every record is what its kind remembers. */
    private fun checkConfiguration(seed: Int, chart: StateChartDefinition, configuration: StateConfiguration) {
        assertTrue(configuration.active.none { chart.node(it) is HistoryState }, "seed $seed: ${configuration.active}")
        assertEquals(1, chart.activeLeaves(configuration).size, "seed $seed")
        for ((id, recorded) in configuration.history.toList()) {
            val history = chart.node(id) as HistoryState
            val one = recorded.single()
            if (history.deep) {
                assertTrue(chart.node(one) is AtomicState && chart.isDescendant(one, history.parent), "seed $seed: $id remembers $recorded")
            } else {
                assertEquals(history.parent, chart.node(one)?.parent, "seed $seed: $id remembers $recorded")
                assertTrue(chart.node(one) !is HistoryState, "seed $seed")
            }
        }
    }

    // region generator

    @Test
    fun generatorProducesWellFormedChartsWithShallowAndDeepHistory() {
        var withHistory = 0
        var deepWithGrandchildren = 0
        var shallow = 0
        var defaults = 0
        RandomCharts.forEachHistoryChart { seed, _, chart ->
            assertEquals(emptyList(), chart.validate().filter { it.isStructural() || it is ValidationIssue.TransitionFromHistory }, "seed $seed")
            val histories = chart.states.filterIsInstance<HistoryState>()
            if (histories.isNotEmpty()) withHistory++
            if (histories.any { h -> h.deep && chart.states.any { chart.ancestorsOf(it.id).indexOf(h.parent) >= 1 } }) deepWithGrandchildren++
            if (histories.any { !it.deep }) shallow++
            if (histories.any { it.default != null }) defaults++
            assertTrue(chart.reachableStates().none { chart.node(it) is HistoryState }, "seed $seed")
        }
        assertTrue(withHistory >= 180, "only $withHistory charts with history")
        assertTrue(deepWithGrandchildren >= 50, "only $deepWithGrandchildren deep histories over two or more levels")
        assertTrue(shallow >= 120, "only $shallow charts with a shallow history")
        assertTrue(defaults >= 150, "only $defaults charts with a history default")
    }

    // endregion

    // region runtime

    @Test
    fun randomWalksAgreeWithTheReferenceAndRestoreWhatWasLastActive() {
        var steps = 0
        var restoredRecord = 0
        var restoredDefault = 0
        var restoredDeep = 0
        RandomCharts.forEachHistoryChart { seed, random, chart ->
            val table = GuardTable(random)
            val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())
            val reference = HistoryReference(chart)
            val atomic = chart.states.filterIsInstance<AtomicState>().map { it.id }
            var configuration = runtime.initialConfiguration()
            assertEquals(reference.initialConfiguration().asConfiguration(), configuration, "seed $seed")
            // Independent of both: the child and the leaf that were active when each compound was last exited.
            val lastChild = mutableMapOf<StateId, StateId>()
            val lastLeaf = mutableMapOf<StateId, StateId>()
            repeat(40) {
                if (random.nextInt(12) == 0) {
                    val keep = random.nextBoolean()
                    configuration = chart.configurationOf(atomic.random(random)).let { if (keep) it.copy(history = configuration.history) else it }
                    if (!keep) {
                        lastChild.clear()
                        lastLeaf.clear()
                    }
                }
                checkConfiguration(seed, chart, configuration)
                val leaf = chart.activeLeaves(configuration).single()
                val action = RandomCharts.actions.random(random)
                val result = runtime.step(configuration, RandomState(leaf), action)
                val expected = reference.step(configuration.asReference(), action, table::holds)
                assertEquals(expected?.asResult() ?: StepResult.Ignored, result, "seed $seed: $leaf on $action")
                if (result !is StepResult.Transitioned) return@repeat
                steps++
                assertTrue(result.entered.none { chart.node(it) is HistoryState }, "seed $seed: entered ${result.entered}")
                for (exited in result.exited) {
                    chart.childrenOf(exited).firstOrNull { it.id in configuration.active }?.let { lastChild[exited] = it.id }
                    if (chart.node(exited) is CompoundState) lastLeaf[exited] = leaf
                }
                val target = chart.node(result.target)
                if (target is HistoryState) {
                    val after = result.configuration.active
                    val newLeaf = chart.activeLeaves(result.configuration).single()
                    val remembered = if (target.deep) lastLeaf[target.parent] else lastChild[target.parent]
                    if (remembered != null) {
                        restoredRecord++
                        if (target.deep) assertEquals(remembered, newLeaf, "seed $seed: deep ${target.id}") else assertTrue(remembered in after, "seed $seed: shallow ${target.id}")
                        if (target.deep && chart.ancestorsOf(remembered).indexOf(target.parent) >= 1) restoredDeep++
                    } else {
                        restoredDefault++
                        val fallback = target.default ?: (chart.node(target.parent) as CompoundState).initial
                        assertTrue(fallback in after, "seed $seed: first entry of ${target.id} should enter $fallback")
                    }
                }
                configuration = result.configuration
            }
        }
        assertTrue(steps >= 3000, "only $steps steps")
        assertTrue(restoredRecord >= 150, "only $restoredRecord restores of a record")
        assertTrue(restoredDefault >= 150, "only $restoredDefault first entries")
        assertTrue(restoredDeep >= 20, "only $restoredDeep deep restores over two or more levels")
    }

    @Test
    fun leafOnlyStepEqualsTheConfigurationStepWithNothingRecorded() = RandomCharts.forEachHistoryChart(100) { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())
        for (leaf in chart.states.filterIsInstance<AtomicState>().map { it.id }) {
            for (action in RandomCharts.actions) {
                assertEquals(runtime.step(chart.configurationOf(leaf), RandomState(leaf), action), runtime.step(RandomState(leaf), action), "seed $seed")
            }
        }
    }

    @Test
    fun runtimeAcceptsExactlyTheChartsWithoutStructuralIssues() {
        var rejected = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedHistory(random, RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random)))
            val issues = chart.validate().filter { it.isStructural() }
            val guards = RandomCharts.guards.associateWith { { _: RandomState, _: Action -> true } }
            if (issues.isEmpty()) {
                StateChartRuntime(chart, { s: RandomState -> s.id }, guards)
            } else {
                rejected++
                val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartRuntime(chart, { s: RandomState -> s.id }, guards) }
                assertEquals("[Koma] Malformed state hierarchy: ${issues.joinToString()}", error.message, "seed $seed")
            }
        }
        assertTrue(rejected >= 200, "only $rejected rejected charts")
    }

    // endregion

    // region validation

    /** Naive history issues: the chart's and then each compound's history initial, then defaults, then sources. */
    private fun referenceHistoryIssues(chart: StateChartDefinition): List<ValidationIssue> {
        val first = LinkedHashMap<StateId, StateNode>()
        chart.states.forEach { if (it.id !in first) first[it.id] = it }
        val nodes = first.values.toList()
        fun ancestors(id: StateId): List<StateId> {
            val chain = mutableListOf<StateId>()
            var p = first[id]?.parent
            while (p != null && p in first && p !in chain && p != id) {
                chain += p
                p = first.getValue(p).parent
            }
            return chain
        }
        val issues = mutableListOf<ValidationIssue>()
        for (node in nodes) {
            val parent = node.parent ?: continue
            if (first[parent] is HistoryState) issues += ValidationIssue.HistoryParent(node.id, parent)
        }
        if (first[chart.initial] is HistoryState) issues += ValidationIssue.HistoryAsInitial(null, chart.initial)
        for (node in nodes) {
            if (node is CompoundState && first[node.initial] is HistoryState && first[node.initial]?.parent == node.id) {
                issues += ValidationIssue.HistoryAsInitial(node.id, node.initial)
            }
        }
        for (node in nodes) {
            if (node !is HistoryState || node.default == null || first[node.parent] !is CompoundState) continue
            val default = first[node.default]
            val ok = when {
                default == null || default is HistoryState -> false
                node.deep -> node.parent in ancestors(default.id)
                else -> default.parent == node.parent
            }
            if (!ok) issues += ValidationIssue.InvalidHistoryDefault(node.id, node.default)
        }
        for (t in chart.transitions) if (first[t.source] is HistoryState) issues += ValidationIssue.TransitionFromHistory(t)
        return issues
    }

    @Test
    fun historyIssuesEqualTheReferenceAndComeInTheirPlace() {
        val kinds = mutableSetOf<String>()
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedHistory(random, RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random)))
            val issues = chart.validate()
            val history = issues.filter {
                it is ValidationIssue.HistoryParent || it is ValidationIssue.HistoryAsInitial || it is ValidationIssue.InvalidHistoryDefault || it is ValidationIssue.TransitionFromHistory
            }
            assertEquals(referenceHistoryIssues(chart), history, "seed $seed")
            history.forEach { kinds += it::class.simpleName!! }
            // Structural issues first, then per transition (unknown source, unknown target, from history), then the rest.
            val structural = issues.takeWhile { it is ValidationIssue.DuplicateStateId || it is ValidationIssue.UnknownInitialState || it.isStructural() }
            assertEquals(issues.filter { it.isStructural() }, structural.filter { it.isStructural() }, "seed $seed")
            val endpoints = issues.drop(structural.size).takeWhile {
                it is ValidationIssue.UnknownTransitionSource || it is ValidationIssue.UnknownTransitionTarget || it is ValidationIssue.TransitionFromHistory
            }
            assertEquals(history.filterIsInstance<ValidationIssue.TransitionFromHistory>(), endpoints.filterIsInstance<ValidationIssue.TransitionFromHistory>(), "seed $seed")
            assertTrue(issues.filterIsInstance<ValidationIssue.UnreachableState>().none { chart.node(it.id) is HistoryState }, "seed $seed")
        }
        assertEquals(4, kinds.size, "kinds seen: $kinds")
    }

    @Test
    fun malformedHistoryChartsStillValidateExportAndSearchWithoutLooping() {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedHistory(random, RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random)))
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
    fun reachabilityAndShortestPathsFollowTheReference() = RandomCharts.forEachHistoryChart { seed, _, chart ->
        val reference = HistoryReference(chart)
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
            assertTrue(id in replay(seed, reference, path).active, "seed $seed: $id not active at the end of $path")
        }
    }

    /** Replays [path] with the reference; returns the final configuration. */
    private fun replay(seed: Int, reference: HistoryReference, path: StateChartPath): HistoryConfiguration {
        var configuration = reference.initialConfiguration()
        assertEquals(reference.leaf(configuration), path.start, "seed $seed")
        path.transitions.forEachIndexed { i, t ->
            assertTrue(t.source in configuration.active, "seed $seed: ${t.source} is not active before step $i")
            configuration = reference.fire(configuration, t).after
            assertEquals(reference.leaf(configuration), path.leaves[i], "seed $seed: leaf after step $i")
        }
        return configuration
    }

    @Test
    fun coveragePathsCoverEveryTransitionWhoseSourceCanBeActiveAndReplay() = RandomCharts.forEachHistoryChart { seed, _, chart ->
        val reference = HistoryReference(chart)
        val reachable = reference.nodeDistances().keys
        val paths = chart.transitionCoveragePaths()
        assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), paths.flatMap { it.transitions }.toSet(), "seed $seed")
        for (path in paths) replay(seed, reference, path)
    }

    // endregion

    // region mermaid

    private val historyLine = Regex("^( *)state \"\\[H(\\*?)]\" as ([A-Za-z_][A-Za-z0-9_]*)$")
    private val blockLine = Regex("^( *)state (?:\"[^\"]*\" as )?([A-Za-z_][A-Za-z0-9_]*) \\{$")
    private val labelled = Regex("^ *state \"([^\"]*)\" as ([A-Za-z_][A-Za-z0-9_]*)(?: \\{)?$")

    @Test
    fun mermaidDrawsEveryHistoryOnceInItsParentWithItsDefault() = RandomCharts.forEachHistoryChart { seed, _, chart ->
        val text = chart.toMermaid()
        val lines = text.lines()
        val histories = chart.states.filterIsInstance<HistoryState>()
        // Refs of non-history states from their labels (or plain ids), to find each history's parent block.
        val refOf = mutableMapOf<String, String>()
        for (line in lines) labelled.matchEntire(line)?.let { m -> if (!m.groupValues[1].startsWith("[H")) refOf[m.groupValues[1].replace("'", "\"")] = m.groupValues[2] }
        fun ref(id: StateId) = refOf[id.value] ?: id.value
        val drawn = mutableListOf<Triple<String?, Boolean, String>>()
        val stack = ArrayDeque<String>()
        for (line in lines.drop(1)) {
            blockLine.matchEntire(line)?.let { stack.addLast(it.groupValues[2]) }
            if (line.trim() == "}") stack.removeLast()
            val match = historyLine.matchEntire(line) ?: continue
            drawn += Triple(stack.lastOrNull(), match.groupValues[2] == "*", match.groupValues[3])
        }
        // Each history once, labelled by its kind, in its parent's block, with a ref of its own.
        assertEquals(histories.map { ref(it.parent) to it.deep }.sortedBy { it.toString() }, drawn.map { it.first to it.second }.sortedBy { it.toString() }, "seed $seed: $text")
        val historyRefs = drawn.map { it.third }
        assertEquals(historyRefs.distinct(), historyRefs, "seed $seed")
        assertTrue(historyRefs.none { it in refOf.values }, "seed $seed")
        for (history in histories) {
            val default = history.default ?: continue
            val group = drawn.filter { it.first == ref(history.parent) && it.second == history.deep }.map { it.third }
            assertTrue(lines.any { line -> group.any { line.trim() == "$it --> ${ref(default)}" } }, "seed $seed: no default edge for ${history.id}")
        }
        val edges = lines.count { " --> " in it && !it.trim().startsWith("[*]") }
        assertEquals(chart.transitions.size + histories.count { it.default != null }, edges, "seed $seed")
    }

    // endregion

    // region conformance

    private sealed interface NoEvent : Event

    private val guards: Map<String, (RandomState, Action) -> Boolean> = mapOf(
        "even" to { _, a -> a is RandomAction.Go && a.n % 2 == 0 },
        "positive" to { _, a -> a is RandomAction.Go && a.n > 0 },
        "never" to { _, _ -> false },
        "always" to { _, _ -> true },
    )

    private fun guardHolds(label: String, action: RandomAction) = guards.getValue(label)(RandomState(StateId("any")), action)

    /** A Store that keeps the chart configuration and follows the runtime, except at [wrongAt]. */
    private fun storeFor(chart: StateChartDefinition, wrongAt: Int?, wrongLeaf: StateId?, scope: TestScope): Store<RandomState, RandomAction, NoEvent> {
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, guards)
        var configuration = runtime.initialConfiguration()
        var index = 0
        return Store(RandomState(chart.activeLeaves(configuration).single())) {
            coroutineContext(StandardTestDispatcher(scope.testScheduler))
            pendingActionPolicy(PendingActionPolicy.Keep)
            state<RandomState> {
                action<RandomAction> {
                    if (index++ == wrongAt) {
                        configuration = chart.configurationOf(wrongLeaf!!).copy(history = configuration.history)
                    } else {
                        (runtime.step(configuration, state, action) as? StepResult.Transitioned)?.let { configuration = it.configuration }
                    }
                    val leaf = chart.activeLeaves(configuration).single()
                    if (leaf != state.id) nextState { RandomState(leaf) }
                }
            }
        }
    }

    private data class Change(val from: StateId, val to: StateId, val action: RandomAction)

    /** Every leaf [t] could enter from [from]: with nothing recorded, and with each record its target history could hold. */
    private fun couldEnter(reference: HistoryReference, from: StateId, t: Transition, history: Map<StateId, Set<StateId>>): Set<StateId> {
        val configuration = HistoryConfiguration(reference.tree.configurationOf(from), history)
        val entered = reference.fire(configuration, t).entered.toMutableSet()
        val target = reference.chart.states.firstOrNull { it.id == t.target } as? HistoryState
        if (target != null && target.id !in history) {
            for (node in reference.chart.states) {
                val fits = node !is HistoryState &&
                    if (target.deep) node is AtomicState && reference.tree.isProperAncestor(target.parent, node.id) else node.parent == target.parent
                if (fits) entered += reference.fire(configuration.copy(history = history + (target.id to setOf(node.id))), t).entered
            }
        }
        return entered
    }

    /** The plugin's documented report for [changes], restated with [HistoryReference]. */
    private fun expectedReport(reference: HistoryReference, changes: List<Change>): Pair<List<ConformanceViolation>, List<Transition>> {
        val violations = mutableListOf<ConformanceViolation>()
        val covered = mutableListOf<Transition>()
        var history = emptyMap<StateId, Set<StateId>>()
        for ((from, to, action) in changes) {
            val configuration = HistoryConfiguration(reference.tree.configurationOf(from), history)
            if (from == to) {
                val first = reference.tree.priority(from).firstOrNull { HierarchyReference.matches(it.on, action) }
                if (first != null && reference.leaf(reference.fire(configuration, first).after) == from && first !in covered) covered += first
                continue
            }
            val candidates = reference.tree.priority(from).filter { to in couldEnter(reference, from, it, history) }
            val taken = candidates.firstOrNull { HierarchyReference.matches(it.on, action) }
            history = if (taken != null) {
                reference.fire(configuration, taken).after.history
            } else {
                val exited = reference.tree.chain(from) - reference.tree.chain(to).toSet()
                history + reference.chart.states.filterIsInstance<HistoryState>().filter { it.parent in exited }.associate { it.id to reference.record(it, configuration.active) }
            }
            when {
                taken != null -> if (taken !in covered) covered += taken
                candidates.isEmpty() -> violations += ConformanceViolation.UndeclaredTransition(from, to, action)
                else -> violations += ConformanceViolation.UnexpectedTrigger(from, to, action)
            }
        }
        return violations to covered
    }

    @Test
    fun storesFollowingRandomHistoryChartsConformAndOneWrongLeafIsReported() = runTest {
        var restores = 0
        var mutants = 0
        for (seed in RandomCharts.seeds.take(150)) {
            val random = Random(seed)
            val chart = RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random))
            val reference = HistoryReference(chart)
            // Mostly actions that change the leaf, so walks go deep and come back through histories.
            var at = reference.initialConfiguration()
            val configurations = mutableListOf(at)
            val actions = List(random.nextInt(0, 41)) {
                fun next(action: RandomAction) = reference.step(at, action, ::guardHolds)
                val moving = RandomCharts.actions.filter { next(it)?.let { f -> reference.leaf(f.after) != reference.leaf(at) } == true }
                val action = if (moving.isNotEmpty() && random.nextInt(4) != 0) moving.random(random) else RandomCharts.actions.random(random)
                next(action)?.let { fired ->
                    if (chart.node(fired.transition.target) is HistoryState) restores++
                    at = fired.after
                }
                configurations += at
                action
            }
            val walk = actions.mapIndexed { i, action -> Change(reference.leaf(configurations[i]), reference.leaf(configurations[i + 1]), action) }

            // One wrong leaf, one that no transition from the current leaf could enter whatever was recorded.
            val wrongAt = if (actions.isEmpty()) null else random.nextInt(actions.size)
            val wrongFrom = wrongAt?.let { walk[it].from }
            val wrongLeaf = wrongFrom?.let { from ->
                val enterable = reference.tree.priority(from).flatMap { couldEnter(reference, from, it, emptyMap()) }.toSet()
                chart.states.filterIsInstance<AtomicState>().map { it.id }.filter { it != from && it !in enterable }.randomOrNull(random)
            }

            for (mutated in listOf(false, true)) {
                if (mutated && wrongLeaf == null) continue
                val conformance = StateChartConformance<RandomState, RandomAction, NoEvent>(chart) { it.id }
                val store = storeFor(chart, wrongAt.takeIf { mutated }, wrongLeaf, this).patch { plugin(conformance) }
                store.startAndAwait()
                for (action in actions) store.dispatchAndAwait(action)

                val changes = if (mutated) mutatedWalk(reference, actions, wrongAt!!, wrongLeaf!!) else walk
                val (violations, covered) = expectedReport(reference, changes)
                assertEquals(violations, conformance.violations, "seed $seed")
                assertEquals(covered, conformance.coveredTransitions.toList(), "seed $seed")
                assertEquals(changes.lastOrNull()?.to ?: reference.leaf(reference.initialConfiguration()), store.currentState.id, "seed $seed")
                if (mutated) {
                    mutants++
                    assertEquals(ConformanceViolation.UndeclaredTransition(wrongFrom!!, wrongLeaf!!, actions[wrongAt!!]), violations.firstOrNull(), "seed $seed")
                } else {
                    assertEquals(emptyList(), violations, "seed $seed: the runtime itself must conform")
                }
                store.close()
            }
        }
        assertTrue(restores >= 150, "only $restores transitions into a history")
        assertTrue(mutants >= 60, "only $mutants mutants")
    }

    /** The reference walk of the mutated Store: at [wrongAt] it jumps to [wrongLeaf], keeping the history. */
    private fun mutatedWalk(reference: HistoryReference, actions: List<RandomAction>, wrongAt: Int, wrongLeaf: StateId): List<Change> {
        var configuration = reference.initialConfiguration()
        return actions.mapIndexed { index, action ->
            val from = reference.leaf(configuration)
            configuration = if (index == wrongAt) {
                HistoryConfiguration(reference.tree.configurationOf(wrongLeaf), configuration.history)
            } else {
                reference.step(configuration, action, ::guardHolds)?.after ?: configuration
            }
            Change(from, reference.leaf(configuration), action)
        }
    }

    // endregion
}
