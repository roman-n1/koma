package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Property-based tests for [StateChartRuntime.step] and [ActionMatcher.matches] over
 * [RandomCharts], with a random truth table per seed for every guard label.
 *
 * The reference is deliberately naive: scan *all* transitions in declaration order and take the
 * first one that leaves the current state, matches the action (by a hard-coded type/name table,
 * not by reflection) and whose guard is absent or true.
 *
 * ```
 * [*] --> A
 * A --Go [even]--> B        guard table decides, per action
 * A --Go--> C               fallback when [even] is false
 * A --Anything--> A         supertype matcher: matches every RandomAction
 * B --Ping--> B             self-loop
 * C --"Reset" (by name)--> A
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartRuntimePropertyTest {

    private val simpleNames = mapOf(
        RandomAction.Ping to "Ping",
        RandomAction.Pong to "Pong",
        RandomAction.Reset to "Reset",
    )

    private fun referenceMatches(matcher: ActionMatcher, action: RandomAction): Boolean {
        val name = simpleNames[action] ?: "Go"
        return when (matcher.type) {
            null -> matcher.name == name
            RandomAction::class -> true
            RandomAction.Go::class -> action is RandomAction.Go
            RandomAction.Ping::class -> action == RandomAction.Ping
            RandomAction.Pong::class -> action == RandomAction.Pong
            else -> error("unexpected matcher $matcher")
        }
    }

    private class GuardTable(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        val calls = mutableListOf<Pair<String, Action>>()

        fun holds(label: String, action: Action): Boolean = table.getValue(label).getValue(action as RandomAction)

        fun implementations(): Map<String, (Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { action: Action -> calls += label to action; holds(label, action) }
        }
    }

    private fun referenceStep(chart: StateChartDefinition, table: GuardTable, current: StateId, action: RandomAction): StepResult {
        for (t in chart.transitions) {
            if (t.source != current) continue
            if (!referenceMatches(t.on, action)) continue
            if (t.guard != null && !table.holds(t.guard!!, action)) continue
            return StepResult.Transitioned(t)
        }
        return StepResult.Ignored
    }

    @Test
    fun stepEqualsFirstDeclaredMatchingTransitionWithTrueGuard() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, table.implementations())
        for (current in RandomCharts.allIds) {
            for (action in RandomCharts.actions) {
                assertEquals(referenceStep(chart, table, current, action), runtime.step(current, action), "seed $seed: $current on $action")
            }
        }
    }

    @Test
    fun guardsAreEvaluatedLazilyInDeclarationOrderUntilOneHolds() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, table.implementations())
        repeat(20) {
            val current = chart.states.random(random).id
            val action = RandomCharts.actions.random(random)
            table.calls.clear()
            val result = runtime.step(current, action)

            val expectedCalls = mutableListOf<Pair<String, Action>>()
            for (t in chart.transitionsFrom(current)) {
                if (!referenceMatches(t.on, action)) continue
                val guard = t.guard ?: break
                expectedCalls += guard to action
                if (table.holds(guard, action)) break
            }
            assertEquals(expectedCalls, table.calls, "seed $seed: $current on $action gave $result")
        }
    }

    @Test
    fun randomWalksStayInsideReachableStatesAndAgreeWithTheReference() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, table.implementations())
        val reachable = chart.reachableStates()
        val actions = List(random.nextInt(0, 60)) { RandomCharts.actions.random(random) }

        val actual = actions.runningFold(chart.initial) { state, action ->
            when (val result = runtime.step(state, action)) {
                is StepResult.Transitioned -> {
                    assertEquals(state, result.transition.source, "seed $seed")
                    assertEquals(result.transition.target, result.target, "seed $seed")
                    result.target
                }
                StepResult.Ignored -> state
            }
        }
        val expected = actions.runningFold(chart.initial) { state, action ->
            (referenceStep(chart, table, state, action) as? StepResult.Transitioned)?.transition?.target ?: state
        }
        assertEquals(expected, actual, "seed $seed")
        assertTrue(reachable.containsAll(actual), "seed $seed: the runtime left the reachable set")
    }

    @Test
    fun stepIsPureAndDoesNotDependOnHistory() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = StateChartRuntime(chart, table.implementations())
        val probes = List(10) { chart.states.random(random).id to RandomCharts.actions.random(random) }
        val first = probes.map { (s, a) -> runtime.step(s, a) }
        // Interleave unrelated steps, then ask again, also on a fresh runtime.
        repeat(30) { runtime.step(RandomCharts.allIds.random(random), RandomCharts.actions.random(random)) }
        assertEquals(first, probes.map { (s, a) -> runtime.step(s, a) }, "seed $seed")
        assertEquals(first, probes.map { (s, a) -> StateChartRuntime(chart, table.implementations()).step(s, a) }, "seed $seed")
    }

    @Test
    fun missingGuardsAreAllListedOnceInFirstUseOrder() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val provided = RandomCharts.guards.filter { random.nextBoolean() }
        val used = chart.transitions.mapNotNull { it.guard }.distinct()
        val missing = used.filter { it !in provided }
        val guards = (provided + "unused-${random.nextInt(5)}").associateWith { { _: Action -> true } }

        if (missing.isEmpty()) {
            StateChartRuntime(chart, guards)
        } else {
            val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartRuntime(chart, guards) }
            assertEquals("[Koma] Missing guard implementations: ${missing.joinToString()}", error.message, "seed $seed")
        }
    }

    @Test
    fun matchesAgreesWithTheReferenceTable() {
        for (matcher in RandomCharts.matchers + ActionMatcher("RandomAction") + ActionMatcher("ping")) {
            for (action in RandomCharts.actions) {
                assertEquals(referenceMatches(matcher, action), matcher.matches(action), "$matcher on $action")
            }
        }
    }

    @Test
    fun nameOnlyMatcherIsCaseSensitiveAndIgnoresPayload() {
        assertTrue(ActionMatcher("Go").matches(RandomAction.Go(-1)))
        assertTrue(ActionMatcher("Go").matches(RandomAction.Go(Int.MAX_VALUE)))
        assertFalse(ActionMatcher("go").matches(RandomAction.Go(0)))
        assertFalse(ActionMatcher("RandomAction").matches(RandomAction.Ping))
    }

    @Test
    fun throwingGuardOnlyMattersWhenItIsReached() {
        val a = StateId("A")
        val b = StateId("B")
        val chart = StateChartDefinition(
            initial = a,
            states = listOf(AtomicState(a), AtomicState(b)),
            transitions = listOf(
                Transition(a, b, ActionMatcher.of<RandomAction.Ping>()),
                Transition(a, b, ActionMatcher.of<RandomAction.Ping>(), guard = "boom"),
                Transition(a, a, ActionMatcher.of<RandomAction.Pong>(), guard = "boom"),
            ),
        )
        val runtime = StateChartRuntime(chart, mapOf("boom" to { _: Action -> throw IllegalStateException("boom") }))

        // The unguarded Ping transition wins before the throwing guard is looked at.
        assertEquals(StepResult.Transitioned(chart.transitions[0]), runtime.step(a, RandomAction.Ping))
        // No transition leaves B, so no guard runs.
        assertEquals(StepResult.Ignored, runtime.step(b, RandomAction.Pong))
        assertFailsWith<IllegalStateException> { runtime.step(a, RandomAction.Pong) }
    }

    @Test
    fun runtimeExposesItsDefinition() {
        val chart = RandomCharts.chart(Random(42))
        assertSame(chart, StateChartRuntime(chart, RandomCharts.guards.associateWith { { _: Action -> true } }).definition)
    }
}
