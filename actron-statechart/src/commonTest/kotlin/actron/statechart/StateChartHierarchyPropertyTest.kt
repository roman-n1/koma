package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property-based tests for compound states over [RandomCharts.hierarchicalChart]: random trees up
 * to four levels deep, declared in shuffled order, with transitions between any nodes (compound
 * ones included), self-loops, duplicates and guards; and [RandomCharts.malformed] variants.
 *
 * The reference ([HierarchyReference]) is a naive re-statement of the design note's LCCA semantics over
 * plain parent maps: walk up from the leaf, scan all transitions for each node, compute the LCCA
 * from ancestor chains, exit every active proper descendant of it deepest first, enter the chain
 * below it and then the initial children recursively. Paths and reachability are checked against
 * a Bellman-Ford style relaxation over configurations.
 *
 * ```
 * [*] --> P
 * state P {
 *     [*] --> A
 *     A --Ping--> B            LCCA P: exit A, enter B
 *     state B {
 *         [*] --> B1
 *         B1 --Go [even]--> P  to an ancestor: exit B1, B, P; enter P, A
 *     }
 * }
 * P --Pong--> P                external self-loop: exits and re-enters P
 * B --Ping--> Q                inner B1 has no Ping, so B's transition fires
 * ```
 */
@OptIn(ExperimentalActronApi::class)
class StateChartHierarchyPropertyTest {

    // region reference

    /** A random truth value per guard label and action, recording every call. */
    private class GuardTable(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        val calls = mutableListOf<Pair<String, Action>>()

        fun holds(label: String, action: RandomAction): Boolean = table.getValue(label).getValue(action)

        fun implementations(): Map<String, (RandomState, Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { _: RandomState, action: Action -> calls += label to action; holds(label, action as RandomAction) }
        }
    }

    private fun Fired.asResult() = StepResult.Transitioned(listOf(transition), exited, entered, StateConfiguration(after))

    /**
     * Checks the invariants of one step from [before]: the new configuration is a tree-closed chain
     * with one active child per active compound, listed outermost first; exited and entered are
     * ordered innermost-first and outermost-first and overlap only in nodes active before and after.
     */
    private fun checkStep(seed: Int, chart: StateChartDefinition, before: StateConfiguration, result: StepResult.Transitioned) {
        val after = result.configuration
        checkConfiguration(seed, chart, after)
        assertEquals(1, result.transitions.size, "seed $seed")
        assertTrue(result.transition.source in before.active, "seed $seed: source not active")
        assertTrue(result.transition.target in result.entered, "seed $seed: target not entered")
        assertEquals(before.active - result.exited.toSet() + result.entered, after.active, "seed $seed")
        assertTrue(before.active.containsAll(result.exited), "seed $seed")
        assertTrue(after.active.containsAll(result.entered), "seed $seed")
        val both = result.exited.toSet() intersect result.entered.toSet()
        assertEquals(both, both.filter { it in before.active && it in after.active }.toSet(), "seed $seed")
        assertTrue((before.active intersect after.active).all { it in both || it !in result.exited }, "seed $seed")
        assertTrue((result.entered - result.exited.toSet()).none { it in before.active }, "seed $seed: entered an active node without exiting it")
        for (i in result.exited.indices) {
            for (j in i + 1 until result.exited.size) {
                assertTrue(!chart.isDescendant(result.exited[j], result.exited[i]), "seed $seed: exit order ${result.exited}")
            }
        }
        for (i in result.entered.indices) {
            for (j in i + 1 until result.entered.size) {
                assertTrue(!chart.isDescendant(result.entered[i], result.entered[j]), "seed $seed: entry order ${result.entered}")
            }
        }
        // Entry starts right below a node that stayed active (or at the top).
        val top = result.entered.first()
        val topParent = chart.node(top)?.parent
        assertTrue(topParent == null || (topParent in after.active && topParent !in result.entered), "seed $seed")
        assertEquals(result.exited.distinct(), result.exited, "seed $seed")
        assertEquals(result.entered.distinct(), result.entered, "seed $seed")
    }

    private fun checkConfiguration(seed: Int, chart: StateChartDefinition, configuration: StateConfiguration) {
        val active = configuration.active
        assertEquals(1, active.count { chart.node(it)?.parent == null }, "seed $seed: $active")
        for (id in active) {
            val node = assertNotNull(chart.node(id), "seed $seed")
            node.parent?.let { assertTrue(it in active, "seed $seed: $id active without its parent") }
            val activeChildren = chart.childrenOf(id).count { it.id in active }
            assertEquals(if (node is CompoundState) 1 else 0, activeChildren, "seed $seed: $id has $activeChildren active children")
        }
        val leaves = chart.activeLeaves(configuration)
        assertEquals(1, leaves.size, "seed $seed")
        assertTrue(chart.node(leaves.single()) is AtomicState, "seed $seed")
        assertEquals(chart.configurationOf(leaves.single()), configuration, "seed $seed")
        assertEquals((chart.ancestorsOf(leaves.single()).reversed() + leaves.single()), active.toList(), "seed $seed: not outermost first")
        assertEquals(emptyMap(), configuration.history, "seed $seed")
    }

    // endregion

    // region generator

    @Test
    fun generatorProducesDeepWellFormedTrees() {
        var deep = 0
        var nestedInitial = 0
        var compoundTargets = 0
        RandomCharts.forEachHierarchicalChart { seed, _, chart ->
            assertEquals(emptyList(), chart.validate().filter { it.isHierarchyIssue() }, "seed $seed")
            val depth = chart.states.maxOf { chart.ancestorsOf(it.id).size } + 1
            assertTrue(depth <= 4, "seed $seed: depth $depth")
            if (depth >= 3) deep++
            if (chart.node(chart.initial)?.parent != null) nestedInitial++
            if (chart.transitions.any { chart.node(it.target) is CompoundState }) compoundTargets++
        }
        assertTrue(deep >= 100, "only $deep charts 3+ levels deep")
        assertTrue(nestedInitial >= 10, "only $nestedInitial charts with a nested initial state")
        assertTrue(compoundTargets >= 100, "only $compoundTargets charts with a compound target")
    }

    private fun ValidationIssue.isHierarchyIssue() = this is ValidationIssue.UnknownParent || this is ValidationIssue.AtomicParent ||
        this is ValidationIssue.ParentCycle || this is ValidationIssue.InitialNotChild || this is ValidationIssue.EmptyCompoundState

    // endregion

    // region runtime

    @Test
    fun initialConfigurationEqualsTheReference() = RandomCharts.forEachHierarchicalChart { seed, random, chart ->
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, GuardTable(random).implementations())
        val initial = runtime.initialConfiguration()
        assertEquals(HierarchyReference(chart).initialConfiguration(), initial.active, "seed $seed")
        checkConfiguration(seed, chart, initial)
    }

    @Test
    fun randomWalksAgreeWithTheReferenceAndKeepTheInvariants() {
        var steps = 0
        var reentered = 0
        var outerWins = 0
        RandomCharts.forEachHierarchicalChart { seed, random, chart ->
            val table = GuardTable(random)
            val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())
            val reference = HierarchyReference(chart)
            val atomic = chart.states.filterIsInstance<AtomicState>().map { it.id }
            var configuration = runtime.initialConfiguration()
            repeat(40) {
                if (random.nextInt(10) == 0) configuration = chart.configurationOf(atomic.random(random))
                val leaf = chart.activeLeaves(configuration).single()
                val action = RandomCharts.actions.random(random)
                table.calls.clear()
                val result = runtime.step(configuration, RandomState(leaf), action)
                assertEquals(reference.expectedGuardCalls(configuration.active, action, table::holds), table.calls, "seed $seed: guard calls")
                val expected = reference.step(configuration.active, action, table::holds)
                assertEquals(expected?.asResult() ?: StepResult.Ignored, result, "seed $seed: $leaf on $action")
                // The leaf-only overload derives the same configuration.
                table.calls.clear()
                assertEquals(result, runtime.step(RandomState(leaf), action), "seed $seed")
                if (result is StepResult.Transitioned) {
                    checkStep(seed, chart, configuration, result)
                    steps++
                    if (result.exited.any { it in result.entered }) reentered++
                    if (result.transition.source != leaf) outerWins++
                    configuration = result.configuration
                }
            }
        }
        assertTrue(steps >= 3000, "only $steps steps")
        assertTrue(reentered >= 300, "only $reentered steps re-entering a node")
        assertTrue(outerWins >= 300, "only $outerWins steps taken from an ancestor of the leaf")
    }

    @Test
    fun flatChartsGiveTheFlatResultThroughEveryOverload() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())
        assertEquals(StateConfiguration(setOf(chart.initial)), runtime.initialConfiguration(), "seed $seed")
        for (id in chart.states.map { it.id }) {
            for (action in RandomCharts.actions) {
                val flat = chart.transitionsFrom(id).firstOrNull { HierarchyReference.matches(it.on, action) && (it.guard == null || table.holds(it.guard, action)) }
                val expected = flat?.let { StepResult.Transitioned(it) } ?: StepResult.Ignored
                assertEquals(expected, runtime.step(RandomState(id), action), "seed $seed")
                assertEquals(expected, runtime.step(StateConfiguration(setOf(id)), RandomState(id), action), "seed $seed")
            }
        }
    }

    @Test
    fun runtimeAcceptsExactlyTheWellFormedHierarchies() {
        var rejected = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformed(random, RandomCharts.hierarchicalChart(random))
            val issues = chart.validate().filter { it.isHierarchyIssue() }
            val guards = RandomCharts.guards.associateWith { { _: RandomState, _: Action -> true } }
            if (issues.isEmpty()) {
                StateChartRuntime(chart, { s: RandomState -> s.id }, guards)
            } else {
                rejected++
                val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartRuntime(chart, { s: RandomState -> s.id }, guards) }
                assertEquals("[Actron] Malformed state hierarchy: ${issues.joinToString()}", error.message, "seed $seed")
            }
        }
        assertTrue(rejected >= 250, "only $rejected malformed charts")
    }

    // endregion

    // region validation

    private fun referenceHierarchyIssues(chart: StateChartDefinition): List<ValidationIssue> {
        val first = LinkedHashMap<StateId, StateNode>()
        chart.states.forEach { if (it.id !in first) first[it.id] = it }
        val nodes = first.values.toList()
        val issues = mutableListOf<ValidationIssue>()
        for (node in nodes) {
            val parent = node.parent ?: continue
            if (parent !in first) issues += ValidationIssue.UnknownParent(node.id, parent)
            else if (first[parent] is AtomicState) issues += ValidationIssue.AtomicParent(node.id, parent)
        }
        val onReportedCycle = mutableSetOf<StateId>()
        for (node in nodes) {
            // On a cycle iff following parents returns to the node.
            var p = node.parent
            var steps = 0
            while (p != null && p != node.id && steps <= nodes.size) {
                p = first[p]?.parent
                steps++
            }
            if (p != node.id || node.id in onReportedCycle) continue
            val cycle = mutableListOf(node.id)
            var next = node.parent!!
            while (next != node.id) {
                cycle += next
                next = first.getValue(next).parent!!
            }
            onReportedCycle += cycle
            issues += ValidationIssue.ParentCycle(cycle)
        }
        for (node in nodes) {
            if (node is CompoundState && first[node.initial]?.parent != node.id) issues += ValidationIssue.InitialNotChild(node.id, node.initial)
        }
        for (node in nodes) {
            if (node is CompoundState && nodes.none { it.parent == node.id }) issues += ValidationIssue.EmptyCompoundState(node.id)
        }
        return issues
    }

    @Test
    fun hierarchyIssuesEqualTheReferenceAndSitBetweenInitialAndEndpointIssues() {
        val kinds = mutableSetOf<String>()
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformed(random, RandomCharts.hierarchicalChart(random))
            val issues = chart.validate()
            val hierarchy = issues.filter { it.isHierarchyIssue() }
            assertEquals(referenceHierarchyIssues(chart), hierarchy, "seed $seed")
            hierarchy.forEach { kinds += it::class.simpleName!! }
            if (hierarchy.isNotEmpty()) {
                val firstIndex = issues.indexOf(hierarchy.first())
                assertEquals(hierarchy, issues.subList(firstIndex, firstIndex + hierarchy.size), "seed $seed: hierarchy issues are contiguous")
                assertTrue(
                    issues.take(firstIndex).all { it is ValidationIssue.DuplicateStateId || it is ValidationIssue.UnknownInitialState },
                    "seed $seed",
                )
            }
        }
        assertEquals(5, kinds.size, "kinds seen: $kinds")
    }

    @Test
    fun reachableStatesEqualTheReferenceOnWellFormedCharts() = RandomCharts.forEachHierarchicalChart { seed, _, chart ->
        val reference = HierarchyReference(chart).nodeDistances()
        assertEquals(reference.keys, chart.reachableStates(), "seed $seed")
        assertEquals(
            chart.states.map { it.id }.distinct().filter { it !in reference }.map { ValidationIssue.UnreachableState(it) },
            chart.validate().filterIsInstance<ValidationIssue.UnreachableState>(),
            "seed $seed",
        )
        // Breadth-first: distances along the returned order never decrease.
        val order = chart.reachableStates().map { reference.getValue(it) }
        assertEquals(order.sorted(), order, "seed $seed")
    }

    @Test
    fun malformedChartsStillValidateExportAndSearchWithoutLooping() {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformed(random, RandomCharts.hierarchicalChart(random))
            val reachable = chart.reachableStates()
            assertTrue(chart.initial in reachable, "seed $seed")
            for (id in reachable) assertNotNull(chart.shortestPathTo(id), "seed $seed")
            val paths = chart.transitionCoveragePaths()
            assertEquals(
                chart.transitions.filter { it.source in reachable }.toSet(),
                paths.flatMap { it.transitions }.toSet(),
                "seed $seed",
            )
            assertEquals(chart.toMermaid(), chart.toMermaid(), "seed $seed")
            checkMermaidStructure(seed, chart, wellFormed = false)
        }
    }

    // endregion

    // region paths

    @Test
    fun shortestPathsHaveTheReferenceLengthAndReplayThroughTheReference() = RandomCharts.forEachHierarchicalChart { seed, _, chart ->
        val reference = HierarchyReference(chart)
        val distances = reference.nodeDistances()
        val start = reference.leaf(reference.initialConfiguration())
        for (id in RandomCharts.allIds) {
            val path = chart.shortestPathTo(id)
            val distance = distances[id]
            if (distance == null) {
                assertNull(path, "seed $seed: $id never becomes active")
                continue
            }
            assertNotNull(path, "seed $seed: $id")
            assertEquals(distance, path.transitions.size, "seed $seed: $id")
            assertEquals(start, path.start, "seed $seed")
            val end = replay(seed, reference, path)
            assertTrue(id in end, "seed $seed: $id not active at the end of $path")
        }
    }

    /** Replays [path] with the reference; returns the final configuration. */
    private fun replay(seed: Int, reference: HierarchyReference, path: StateChartPath): Set<StateId> {
        var configuration = reference.initialConfiguration()
        path.transitions.forEachIndexed { i, t ->
            assertTrue(t.source in configuration, "seed $seed: ${t.source} is not active before step $i")
            configuration = reference.fire(configuration, t).after
            assertEquals(reference.leaf(configuration), path.leaves[i], "seed $seed: leaf after step $i")
        }
        assertEquals(path.leaves.lastOrNull() ?: path.start, path.end, "seed $seed")
        return configuration
    }

    @Test
    fun coveragePathsCoverEveryTransitionWhoseSourceCanBeActive() = RandomCharts.forEachHierarchicalChart { seed, _, chart ->
        val reference = HierarchyReference(chart)
        val reachable = reference.nodeDistances().keys
        val paths = chart.transitionCoveragePaths()
        assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), paths.flatMap { it.transitions }.toSet(), "seed $seed")
        for (path in paths) {
            replay(seed, reference, path)
            // Shortest path to the last transition's source, then that transition.
            assertEquals(chart.shortestPathTo(path.transitions.last().source)!!.transitions, path.transitions.dropLast(1), "seed $seed")
        }
        for ((i, p) in paths.withIndex()) {
            for ((j, q) in paths.withIndex()) {
                if (i != j) assertTrue(q.transitions.size < p.transitions.size || q.transitions.subList(0, p.transitions.size) != p.transitions, "seed $seed")
            }
        }
        val order = paths.map { chart.transitions.indexOf(it.transitions.last()) }
        assertEquals(order.sorted().distinct(), order, "seed $seed")
    }

    // endregion

    // region mermaid

    private class Block(val ref: String?) {
        var initial: String? = null
        val declared = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        val transitions = mutableListOf<String>()
    }

    private val declarationLine = Regex("^state \"([^\"]*)\" as ([A-Za-z_][A-Za-z0-9_]*)$")
    private val aliasedBlockLine = Regex("^state \"([^\"]*)\" as ([A-Za-z_][A-Za-z0-9_]*) \\{$")
    private val plainBlockLine = Regex("^state ([A-Za-z_][A-Za-z0-9_]*) \\{$")
    private val plainLine = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Parses the block structure; returns the top block and the label of every alias. */
    private fun parseBlocks(seed: Int, text: String): Pair<Block, Map<String, String>> {
        val lines = text.lines()
        assertEquals("stateDiagram-v2", lines.first(), "seed $seed")
        val top = Block(null)
        val stack = ArrayDeque(listOf(top))
        val labels = mutableMapOf<String, String>()
        for (raw in lines.drop(1)) {
            val indent = "    ".repeat(stack.size)
            if (raw == "    ".repeat(stack.size - 1) + "}") {
                stack.removeLast()
                continue
            }
            assertTrue(raw.startsWith(indent) && !raw[indent.length].isWhitespace(), "seed $seed: bad indent in '$raw'")
            val line = raw.removePrefix(indent)
            val block = stack.last()
            when {
                line.startsWith("[*] --> ") -> {
                    assertNull(block.initial, "seed $seed")
                    block.initial = line.removePrefix("[*] --> ")
                }
                aliasedBlockLine.matches(line) -> aliasedBlockLine.matchEntire(line)!!.groupValues.let { (_, label, ref) ->
                    labels[ref] = label
                    Block(ref).also { block.blocks += it; stack.addLast(it) }
                }
                plainBlockLine.matches(line) -> Block(plainBlockLine.matchEntire(line)!!.groupValues[1]).also { block.blocks += it; stack.addLast(it) }
                declarationLine.matches(line) -> declarationLine.matchEntire(line)!!.groupValues.let { (_, label, ref) ->
                    labels[ref] = label
                    block.declared += ref
                }
                plainLine.matches(line) -> block.declared += line
                else -> block.transitions += line
            }
        }
        assertEquals(1, stack.size, "seed $seed: unbalanced braces")
        return top to labels
    }

    private fun Block.all(): List<Block> = listOf(this) + blocks.flatMap { it.all() }

    private val keywords = setOf("state", "note", "direction", "class", "classdef", "style", "scale", "hide", "end", "click", "acctitle", "accdescr")

    private fun checkMermaidStructure(seed: Int, chart: StateChartDefinition, wellFormed: Boolean) {
        val (top, labels) = parseBlocks(seed, chart.toMermaid())
        val first = LinkedHashMap<StateId, StateNode>().also { map -> chart.states.forEach { if (it.id !in map) map[it.id] = it } }
        val ids = (chart.states.map { it.id } + chart.initial + first.values.filterIsInstance<CompoundState>().map { it.initial } +
            chart.transitions.flatMap { listOf(it.source, it.target) }).distinct()
        val plain = ids.filter { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it.value) && it.value.lowercase() !in keywords }
        assertEquals(ids.size - plain.size, labels.size, "seed $seed: every other id is aliased once")
        assertTrue(labels.keys.none { alias -> plain.any { it.value == alias } || alias.lowercase() in keywords }, "seed $seed")
        val refOf = plain.associateWith { it.value } + labels.entries.associate { (ref, label) -> ids.single { it.value.replace("\"", "'") == label && it !in plain } to ref }
        val idOf = refOf.entries.associate { (id, ref) -> ref to id }

        val blocks = top.all()
        // Every compound state is exactly one block; every declared state appears exactly once.
        val compounds = first.values.filterIsInstance<CompoundState>().map { refOf.getValue(it.id) }
        assertEquals(compounds.sorted(), blocks.drop(1).map { it.ref!! }.sorted(), "seed $seed")
        val appearances = blocks.drop(1).map { it.ref!! } + blocks.flatMap { it.declared }
        assertEquals(appearances.distinct(), appearances, "seed $seed")
        assertEquals(refOf.getValue(chart.initial), top.initial, "seed $seed")
        for (block in blocks.drop(1)) {
            val node = first.getValue(idOf.getValue(block.ref!!)) as CompoundState
            assertEquals(refOf.getValue(node.initial), block.initial, "seed $seed")
        }
        // Transitions: each once, in declaration order within its block.
        val all = blocks.flatMap { b -> b.transitions.map { b.ref to it } }
        assertEquals(chart.transitions.size, all.size, "seed $seed")
        fun line(t: Transition) = "${refOf.getValue(t.source)} --> ${refOf.getValue(t.target)} : ${t.on!!.name}" + (t.guard?.let { " [$it]" } ?: "")
        assertEquals(chart.transitions.map(::line).sorted(), all.map { it.second }.sorted(), "seed $seed")
        if (!wellFormed) return

        for (block in blocks) {
            val parent = block.ref?.let { idOf.getValue(it) }
            val children = first.values.filter { it.parent == parent }
            // Inside a compound every child is declared; at the top only aliased atomic ones are.
            val expectedDeclared = children.filter { it !is CompoundState && (parent != null || refOf.getValue(it.id) != it.id.value) }.map { refOf.getValue(it.id) } +
                (if (parent == null) ids.filter { it !in first && it !in plain }.map { refOf.getValue(it) } else emptyList())
            assertEquals(expectedDeclared.sorted(), block.declared.sorted(), "seed $seed: block $parent")
            assertEquals(children.filterIsInstance<CompoundState>().map { refOf.getValue(it.id) }, block.blocks.map { it.ref }, "seed $seed")
            // A transition sits in the innermost common proper ancestor of its endpoints.
            val expected = chart.transitions.filter { t ->
                val targetAncestors = chart.ancestorsOf(t.target)
                chart.ancestorsOf(t.source).firstOrNull { it in targetAncestors } == parent
            }.map(::line)
            assertEquals(expected, block.transitions, "seed $seed: transitions of block $parent")
        }
    }

    @Test
    fun mermaidNestsEveryStateInItsParentWithSafeAliases() = RandomCharts.forEachHierarchicalChart { seed, _, chart ->
        checkMermaidStructure(seed, chart, wellFormed = true)
    }

    // endregion
}
