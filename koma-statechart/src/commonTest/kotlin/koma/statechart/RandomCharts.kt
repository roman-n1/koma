package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import koma.core.State
import kotlin.random.Random

/**
 * Actions used by randomly generated charts. [Go] carries a payload so guards have something to
 * look at.
 */
sealed interface RandomAction : Action {
    data object Ping : RandomAction
    data object Pong : RandomAction
    data object Reset : RandomAction
    data class Go(val n: Int) : RandomAction
}

/**
 * A Koma state for runtime tests: a chart node plus data that guards can read.
 */
@OptIn(ExperimentalKomaApi::class)
data class RandomState(val id: StateId, val attempts: Int = 0) : State

/**
 * Seeded generator of [StateChartDefinition]s for property-based tests: flat charts ([chart]) and
 * hierarchical ones ([hierarchicalChart], [malformed]).
 *
 * Charts mix plain identifiers with ids that Mermaid cannot use directly (spaces, dashes, quotes,
 * unicode, keywords, and ids that look like the exporter's own aliases), and may contain
 * self-loops, duplicate transitions, guards and unreachable islands. With `valid = false` they may
 * also contain duplicate states and undeclared endpoints.
 */
@OptIn(ExperimentalKomaApi::class)
internal object RandomCharts {
    /** Seeds used by every property; fixed so failures reproduce. */
    val seeds: List<Int> = List(300) { it * 7919 + 17 }

    val plainIds = listOf("Idle", "Loading", "Ready", "Error", "_hidden", "s1", "S2", "a_b_c")

    val trickyIds = listOf(
        "Not signed in", "Signed-in", "état", "状態", "Ω", " padded ", "a\"quoted\"", "1st", "a.b",
        "a:b", "[*]", "-->", "state", "State", "end", "note", "direction", "class", "classDef", "style",
        "koma_state_0", "koma_state_1", "koma_state_2",
    )

    val allIds: List<StateId> = (plainIds + trickyIds).map(::StateId)

    val matchers = listOf(
        ActionMatcher.of<RandomAction.Ping>("Ping"),
        ActionMatcher.of<RandomAction.Pong>("Pong"),
        ActionMatcher.of<RandomAction.Go>("Go"),
        ActionMatcher("Reset"),
        ActionMatcher("Go"),
        ActionMatcher("Anything", RandomAction::class),
    )

    val guards = listOf("even", "positive", "never", "always")

    val actions: List<RandomAction> = listOf(RandomAction.Ping, RandomAction.Pong, RandomAction.Reset) +
        (-3..4).map { RandomAction.Go(it) }

    fun chart(random: Random, valid: Boolean = true, maxStates: Int = 9, maxTransitions: Int = 18): StateChartDefinition {
        val pool = allIds.shuffled(random)
        val stateCount = random.nextInt(1, maxStates + 1)
        val ids = pool.take(stateCount).toMutableList()
        val undeclared = pool.drop(stateCount).take(2)
        if (!valid && random.nextInt(3) == 0) {
            ids.add(random.nextInt(ids.size + 1), ids.random(random))
        }
        fun endpoint(): StateId =
            if (!valid && undeclared.isNotEmpty() && random.nextInt(8) == 0) undeclared.random(random) else ids.random(random)

        val transitions = mutableListOf<Transition>()
        repeat(random.nextInt(0, maxTransitions + 1)) {
            val roll = random.nextInt(10)
            val next = when {
                roll == 0 && transitions.isNotEmpty() -> transitions.random(random)
                roll == 1 -> endpoint().let { Transition(it, it, matchers.random(random)) }
                else -> Transition(
                    source = endpoint(),
                    target = endpoint(),
                    on = matchers.random(random),
                    guard = if (random.nextInt(3) == 0) guards.random(random) else null,
                )
            }
            transitions += next
        }
        val initial = if (!valid && undeclared.isNotEmpty() && random.nextInt(6) == 0) undeclared.first() else ids.random(random)
        // Grow the part reachable from the initial state, inserting at random positions, so that
        // most charts have deep, branching reachable parts and not only unreachable noise.
        repeat(random.nextInt(0, maxTransitions / 2 + 1)) {
            val source = reachableFrom(initial, transitions).random(random)
            val grown = Transition(source, endpoint(), matchers.random(random), if (random.nextInt(4) == 0) guards.random(random) else null)
            transitions.add(random.nextInt(transitions.size + 1), grown)
        }
        return StateChartDefinition(initial, ids.map(::AtomicState), transitions)
    }

    /** Plain fixed-point reachability, independent of the library's own [reachableStates]. */
    private fun reachableFrom(initial: StateId, transitions: List<Transition>): List<StateId> {
        val reachable = mutableListOf(initial)
        var changed = true
        while (changed) {
            changed = false
            for (t in transitions) {
                if (t.source in reachable && t.target !in reachable) {
                    reachable += t.target
                    changed = true
                }
            }
        }
        return reachable
    }

    /**
     * A random tree of [AtomicState]s and [CompoundState]s at most [maxDepth] levels deep (a
     * top-level node is level 1), declared in shuffled order so parents may come after their
     * children, with random transitions between any nodes (compound ones included), self-loops,
     * duplicates and guards. The initial state is usually a top-level node, sometimes a nested one.
     * Every compound state has children and a child as its initial state, so the hierarchy is well
     * formed; see [malformed] for broken ones.
     */
    fun hierarchicalChart(random: Random, maxStates: Int = 12, maxDepth: Int = 4, maxTransitions: Int = 20): StateChartDefinition {
        val ids = allIds.shuffled(random).take(random.nextInt(1, maxStates + 1))
        val parents = arrayOfNulls<StateId>(ids.size)
        val depths = IntArray(ids.size)
        val compound = BooleanArray(ids.size)
        for (i in ids.indices) {
            val containers = (0 until i).filter { compound[it] && depths[it] < maxDepth - 1 }
            if (containers.isNotEmpty() && random.nextInt(5) != 0) {
                val p = containers.random(random)
                parents[i] = ids[p]
                depths[i] = depths[p] + 1
            }
            compound[i] = depths[i] < maxDepth - 1 && random.nextInt(5) < 2
        }
        val nodes = ids.indices.map { i ->
            val children = ids.indices.filter { parents[it] == ids[i] }
            if (compound[i] && children.isNotEmpty()) {
                CompoundState(ids[i], initial = ids[children.random(random)], parent = parents[i])
            } else {
                AtomicState(ids[i], parent = parents[i])
            }
        }.shuffled(random)

        val transitions = mutableListOf<Transition>()
        repeat(random.nextInt(0, maxTransitions + 1)) {
            val roll = random.nextInt(10)
            transitions += when {
                roll == 0 && transitions.isNotEmpty() -> transitions.random(random)
                roll == 1 -> ids.random(random).let { Transition(it, it, matchers.random(random)) }
                else -> Transition(
                    source = ids.random(random),
                    target = ids.random(random),
                    on = matchers.random(random),
                    guard = if (random.nextInt(3) == 0) guards.random(random) else null,
                )
            }
        }
        val roots = nodes.filter { it.parent == null }.map { it.id }
        val initial = if (random.nextInt(6) == 0) ids.random(random) else roots.random(random)
        return StateChartDefinition(initial, nodes, transitions)
    }

    /**
     * Breaks the hierarchy of [chart] in one to three random ways: an unknown parent, an atomic
     * parent, a parent cycle, a compound initial state that is not a child, an empty compound
     * state, or a duplicate declaration with another parent.
     */
    fun malformed(random: Random, chart: StateChartDefinition): StateChartDefinition {
        var states = chart.states
        fun replace(index: Int, node: StateNode) {
            states = states.toMutableList().also { it[index] = node }
        }
        fun withParent(node: StateNode, parent: StateId?): StateNode = when (node) {
            is AtomicState -> node.copy(parent = parent)
            is CompoundState -> node.copy(parent = parent)
        }
        repeat(random.nextInt(1, 4)) {
            val index = random.nextInt(states.size)
            val node = states[index]
            when (random.nextInt(6)) {
                0 -> replace(index, withParent(node, allIds.firstOrNull { id -> states.none { it.id == id } } ?: StateId("ghost parent")))
                1 -> states.filterIsInstance<AtomicState>().randomOrNull(random)?.let { replace(index, withParent(node, it.id)) }
                2 -> {
                    // Make a node the parent of one of its ancestors, or of itself.
                    val chain = generateSequence(node) { n -> n.parent?.let { p -> states.firstOrNull { it.id == p } } }.take(states.size).toList()
                    val top = chain.random(random)
                    replace(states.indexOf(top), withParent(top, node.id))
                }
                3 -> replace(index, CompoundState(node.id, initial = allIds.random(random), parent = node.parent))
                4 -> states = states + CompoundState(StateId("empty ${random.nextInt(3)}"), initial = node.id, parent = node.parent.takeIf { random.nextBoolean() })
                else -> states = states + AtomicState(node.id, parent = states.random(random).id)
            }
        }
        return chart.copy(states = states)
    }

    fun forEachHierarchicalChart(block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds) {
            val random = Random(seed)
            block(seed, random, hierarchicalChart(random))
        }
    }

    fun forEachChart(valid: Boolean = true, block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds) {
            val random = Random(seed)
            block(seed, random, chart(random, valid))
        }
    }
}
