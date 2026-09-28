package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
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
 * Seeded generator of [StateChartDefinition]s for property-based tests.
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

    fun forEachChart(valid: Boolean = true, block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds) {
            val random = Random(seed)
            block(seed, random, chart(random, valid))
        }
    }
}
