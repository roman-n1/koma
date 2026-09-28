package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Property-based tests for the model, [validate], [reachableStates] and [toMermaid] over
 * [RandomCharts]: a few hundred seeded charts with random state counts, self-loops, duplicate
 * transitions, guards, unreachable islands and ids that Mermaid cannot use as they are.
 *
 * Each property compares the library with an independent reference (fixed-point iteration,
 * naive loops) or checks a metamorphic relation (renaming, shuffling, adding an island,
 * appending a transition). A typical generated chart:
 *
 * ```
 * [*] --> "Not signed in"
 * "Not signed in" --Ping--> "Not signed in"        (self-loop)
 * "Not signed in" --Go [even]--> koma_state_1      (id that looks like an exporter alias)
 * "Not signed in" --Go--> state                    (Mermaid keyword)
 * state --Go--> state, state --Go--> state         (duplicate, ambiguous)
 * Idle --Reset--> 状態                              (unreachable island)
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartModelPropertyTest {

    // region references

    private fun fixedPointReachable(chart: StateChartDefinition): Set<StateId> {
        var reachable = setOf(chart.initial)
        while (true) {
            val next = reachable + chart.transitions.filter { it.source in reachable }.map { it.target }
            if (next == reachable) return reachable
            reachable = next
        }
    }

    private fun naiveMatches(matcher: ActionMatcher, action: Action): Boolean = when (val type = matcher.type) {
        null -> action::class.simpleName == matcher.name
        else -> type.isInstance(action)
    }

    private fun referenceIssues(chart: StateChartDefinition, samples: List<Action> = emptyList()): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val ids = chart.states.map { it.id }
        val distinctIds = mutableListOf<StateId>()
        for (id in ids) {
            if (id in distinctIds) continue
            distinctIds += id
            if (ids.count { it == id } > 1) issues += ValidationIssue.DuplicateStateId(id)
        }
        val initialDeclared = chart.initial in distinctIds
        if (!initialDeclared) issues += ValidationIssue.UnknownInitialState(chart.initial)
        for (t in chart.transitions) {
            if (t.source !in distinctIds) issues += ValidationIssue.UnknownTransitionSource(t)
            if (t.target !in distinctIds) issues += ValidationIssue.UnknownTransitionTarget(t)
        }
        if (initialDeclared) {
            val reachable = fixedPointReachable(chart)
            for (id in distinctIds) if (id !in reachable) issues += ValidationIssue.UnreachableState(id)
        }
        val keys = mutableListOf<Pair<StateId, ActionMatcher>>()
        for (t in chart.transitions) if (t.guard == null && (t.source to t.on) !in keys) keys += t.source to t.on
        for ((source, on) in keys) {
            val group = chart.transitions.filter { it.guard == null && it.source == source && it.on == on }
            if (group.size > 1) issues += ValidationIssue.AmbiguousTransitions(source, on, group)
        }
        val sources = mutableListOf<StateId>()
        for (t in chart.transitions) if (t.guard == null && t.source !in sources) sources += t.source
        val distinctSamples = mutableListOf<Action>()
        for (sample in samples) if (sample !in distinctSamples) distinctSamples += sample
        for (source in sources) {
            for (sample in distinctSamples) {
                val group = chart.transitions.filter { it.guard == null && it.source == source && naiveMatches(it.on, sample) }
                if (group.any { it.on != group.first().on }) issues += ValidationIssue.ShadowedTransitions(source, sample, group)
            }
        }
        return issues
    }

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val keywords = setOf(
        "state", "note", "direction", "class", "classdef", "style", "scale", "hide", "end", "click",
        "acctitle", "accdescr",
    )

    private fun needsAlias(id: StateId) = !identifier.matches(id.value) || id.value.lowercase() in keywords

    private fun idsInFirstUseOrder(chart: StateChartDefinition): List<StateId> =
        (chart.states.map { it.id } + chart.initial + chart.transitions.flatMap { listOf(it.source, it.target) }).distinct()

    private fun rename(chart: StateChartDefinition, f: (StateId) -> StateId) = StateChartDefinition(
        initial = f(chart.initial),
        states = chart.states.map { AtomicState(f(it.id)) },
        transitions = chart.transitions.map { it.copy(source = f(it.source), target = f(it.target)) },
    )

    private fun renameIssue(issue: ValidationIssue, f: (StateId) -> StateId): ValidationIssue {
        fun Transition.r() = copy(source = f(source), target = f(target))
        return when (issue) {
            is ValidationIssue.DuplicateStateId -> ValidationIssue.DuplicateStateId(f(issue.id))
            is ValidationIssue.UnknownInitialState -> ValidationIssue.UnknownInitialState(f(issue.id))
            is ValidationIssue.UnknownTransitionSource -> ValidationIssue.UnknownTransitionSource(issue.transition.r())
            is ValidationIssue.UnknownTransitionTarget -> ValidationIssue.UnknownTransitionTarget(issue.transition.r())
            is ValidationIssue.UnreachableState -> ValidationIssue.UnreachableState(f(issue.id))
            is ValidationIssue.AmbiguousTransitions ->
                ValidationIssue.AmbiguousTransitions(f(issue.source), issue.on, issue.transitions.map { it.r() })
            is ValidationIssue.ShadowedTransitions ->
                ValidationIssue.ShadowedTransitions(f(issue.source), issue.sample, issue.transitions.map { it.r() })
        }
    }

    // endregion

    // region reachability

    @Test
    fun reachableStatesEqualsFixedPointIteration() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        val reachable = chart.reachableStates()
        assertEquals(fixedPointReachable(chart), reachable, "seed $seed")
        assertEquals(chart.initial, reachable.first(), "seed $seed: initial comes first")
    }

    @Test
    fun reachableStatesAreClosedUnderTransitionsAndListedInBreadthFirstOrder() =
        RandomCharts.forEachChart(valid = false) { seed, _, chart ->
            val reachable = chart.reachableStates()
            for (t in chart.transitions) {
                if (t.source in reachable) assertTrue(t.target in reachable, "seed $seed: $t leaves the set")
            }
            // BFS order: distances along the returned order never decrease.
            val distance = mutableMapOf(chart.initial to 0)
            var changed = true
            while (changed) {
                changed = false
                for (t in chart.transitions) {
                    val d = distance[t.source] ?: continue
                    if ((distance[t.target] ?: Int.MAX_VALUE) > d + 1) {
                        distance[t.target] = d + 1
                        changed = true
                    }
                }
            }
            val distances = reachable.map { distance.getValue(it) }
            assertEquals(distances.sorted(), distances, "seed $seed")
        }

    @Test
    fun reachabilityIgnoresTransitionOrderAndGuards() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val shuffled = chart.copy(transitions = chart.transitions.shuffled(random).map { it.copy(guard = "g${random.nextInt(3)}") })
        assertEquals(chart.reachableStates(), shuffled.reachableStates().toSet(), "seed $seed")
    }

    // endregion

    // region validation

    @Test
    fun validateEqualsNaiveReference() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        assertEquals(referenceIssues(chart), chart.validate(), "seed $seed\n$chart")
    }

    @Test
    fun validateWithSampleActionsEqualsNaiveReference() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val samples = RandomCharts.actions.shuffled(random).take(random.nextInt(RandomCharts.actions.size + 1))
        // Duplicated samples must not duplicate issues.
        val withDuplicates = samples + samples.take(2)
        assertEquals(referenceIssues(chart, samples), chart.validate(withDuplicates), "seed $seed\n$chart\n$samples")
    }

    @Test
    fun samplesOnlyAddShadowingIssues() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        val without = chart.validate()
        val with = chart.validate(RandomCharts.actions)
        assertTrue(without.none { it is ValidationIssue.ShadowedTransitions }, "seed $seed")
        assertEquals(without, with.filterNot { it is ValidationIssue.ShadowedTransitions }, "seed $seed")
        // Guarding every transition removes every shadowing issue.
        val guarded = chart.copy(transitions = chart.transitions.map { it.copy(guard = it.guard ?: "g") })
        assertTrue(guarded.validate(RandomCharts.actions).none { it is ValidationIssue.ShadowedTransitions }, "seed $seed")
    }

    @Test
    fun validateOnDeclaredChartsReportsOnlyReachabilityAndAmbiguity() = RandomCharts.forEachChart { seed, _, chart ->
        val issues = chart.validate()
        assertEquals(referenceIssues(chart), issues, "seed $seed")
        assertTrue(
            issues.all { it is ValidationIssue.UnreachableState || it is ValidationIssue.AmbiguousTransitions },
            "seed $seed: $issues",
        )
        val sound = chart.reachableStates().containsAll(chart.states.map { it.id }) &&
            chart.transitions.filter { it.guard == null }.groupBy { it.source to it.on }.values.all { it.size == 1 }
        assertEquals(sound, issues.isEmpty(), "seed $seed")
    }

    @Test
    fun everyAmbiguityIsJustifiedAndEveryUnreachableStateHasNoPath() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        val reachable = fixedPointReachable(chart)
        for (issue in chart.validate()) {
            when (issue) {
                is ValidationIssue.AmbiguousTransitions -> {
                    assertTrue(issue.transitions.size >= 2, "seed $seed")
                    assertTrue(issue.transitions.all { it.source == issue.source && it.on == issue.on && it.guard == null }, "seed $seed")
                    // The group keeps declaration order and is complete.
                    assertEquals(chart.transitions.filter { it in issue.transitions }, issue.transitions, "seed $seed")
                    assertEquals(
                        chart.transitions.count { it.source == issue.source && it.on == issue.on && it.guard == null },
                        issue.transitions.size,
                        "seed $seed",
                    )
                }
                is ValidationIssue.ShadowedTransitions -> error("seed $seed: shadowing reported without samples")
                is ValidationIssue.UnreachableState -> assertTrue(issue.id !in reachable, "seed $seed")
                is ValidationIssue.DuplicateStateId -> assertTrue(chart.states.count { it.id == issue.id } > 1, "seed $seed")
                is ValidationIssue.UnknownInitialState -> assertTrue(chart.states.none { it.id == issue.id }, "seed $seed")
                is ValidationIssue.UnknownTransitionSource -> assertTrue(chart.states.none { it.id == issue.transition.source }, "seed $seed")
                is ValidationIssue.UnknownTransitionTarget -> assertTrue(chart.states.none { it.id == issue.transition.target }, "seed $seed")
            }
        }
        // Endpoint issues are per declared transition, so a duplicated transition is reported once
        // per copy; every other issue is reported once.
        val issues = chart.validate()
        val (endpoint, other) = issues.partition {
            it is ValidationIssue.UnknownTransitionSource || it is ValidationIssue.UnknownTransitionTarget
        }
        assertEquals(other.distinct(), other, "seed $seed")
        for (issue in endpoint.distinct()) {
            val transition = when (issue) {
                is ValidationIssue.UnknownTransitionSource -> issue.transition
                is ValidationIssue.UnknownTransitionTarget -> issue.transition
                else -> error("unreachable")
            }
            assertEquals(chart.transitions.count { it == transition }, endpoint.count { it == issue }, "seed $seed")
        }
    }

    @Test
    fun everyShadowingIsJustified() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        for (issue in chart.validate(RandomCharts.actions).filterIsInstance<ValidationIssue.ShadowedTransitions>()) {
            val group = issue.transitions
            assertTrue(group.size >= 2 && group.map { it.on }.distinct().size >= 2, "seed $seed: $issue")
            assertTrue(group.all { it.source == issue.source && it.guard == null && naiveMatches(it.on, issue.sample) }, "seed $seed")
            // Complete and in declaration order: the first one is what the runtime takes.
            assertEquals(
                chart.transitions.filter { it.source == issue.source && it.guard == null && naiveMatches(it.on, issue.sample) },
                group,
                "seed $seed",
            )
        }
    }

    @Test
    fun validateIsEquivariantUnderRenamingStates() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val ids = RandomCharts.allIds
        val permuted = ids.zip(ids.shuffled(random)).toMap()
        val f: (StateId) -> StateId = { permuted.getValue(it) }
        val renamed = rename(chart, f)
        assertEquals(chart.validate().map { renameIssue(it, f) }, renamed.validate(), "seed $seed")
        assertEquals(
            chart.validate(RandomCharts.actions).map { renameIssue(it, f) },
            renamed.validate(RandomCharts.actions),
            "seed $seed",
        )
        assertEquals(chart.reachableStates().map(f), renamed.reachableStates().toList(), "seed $seed")
    }

    @Test
    fun addingAnUnreachableIslandAddsExactlyItsIssues() = RandomCharts.forEachChart { seed, random, chart ->
        val island = List(random.nextInt(1, 5)) { StateId("island $it") }
        val islandTransitions = island.zipWithNext { a, b -> Transition(a, b, RandomCharts.matchers.random(random)) } +
            Transition(island.last(), island.first(), RandomCharts.matchers.random(random)) +
            // An edge from the island into the chart does not make the island reachable.
            Transition(island.first(), chart.initial, RandomCharts.matchers.random(random), guard = "never")
        val withIsland = chart.copy(
            states = chart.states + island.map(::AtomicState),
            transitions = islandTransitions + chart.transitions,
        )
        val before = chart.validate()
        // Every island state has exactly one unguarded way out, so the island adds no ambiguity.
        assertEquals(
            before.filter { it !is ValidationIssue.AmbiguousTransitions } +
                island.map { ValidationIssue.UnreachableState(it) } +
                before.filterIsInstance<ValidationIssue.AmbiguousTransitions>(),
            withIsland.validate(),
            "seed $seed",
        )
        assertEquals(chart.reachableStates(), withIsland.reachableStates(), "seed $seed")
    }

    // endregion

    // region mermaid

    private class ParsedMermaid(val declarations: List<Pair<String, String>>, val initialRef: String, val transitionLines: List<String>)

    private fun parse(seed: Int, text: String): ParsedMermaid {
        val lines = text.lines()
        assertEquals("stateDiagram-v2", lines.first(), "seed $seed")
        val declaration = Regex("^ {4}state \"([^\"]*)\" as ([A-Za-z_][A-Za-z0-9_]*)$")
        val declarations = lines.drop(1).takeWhile { declaration.matches(it) }.map {
            val match = declaration.matchEntire(it)!!
            match.groupValues[1] to match.groupValues[2]
        }
        val initialLine = lines[1 + declarations.size]
        assertTrue(initialLine.startsWith("    [*] --> "), "seed $seed: $initialLine")
        return ParsedMermaid(declarations, initialLine.removePrefix("    [*] --> "), lines.drop(2 + declarations.size))
    }

    @Test
    fun mermaidListsEveryStateAndTransitionExactlyOnceWithUniqueSafeAliases() =
        RandomCharts.forEachChart(valid = false) { seed, _, chart ->
            val parsed = parse(seed, chart.toMermaid())
            val ids = idsInFirstUseOrder(chart)
            val aliased = ids.filter(::needsAlias)

            // Every id that is not a safe identifier is declared exactly once, in first-use order,
            // with its text as the label.
            assertEquals(aliased.map { it.value.replace("\"", "'") }, parsed.declarations.map { it.first }, "seed $seed")

            val aliases = parsed.declarations.map { it.second }
            val plain = ids.filterNot(::needsAlias).map { it.value }
            assertEquals(aliases.distinct(), aliases, "seed $seed: aliases must be unique")
            assertTrue(aliases.none { it in plain }, "seed $seed: alias $aliases collides with a plain id $plain")
            assertTrue(aliases.all { it.lowercase() !in keywords }, "seed $seed")

            val refOf = (plain.map { StateId(it) to it } + aliased.zip(aliases)).toMap()
            assertEquals(ids.size, refOf.values.toSet().size, "seed $seed: two ids share a reference")
            assertEquals(refOf.getValue(chart.initial), parsed.initialRef, "seed $seed")
            assertEquals(
                chart.transitions.map { t ->
                    "    ${refOf.getValue(t.source)} --> ${refOf.getValue(t.target)} : ${t.on.name}" + (t.guard?.let { " [$it]" } ?: "")
                },
                parsed.transitionLines,
                "seed $seed",
            )
        }

    @Test
    fun mermaidIsDeterministicAndStableWhenTransitionsAreAppended() = RandomCharts.forEachChart { seed, random, chart ->
        val text = chart.toMermaid()
        assertEquals(text, chart.toMermaid(), "seed $seed")
        assertEquals(text, chart.copy(states = chart.states.toList(), transitions = chart.transitions.toList()).toMermaid(), "seed $seed")

        val ids = chart.states.map { it.id }
        val extra = Transition(ids.random(random), ids.random(random), RandomCharts.matchers.random(random))
        val longer = chart.copy(transitions = chart.transitions + extra).toMermaid()
        assertTrue(longer.startsWith(text + "\n"), "seed $seed: appending a transition changed earlier lines")
        assertEquals(1, longer.lines().size - text.lines().size, "seed $seed")
    }

    @Test
    fun mermaidAliasesOfExistingStatesSurviveAddingStates() = RandomCharts.forEachChart { seed, random, chart ->
        fun refs(c: StateChartDefinition): Map<String, String> = parse(seed, c.toMermaid()).declarations.toMap()
        val newId = StateId("new state ${random.nextInt(100)}")
        val bigger = chart.copy(states = chart.states + AtomicState(newId))
        val before = refs(chart)
        val after = refs(bigger)
        assertEquals(before, after - newId.value, "seed $seed")
        assertTrue(after.getValue(newId.value) !in before.values, "seed $seed")
    }

    // endregion

    // region hand-picked edge cases found by the properties

    @Test
    fun mermaidGeneratedAliasNeverCollidesWithAPlainIdThatLooksLikeOne() {
        val plain = StateId("Plain")
        val spaced = StateId("a b")
        val lookalike = StateId("koma_state_1")
        val chart = StateChartDefinition(
            initial = plain,
            states = listOf(plain, spaced, lookalike).map(::AtomicState),
            transitions = listOf(Transition(plain, spaced, ActionMatcher("Go")), Transition(spaced, lookalike, ActionMatcher("Go"))),
        )

        assertEquals(
            """
            stateDiagram-v2
                state "a b" as koma_state_0
                [*] --> Plain
                Plain --> koma_state_0 : Go
                koma_state_0 --> koma_state_1 : Go
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    @Test
    fun mermaidAliasesKeywordsAndUndeclaredIds() {
        val state = StateId("state")
        val ghost = StateId("not declared")
        val chart = StateChartDefinition(
            initial = state,
            states = listOf(AtomicState(state), AtomicState(state)),
            transitions = listOf(Transition(state, ghost, ActionMatcher("Go"), guard = "ok")),
        )

        assertEquals(
            """
            stateDiagram-v2
                state "state" as koma_state_0
                state "not declared" as koma_state_1
                [*] --> koma_state_0
                koma_state_0 --> koma_state_1 : Go [ok]
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    @Test
    fun mermaidOfChartWithoutTransitions() {
        val only = StateId("Only")
        assertEquals(
            "stateDiagram-v2\n    [*] --> Only",
            StateChartDefinition(only, listOf(AtomicState(only)), emptyList()).toMermaid(),
        )
    }

    @Test
    fun blankLikeIdsAreRejectedButAnyOtherTextIsKept() {
        for (blank in listOf("", " ", "\t", "\n", " \r\n\t ")) {
            assertFailsWith<IllegalArgumentException> { StateId(blank) }
            assertFailsWith<IllegalArgumentException> { ActionMatcher(blank) }
        }
        for (text in RandomCharts.trickyIds) {
            assertEquals(text, StateId(text).toString())
            assertEquals(text, StateId(text).value)
        }
    }

    @Test
    fun actionMatcherOfUsesTheGivenNameAndType() {
        assertEquals(ActionMatcher("Go", RandomAction.Go::class), ActionMatcher.of<RandomAction.Go>("Go"))
        assertEquals(ActionMatcher("Any", RandomAction::class), ActionMatcher.of<RandomAction>("Any"))
        assertTrue(ActionMatcher("Go") != ActionMatcher.of<RandomAction.Go>("Go"))
        for (blank in listOf("", " ", "\t")) {
            assertFailsWith<IllegalArgumentException> { ActionMatcher.of<RandomAction.Go>(blank) }
        }
    }

    private inline fun <reified A : Action> matcherFor(@Suppress("UNUSED_PARAMETER") action: A, name: String) = ActionMatcher.of<A>(name)

    @Test
    fun actionMatcherOfWorksForTypesWithoutASimpleName() {
        val anonymous = object : Action {}
        val matcher = matcherFor(anonymous, "Anonymous")
        assertEquals("Anonymous", matcher.name)
        assertTrue(matcher.matches(anonymous))
        assertTrue(!matcher.matches(RandomAction.Ping))
    }

    @Test
    fun matchesEqualsNaiveDefinition() {
        val samples: List<Action> = RandomCharts.actions + object : Action {}
        for (matcher in RandomCharts.matchers) {
            for (action in samples) assertEquals(naiveMatches(matcher, action), matcher.matches(action), "$matcher $action")
        }
    }

    @Test
    fun transitionsFromEqualsFilterInDeclarationOrder() = RandomCharts.forEachChart(valid = false) { seed, _, chart ->
        for (id in RandomCharts.allIds) {
            val expected = mutableListOf<Transition>()
            for (t in chart.transitions) if (t.source == id) expected += t
            assertEquals(expected, chart.transitionsFrom(id), "seed $seed")
        }
    }

    @Test
    fun generatorCoversTheInterestingShapes() {
        // Guards against a generator change silently dropping the tricky cases.
        var selfLoops = 0
        var duplicates = 0
        var islands = 0
        var aliased = 0
        var ambiguous = 0
        var deep = 0
        var shadowed = 0
        RandomCharts.forEachChart { _, _, chart ->
            if (chart.validate(RandomCharts.actions).any { it is ValidationIssue.ShadowedTransitions }) shadowed++
            if (chart.reachableStates().size >= 4) deep++
            if (chart.transitions.any { it.source == it.target }) selfLoops++
            if (chart.transitions.distinct().size < chart.transitions.size) duplicates++
            if (chart.validate().any { it is ValidationIssue.UnreachableState }) islands++
            if (idsInFirstUseOrder(chart).any(::needsAlias)) aliased++
            if (chart.validate().any { it is ValidationIssue.AmbiguousTransitions }) ambiguous++
        }
        for ((name, count) in listOf("selfLoops" to selfLoops, "duplicates" to duplicates, "islands" to islands, "aliased" to aliased, "ambiguous" to ambiguous, "shadowed" to shadowed)) {
            assertTrue(count >= 20, "only $count charts with $name")
        }
        assertTrue(deep >= 100, "only $deep charts with 4+ reachable states")
    }

    // endregion
}
