package koma.statechart

import koma.core.ExperimentalKomaApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property-based tests for [shortestPathTo], [transitionCoveragePaths] and [StateChartPath] over
 * [RandomCharts] (self-loops, duplicate transitions, guards, unreachable islands, undeclared
 * endpoints).
 *
 * Distances come from an independent Bellman-Ford style relaxation, not from a BFS.
 *
 * ```
 * [*] --> A
 * A --x--> B --y--> C          shortest path to C: [x, y]
 * A --z--> C'  (C' = C)        ...unless a direct edge exists: [z]
 * C --w--> C                   self-loop: covered by [x, y, w] or [z, w]
 * D --v--> A                   D unreachable: v is never in a coverage path
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartPathPropertyTest {

    private fun referenceDistances(chart: StateChartDefinition): Map<StateId, Int> {
        val distance = mutableMapOf(chart.initial to 0)
        repeat(chart.transitions.size + 1) {
            for (t in chart.transitions) {
                val d = distance[t.source] ?: continue
                if (d + 1 < (distance[t.target] ?: Int.MAX_VALUE)) distance[t.target] = d + 1
            }
        }
        return distance
    }

    /**
     * [RandomCharts] charts, grown by up to 20 transitions that leave already reachable states so
     * that most charts have deep, branching reachable parts where search order matters.
     */
    private fun forEachChart(block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            var chart = RandomCharts.chart(random, valid = false, maxStates = 12)
            val ids = chart.states.map { it.id }
            repeat(random.nextInt(0, 21)) {
                val source = chart.reachableStates().toList().random(random)
                val transition = Transition(source, ids.random(random), RandomCharts.matchers.random(random))
                val at = random.nextInt(chart.transitions.size + 1)
                chart = chart.copy(transitions = chart.transitions.take(at) + transition + chart.transitions.drop(at))
            }
            block(seed, random, chart)
        }
    }

    @Test
    fun generatorProducesDeepCharts() {
        var deep = 0
        forEachChart { _, _, chart ->
            if (referenceDistances(chart).values.any { it >= 3 }) deep++
        }
        assertTrue(deep >= 100, "only $deep charts with a state 3+ steps away")
    }

    private fun candidateIds(chart: StateChartDefinition) =
        (RandomCharts.allIds + chart.states.map { it.id } + chart.transitions.map { it.target }).distinct()

    @Test
    fun shortestPathLengthEqualsReferenceDistanceAndNullMeansUnreachable() = forEachChart { seed, _, chart ->
        val distances = referenceDistances(chart)
        for (id in candidateIds(chart)) {
            val path = chart.shortestPathTo(id)
            val distance = distances[id]
            if (distance == null) {
                assertNull(path, "seed $seed: $id is unreachable")
            } else {
                assertNotNull(path, "seed $seed: $id is reachable")
                assertEquals(distance, path.transitions.size, "seed $seed: $id")
                assertEquals(chart.initial, path.start, "seed $seed")
                assertEquals(id, path.end, "seed $seed")
                assertTrue(chart.transitions.containsAll(path.transitions), "seed $seed")
                assertEquals(path.transitions.map { it.on }, path.actions, "seed $seed")
            }
        }
        assertEquals(chart.reachableStates(), distances.keys, "seed $seed")
    }

    @Test
    fun shortestPathsFormATree() = forEachChart { seed, _, chart ->
        // Dropping the last step of a shortest path gives the shortest path to its source, so all
        // paths share prefixes and never visit a state twice.
        for (id in chart.reachableStates()) {
            val path = chart.shortestPathTo(id)!!
            val visited = listOf(path.start) + path.transitions.map { it.target }
            assertEquals(visited.distinct(), visited, "seed $seed: $path revisits a state")
            val last = path.transitions.lastOrNull() ?: continue
            assertEquals(chart.shortestPathTo(last.source)!!.transitions, path.transitions.dropLast(1), "seed $seed")
        }
    }

    @Test
    fun shortestPathPrefersTheEarliestDeclaredEdgeFromTheSameParent() = forEachChart { seed, _, chart ->
        for (id in chart.reachableStates()) {
            val last = chart.shortestPathTo(id)!!.transitions.lastOrNull() ?: continue
            // No earlier-declared transition from the same source reaches the same target.
            val first = chart.transitionsFrom(last.source).first { it.target == id }
            assertEquals(first, last, "seed $seed")
        }
    }

    @Test
    fun coveragePathsCoverExactlyTheTransitionsLeavingReachableStates() = forEachChart { seed, _, chart ->
        val reachable = referenceDistances(chart).keys
        val paths = chart.transitionCoveragePaths()
        val covered = paths.flatMap { it.transitions }.toSet()
        assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), covered, "seed $seed")
        assertTrue(chart.transitions.filter { it.source !in reachable }.none { it in covered }, "seed $seed")
    }

    @Test
    fun coveragePathsAreConnectedPrefixFreeAndIrredundant() = forEachChart { seed, _, chart ->
        val paths = chart.transitionCoveragePaths()
        for (path in paths) {
            assertEquals(chart.initial, path.start, "seed $seed")
            assertTrue(path.transitions.isNotEmpty(), "seed $seed")
            // Re-validate connectivity independently of the constructor.
            path.transitions.fold(chart.initial) { at, t -> assertEquals(at, t.source, "seed $seed"); t.target }
            // Every path is a shortest path to the last source, plus that last transition.
            val last = path.transitions.last()
            assertEquals(chart.shortestPathTo(last.source)!!.transitions, path.transitions.dropLast(1), "seed $seed")
        }
        for ((i, p) in paths.withIndex()) {
            for ((j, q) in paths.withIndex()) {
                if (i == j) continue
                val isPrefix = p.transitions.size <= q.transitions.size && q.transitions.subList(0, p.transitions.size) == p.transitions
                assertTrue(!isPrefix, "seed $seed: path $i is a prefix of path $j")
            }
        }
        // Removing any path loses its last transition.
        for ((i, p) in paths.withIndex()) {
            val others = paths.filterIndexed { j, _ -> j != i }.flatMap { it.transitions }.toSet()
            assertTrue(p.transitions.last() !in others, "seed $seed: path $i is redundant")
        }
    }

    @Test
    fun coveragePathsComeInDeclarationOrderOfTheirLastTransition() = forEachChart { seed, _, chart ->
        val order = chart.transitionCoveragePaths().map { chart.transitions.indexOf(it.transitions.last()) }
        assertEquals(order.sorted(), order, "seed $seed")
        assertEquals(order.distinct(), order, "seed $seed")
    }

    @Test
    fun coveragePathCountIsTheNumberOfLeavesOfTheCoverageTree() = forEachChart { seed, _, chart ->
        // Candidates are "tree path to source + transition"; only those that no other candidate
        // extends survive. Count them independently via tree edges.
        val reachable = referenceDistances(chart).keys
        val candidates = chart.transitions.filter { it.source in reachable }.distinct()
        val treeEdges = reachable.mapNotNull { chart.shortestPathTo(it)!!.transitions.lastOrNull() }.toSet()
        // A candidate is extended iff it is a tree edge whose target has an outgoing transition.
        val leaves = candidates.count { t -> !(t in treeEdges && chart.transitionsFrom(t.target).isNotEmpty()) }
        assertEquals(leaves, chart.transitionCoveragePaths().size, "seed $seed")
    }

    @Test
    fun resultsAreDeterministicAndIndependentOfUnreachableParts() = forEachChart { seed, random, chart ->
        val paths = chart.transitionCoveragePaths()
        assertEquals(paths, chart.transitionCoveragePaths(), "seed $seed")
        assertEquals(paths, chart.copy(transitions = chart.transitions.toList()).transitionCoveragePaths(), "seed $seed")

        // Adding transitions that leave an unreachable island changes nothing.
        val island = StateId("island ${random.nextInt(10)}")
        val withIsland = chart.copy(
            states = chart.states + AtomicState(island),
            transitions = chart.transitions.flatMap { listOf(Transition(island, it.source, it.on), it) },
        )
        assertEquals(paths, withIsland.transitionCoveragePaths(), "seed $seed")
        for (id in candidateIds(chart)) assertEquals(chart.shortestPathTo(id), withIsland.shortestPathTo(id), "seed $seed")
    }

    @Test
    fun pathConstructorAcceptsExactlyTheConnectedSequences() {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.chart(random, valid = false)
            if (chart.transitions.isEmpty()) continue
            val start = (chart.states.map { it.id } + chart.initial).random(random)
            val sequence = List(random.nextInt(0, 6)) { chart.transitions.random(random) }
            var at = start
            var connected = true
            for (t in sequence) {
                if (t.source != at) connected = false
                at = t.target
            }
            if (connected) {
                val path = StateChartPath(start, sequence)
                assertEquals(sequence.lastOrNull()?.target ?: start, path.end, "seed $seed")
                assertEquals(sequence.map { it.on }, path.actions, "seed $seed")
            } else {
                val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartPath(start, sequence) }
                assertTrue(error.message!!.startsWith("[Koma] Path is not connected"), "seed $seed")
            }
        }
    }

    @Test
    fun laterDeclaredDetourDoesNotHideAShorterPath() {
        // A depth-first search would reach X through C3 -> Y first (3 steps) instead of C1 (2 steps).
        val (init, c1, c3, y, x) = listOf("Init", "C1", "C3", "Y", "X").map(::StateId)
        val go = ActionMatcher("Go")
        val toC1 = Transition(init, c1, go)
        val c1ToX = Transition(c1, x, go)
        val chart = StateChartDefinition(
            initial = init,
            states = listOf(init, c1, c3, y, x).map(::AtomicState),
            transitions = listOf(toC1, Transition(init, c3, go), Transition(c3, y, go), Transition(y, x, go), c1ToX),
        )

        assertEquals(listOf(toC1, c1ToX), chart.shortestPathTo(x)?.transitions)
    }

    @Test
    fun selfLoopOnTheInitialStateIsAPathOfOne() {
        val a = StateId("A")
        val loop = Transition(a, a, ActionMatcher("Tick"))
        val chart = StateChartDefinition(a, listOf(AtomicState(a)), listOf(loop, loop))

        assertEquals(StateChartPath(a, emptyList()), chart.shortestPathTo(a))
        assertEquals(listOf(StateChartPath(a, listOf(loop))), chart.transitionCoveragePaths())
        assertEquals(a, StateChartPath(a, listOf(loop, loop, loop)).end)
    }

    @Test
    fun undeclaredInitialStillYieldsPathsFromIt() {
        val ghost = StateId("ghost")
        val b = StateId("B")
        val t = Transition(ghost, b, ActionMatcher("Go"))
        val chart = StateChartDefinition(ghost, listOf(AtomicState(b)), listOf(t))

        assertEquals(StateChartPath(ghost, listOf(t)), chart.shortestPathTo(b))
        assertEquals(listOf(StateChartPath(ghost, listOf(t))), chart.transitionCoveragePaths())
    }
}
