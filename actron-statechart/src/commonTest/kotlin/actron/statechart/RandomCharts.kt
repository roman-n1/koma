package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.State
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
 * A Actron state for runtime tests: a chart node plus data that guards can read.
 */
@OptIn(ExperimentalActronApi::class)
data class RandomState(val id: StateId, val attempts: Int = 0) : State

/**
 * Seeded generator of [StateChartDefinition]s for property-based tests: flat charts ([chart]),
 * hierarchical ones ([hierarchicalChart], [malformed]), ones with history states
 * ([withHistory], [malformedHistory]), ones with parallel states ([parallelChart],
 * [malformedParallel]) and ones with timers ([withTimers], [malformedTimers]).
 *
 * Charts mix plain identifiers with ids that Mermaid cannot use directly (spaces, dashes, quotes,
 * unicode, keywords, and ids that look like the exporter's own aliases), and may contain
 * self-loops, duplicate transitions, guards and unreachable islands. With `valid = false` they may
 * also contain duplicate states and undeclared endpoints.
 */
@OptIn(ExperimentalActronApi::class)
internal object RandomCharts {
    /** Seeds used by every property; fixed so failures reproduce. */
    val seeds: List<Int> = List(300) { it * 7919 + 17 }

    val plainIds = listOf("Idle", "Loading", "Ready", "Error", "_hidden", "s1", "S2", "a_b_c")

    val trickyIds = listOf(
        "Not signed in", "Signed-in", "état", "状態", "Ω", " padded ", "a\"quoted\"", "1st", "a.b",
        "a:b", "[*]", "-->", "state", "State", "end", "note", "direction", "class", "classDef", "style",
        "actron_state_0", "actron_state_1", "actron_state_2",
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
            is FinalState -> node.copy(parent = parent)
            is CompoundState -> node.copy(parent = parent)
            is ParallelState -> node.copy(parent = parent)
            is HistoryState -> node.copy(parent = parent ?: node.parent)
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

    /** Ids of generated history states: disjoint from [allIds], some needing a Mermaid alias. */
    val historyIds: List<StateId> = listOf("H", "H*", "hist", "deep history", "Chat.H", "actron_state_3", "history", "end_h").map(::StateId)

    /**
     * Adds random [HistoryState]s to a well-formed [hierarchicalChart] or [parallelChart]: each
     * compound or parallel state gets
     * none, one or two (shallow or deep, with no default, or a valid one: a child for shallow, any
     * descendant for deep), inserted at random positions. Then random transitions are pointed at
     * them: some existing targets are replaced, and new ones are added from random states, so
     * walks leave a compound state and come back through its history.
     */
    fun withHistory(random: Random, chart: StateChartDefinition): StateChartDefinition {
        val compounds = chart.states.filter { it is CompoundState || it is ParallelState }
        val pool = historyIds.shuffled(random).toMutableList()
        val histories = mutableListOf<HistoryState>()
        for (compound in compounds) {
            repeat(listOf(0, 1, 1, 2).random(random)) {
                val id = pool.removeFirstOrNull() ?: return@repeat
                val deep = random.nextBoolean()
                val options = chart.states.filter { if (deep) chart.isDescendant(it.id, compound.id) else it.parent == compound.id }
                val default = if (random.nextInt(3) == 0) null else options.random(random).id
                histories += HistoryState(id, parent = compound.id, deep = deep, default = default)
            }
        }
        if (histories.isEmpty()) return chart
        val states = chart.states.toMutableList()
        for (history in histories) states.add(random.nextInt(states.size + 1), history)
        val ids = chart.states.map { it.id }
        val transitions = chart.transitions.map {
            if (random.nextInt(4) == 0) it.copy(target = histories.random(random).id) else it
        }.toMutableList()
        repeat(random.nextInt(1, 6)) {
            val history = histories.random(random)
            val source = if (random.nextBoolean()) ids.random(random) else chart.states.filter { chart.isDescendant(it.id, history.parent) }.random(random).id
            transitions.add(random.nextInt(transitions.size + 1), Transition(source, history.id, matchers.random(random)))
        }
        return chart.copy(states = states, transitions = transitions)
    }

    /**
     * Breaks the history states of [chart] (adding one when it has none) in one to three random
     * ways: a child of a history state, a history state under an atomic or an undeclared parent, a
     * default outside its parent (or a history state as default), a transition from a history
     * state, a history state as a compound's or the chart's initial state.
     */
    fun malformedHistory(random: Random, chart: StateChartDefinition): StateChartDefinition {
        var states = chart.states
        var transitions = chart.transitions
        var initial = chart.initial
        fun histories() = states.filterIsInstance<HistoryState>()
        if (histories().isEmpty()) {
            val compound = states.filterIsInstance<CompoundState>().randomOrNull(random)
                ?: CompoundState(StateId("box"), initial = StateId("inner")).also { states = states + it + AtomicState(StateId("inner"), parent = it.id) }
            states = states + HistoryState(StateId("added history"), parent = compound.id, deep = random.nextBoolean())
        }
        fun replace(old: StateNode, new: StateNode) {
            states = states.toMutableList().also { it[it.indexOf(old)] = new }
        }
        repeat(random.nextInt(1, 4)) { round ->
            val history = histories().random(random)
            when (random.nextInt(7)) {
                0 -> states = states + AtomicState(StateId("child $round"), parent = history.id)
                1 -> states.filterIsInstance<AtomicState>().randomOrNull(random)?.let { states = states + HistoryState(StateId("atomic h $round"), parent = it.id) }
                2 -> states = states + HistoryState(StateId("orphan h $round"), parent = StateId("ghost $round"), deep = random.nextBoolean())
                3 -> replace(history, history.copy(default = (states.map { it.id } + StateId("ghost default")).random(random)))
                4 -> transitions = transitions + Transition(history.id, states.random(random).id, matchers.random(random))
                5 -> (states.firstOrNull { it.id == history.parent } as? CompoundState)?.let { replace(it, it.copy(initial = history.id)) }
                else -> initial = history.id
            }
        }
        return StateChartDefinition(initial, states, transitions)
    }

    /**
     * A random well-formed tree of atomic, compound and [ParallelState]s at most [maxDepth] levels
     * deep, built top-down so every compound state has one to three children (one of them its
     * initial state) and every parallel state two or three regions (mostly compound, sometimes
     * atomic or parallel). The first top-level node is usually parallel. Nodes are declared in
     * shuffled order. Transitions are random, with self-loops, duplicates and guards, and some
     * groups share one matcher across the regions of a parallel state, so one action fires in
     * several regions at once. The initial state is usually a top-level node, sometimes a nested
     * one, whose parallel ancestors then enter their other regions too.
     *
     * Sizes are capped ([maxStates] is a soft limit: children a container needs are added anyway)
     * so the configuration graph, which multiplies the regions' configurations, stays small.
     */
    fun parallelChart(random: Random, maxStates: Int = 13, maxDepth: Int = 4, maxTransitions: Int = 18): StateChartDefinition {
        val pool = allIds.shuffled(random).toMutableList()
        val nodes = mutableListOf<StateNode>()
        fun build(parent: StateId?, depth: Int, kind: Int): StateId {
            val id = pool.removeAt(0)
            val container = depth < maxDepth - 1 && pool.size > 3
            val room = { allIds.size - pool.size < maxStates }
            when {
                container && kind == 1 -> {
                    val children = List(random.nextInt(1, 4)) { build(id, depth + 1, if (room()) random.nextInt(6) else 0) }
                    nodes += CompoundState(id, initial = children.random(random), parent = parent)
                }
                container && kind == 2 -> {
                    repeat(random.nextInt(2, 4)) { build(id, depth + 1, if (room()) listOf(0, 1, 1, 1, 2).random(random) else 0) }
                    nodes += ParallelState(id, parent = parent)
                }
                else -> nodes += AtomicState(id, parent = parent)
            }
            return id
        }
        build(null, 0, if (random.nextInt(5) == 0) 1 else 2)
        repeat(random.nextInt(0, 3)) { if (pool.size > 3) build(null, 0, random.nextInt(3)) }
        val shuffled = nodes.shuffled(random)
        val ids = shuffled.map { it.id }

        val transitions = mutableListOf<Transition>()
        repeat(random.nextInt(0, maxTransitions + 1)) {
            val roll = random.nextInt(10)
            transitions += when {
                roll == 0 && transitions.isNotEmpty() -> transitions.random(random)
                roll == 1 -> ids.random(random).let { Transition(it, it, matchers.random(random)) }
                else -> Transition(ids.random(random), ids.random(random), matchers.random(random), if (random.nextInt(3) == 0) guards.random(random) else null)
            }
        }
        val parallels = shuffled.filterIsInstance<ParallelState>()
        fun inside(id: StateId): List<StateId> = shuffled.filter { n -> generateSequence(n.parent) { p -> shuffled.first { it.id == p }.parent }.any { it == id } }.map { it.id }
        repeat(if (parallels.isEmpty()) 0 else random.nextInt(1, 5)) {
            val parallel = parallels.random(random)
            val matcher = matchers.random(random)
            for (region in shuffled.filter { it.parent == parallel.id }.shuffled(random).take(random.nextInt(2, 4))) {
                val within = inside(region.id)
                if (within.isEmpty()) continue
                val transition = Transition(within.random(random), within.random(random), matcher, if (random.nextInt(4) == 0) guards.random(random) else null)
                transitions.add(random.nextInt(transitions.size + 1), transition)
            }
        }
        val roots = shuffled.filter { it.parent == null }.map { it.id }
        val initial = if (random.nextInt(5) == 0) ids.random(random) else roots.random(random)
        return StateChartDefinition(initial, shuffled, transitions)
    }

    /**
     * Breaks the parallel states of [chart] (adding one when it has none) in one to three random
     * ways: a parallel state with no region or with one, a compound state turned parallel (keeping
     * its children), a history state of a parallel state with a default outside it or on a
     * grandchild while shallow, a node moved under an atomic region.
     */
    fun malformedParallel(random: Random, chart: StateChartDefinition): StateChartDefinition {
        var states = chart.states
        fun parallels() = states.filterIsInstance<ParallelState>()
        if (parallels().isEmpty()) {
            states = states + ParallelState(StateId("pair")) + AtomicState(StateId("left"), StateId("pair")) + AtomicState(StateId("right"), StateId("pair"))
        }
        repeat(random.nextInt(1, 4)) { round ->
            val parallel = parallels().random(random)
            when (random.nextInt(5)) {
                0 -> states = states + ParallelState(StateId("bare $round"), parent = parallel.id.takeIf { random.nextBoolean() })
                1 -> states = states + ParallelState(StateId("single $round")) + AtomicState(StateId("only $round"), StateId("single $round"))
                2 -> states.filterIsInstance<CompoundState>().randomOrNull(random)?.let { c ->
                    states = states.map { if (it == c) ParallelState(c.id, c.parent) else it }
                }
                3 -> {
                    val grandchildren = states.filter { n -> n.parent != null && states.firstOrNull { it.id == n.parent }?.parent == parallel.id }
                    val default = (grandchildren.map { it.id } + StateId("ghost default")).random(random)
                    states = states + HistoryState(StateId("bad h $round"), parent = parallel.id, deep = false, default = default)
                }
                else -> states.filter { it is AtomicState && it.parent == parallel.id }.randomOrNull(random)?.let { atomic ->
                    states = states + AtomicState(StateId("under atomic $round"), parent = atomic.id)
                }
            }
        }
        return chart.copy(states = states)
    }

    /** Positive delays for generated timers; some equal, so equal-delay timers occur. */
    val delays: List<Duration> = listOf(500.milliseconds, 1.seconds, 3.seconds, 5.seconds, 90.seconds, 2.minutes)

    /**
     * Adds random timers ([Trigger.After]) to [chart]: one to eight, from random declared states
     * (history states excluded) to random declared states (history states included), with random
     * positive delays, some self-loops, some guarded, some with an effect label, and some added
     * twice to one source with one delay; inserted at random positions among the transitions.
     */
    fun withTimers(random: Random, chart: StateChartDefinition): StateChartDefinition {
        val sources = chart.states.filter { it !is HistoryState }.map { it.id }
        if (sources.isEmpty()) return chart
        val targets = chart.states.map { it.id }
        val transitions = chart.transitions.toMutableList()
        repeat(random.nextInt(1, 9)) {
            val source = sources.random(random)
            val target = if (random.nextInt(5) == 0) source else targets.random(random)
            val guard = if (random.nextInt(3) == 0) guards.random(random) else null
            val effect = if (random.nextInt(4) == 0) "effect ${random.nextInt(3)}" else null
            val timer = Transition(source, target, Trigger.After(delays.random(random)), guard, effect)
            transitions.add(random.nextInt(transitions.size + 1), timer)
            if (random.nextInt(8) == 0) {
                val twin = Transition(source, targets.random(random), timer.trigger)
                transitions.add(random.nextInt(transitions.size + 1), twin)
            }
        }
        return chart.copy(transitions = transitions)
    }

    /**
     * Breaks the timers of [chart] (adding one when it has none) in one to three random ways: a
     * zero or negative delay, a timer from a history state or an undeclared state, a timer into an
     * undeclared state.
     */
    fun malformedTimers(random: Random, chart: StateChartDefinition): StateChartDefinition {
        var transitions = chart.transitions
        val ids = chart.states.map { it.id }
        if (transitions.none { it.isTimer }) transitions = transitions + Transition(ids.random(random), ids.random(random), Trigger.After(1.seconds))
        repeat(random.nextInt(1, 4)) { round ->
            val timer = transitions.filter { it.isTimer }.random(random)
            val broken = when (random.nextInt(4)) {
                0 -> timer.copy(trigger = Trigger.After(listOf(Duration.ZERO, (-1).seconds, (-5).minutes).random(random)))
                1 -> chart.states.filterIsInstance<HistoryState>().randomOrNull(random)?.let { timer.copy(source = it.id) } ?: timer.copy(source = StateId("ghost $round"))
                2 -> timer.copy(source = StateId("ghost $round"))
                else -> timer.copy(target = StateId("ghost $round"))
            }
            transitions = transitions.toMutableList().also { it.add(random.nextInt(it.size + 1), broken) }
        }
        return chart.copy(transitions = transitions)
    }

    /**
     * [parallelChart] with [withHistory] and [withTimers], for the first [count] seeds. Inline, so
     * suspending tests (Store walks under virtual time) can suspend in [block].
     */
    inline fun forEachTimerChart(count: Int = seeds.size, block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds.take(count)) {
            val random = Random(seed)
            val base = if (seed % 3 == 0) hierarchicalChart(random) else parallelChart(random)
            block(seed, random, withTimers(random, withHistory(random, base)))
        }
    }

    /** [parallelChart] with [withHistory], for the first [count] seeds. */
    fun forEachParallelChart(count: Int = seeds.size, block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds.take(count)) {
            val random = Random(seed)
            block(seed, random, withHistory(random, parallelChart(random)))
        }
    }

    /** [hierarchicalChart] with [withHistory], for the first [count] seeds. */
    fun forEachHistoryChart(count: Int = seeds.size, block: (seed: Int, random: Random, chart: StateChartDefinition) -> Unit) {
        for (seed in seeds.take(count)) {
            val random = Random(seed)
            block(seed, random, withHistory(random, hierarchicalChart(random)))
        }
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
