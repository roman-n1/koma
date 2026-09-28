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
 * [RandomCharts], with a random truth table per seed for every guard label, action and retry
 * count. States are [RandomState]s, so guards can read state data (`attempts`).
 *
 * The reference is deliberately naive: scan *all* transitions in declaration order and take the
 * first one that leaves the current state, matches the action (by a hard-coded type/name table,
 * not by reflection) and whose guard is absent or true.
 *
 * ```
 * [*] --> A
 * A --Go [even]--> B        guard table decides, per action and attempts
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
        private val table = RandomCharts.guards.associateWith {
            RandomCharts.actions.associateWith { List(maxAttempts + 1) { random.nextBoolean() } }
        }
        val calls = mutableListOf<Triple<String, RandomState, Action>>()

        fun holds(label: String, state: RandomState, action: Action): Boolean =
            table.getValue(label).getValue(action as RandomAction)[state.attempts]

        fun implementations(): Map<String, (RandomState, Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { state: RandomState, action: Action -> calls += Triple(label, state, action); holds(label, state, action) }
        }

        companion object {
            const val maxAttempts = 3
        }
    }

    private val stateIdOf: (RandomState) -> StateId = { it.id }

    private fun runtimeOf(chart: StateChartDefinition, table: GuardTable) = StateChartRuntime(chart, stateIdOf, table.implementations())

    private fun randomState(random: Random, id: StateId) = RandomState(id, random.nextInt(GuardTable.maxAttempts + 1))

    private fun referenceStep(chart: StateChartDefinition, table: GuardTable, current: RandomState, action: RandomAction): StepResult {
        for (t in chart.transitions) {
            if (t.source != current.id) continue
            if (!referenceMatches(t.on, action)) continue
            if (t.guard != null && !table.holds(t.guard, current, action)) continue
            return StepResult.Transitioned(t)
        }
        return StepResult.Ignored
    }

    @Test
    fun stepEqualsFirstDeclaredMatchingTransitionWithTrueGuard() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = runtimeOf(chart, table)
        for (id in RandomCharts.allIds) {
            for (attempts in 0..GuardTable.maxAttempts) {
                val current = RandomState(id, attempts)
                for (action in RandomCharts.actions) {
                    assertEquals(referenceStep(chart, table, current, action), runtime.step(current, action), "seed $seed: $current on $action")
                }
            }
        }
    }

    @Test
    fun guardsAreEvaluatedLazilyInDeclarationOrderUntilOneHolds() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = runtimeOf(chart, table)
        repeat(20) {
            val current = randomState(random, chart.states.random(random).id)
            val action = RandomCharts.actions.random(random)
            table.calls.clear()
            val result = runtime.step(current, action)

            // Every guard receives exactly the state and action passed to step.
            val expectedCalls = mutableListOf<Triple<String, RandomState, Action>>()
            for (t in chart.transitionsFrom(current.id)) {
                if (!referenceMatches(t.on, action)) continue
                val guard = t.guard ?: break
                expectedCalls += Triple(guard, current, action)
                if (table.holds(guard, current, action)) break
            }
            assertEquals(expectedCalls, table.calls, "seed $seed: $current on $action gave $result")
        }
    }

    @Test
    fun randomWalksStayInsideReachableStatesAndAgreeWithTheReference() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = runtimeOf(chart, table)
        val reachable = chart.reachableStates()
        val actions = List(random.nextInt(0, 60)) { RandomCharts.actions.random(random) }

        // An ignored action counts as a failed attempt (up to maxAttempts); a transition resets it.
        fun next(state: RandomState, target: StateId?): RandomState =
            if (target == null) state.copy(attempts = minOf(state.attempts + 1, GuardTable.maxAttempts)) else RandomState(target)

        val actual = actions.runningFold(RandomState(chart.initial)) { state, action ->
            when (val result = runtime.step(state, action)) {
                is StepResult.Transitioned -> {
                    assertEquals(state.id, result.transition.source, "seed $seed")
                    assertEquals(result.transition.target, result.target, "seed $seed")
                    next(state, result.target)
                }
                StepResult.Ignored -> next(state, null)
            }
        }
        val expected = actions.runningFold(RandomState(chart.initial)) { state, action ->
            next(state, (referenceStep(chart, table, state, action) as? StepResult.Transitioned)?.transition?.target)
        }
        assertEquals(expected, actual, "seed $seed")
        assertTrue(reachable.containsAll(actual.map { it.id }), "seed $seed: the runtime left the reachable set")
    }

    @Test
    fun stepIsPureAndDoesNotDependOnHistory() = RandomCharts.forEachChart { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = runtimeOf(chart, table)
        val probes = List(10) { randomState(random, chart.states.random(random).id) to RandomCharts.actions.random(random) }
        val first = probes.map { (s, a) -> runtime.step(s, a) }
        // Interleave unrelated steps, then ask again, also on a fresh runtime.
        repeat(30) { runtime.step(randomState(random, RandomCharts.allIds.random(random)), RandomCharts.actions.random(random)) }
        assertEquals(first, probes.map { (s, a) -> runtime.step(s, a) }, "seed $seed")
        assertEquals(first, probes.map { (s, a) -> runtimeOf(chart, table).step(s, a) }, "seed $seed")
    }

    @Test
    fun missingGuardsAreAllListedOnceInFirstUseOrder() = RandomCharts.forEachChart(valid = false) { seed, random, chart ->
        val provided = RandomCharts.guards.filter { random.nextBoolean() }
        val used = chart.transitions.mapNotNull { it.guard }.distinct()
        val missing = used.filter { it !in provided }
        val guards = (provided + "unused-${random.nextInt(5)}").associateWith { { _: RandomState, _: Action -> true } }

        if (missing.isEmpty()) {
            StateChartRuntime(chart, stateIdOf, guards)
        } else {
            val error = assertFailsWith<IllegalArgumentException>("seed $seed") { StateChartRuntime(chart, stateIdOf, guards) }
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
                Transition(a, b, ActionMatcher.of<RandomAction.Ping>("Ping")),
                Transition(a, b, ActionMatcher.of<RandomAction.Ping>("Ping"), guard = "boom"),
                Transition(a, a, ActionMatcher.of<RandomAction.Pong>("Pong"), guard = "boom"),
            ),
        )
        val runtime = StateChartRuntime(chart, stateIdOf, mapOf("boom" to { _: RandomState, _: Action -> throw IllegalStateException("boom") }))

        // The unguarded Ping transition wins before the throwing guard is looked at.
        assertEquals(StepResult.Transitioned(chart.transitions[0]), runtime.step(RandomState(a), RandomAction.Ping))
        // No transition leaves B, so no guard runs.
        assertEquals(StepResult.Ignored, runtime.step(RandomState(b), RandomAction.Pong))
        assertFailsWith<IllegalStateException> { runtime.step(RandomState(a), RandomAction.Pong) }
    }

    @Test
    fun runtimeExposesItsDefinition() {
        val chart = RandomCharts.chart(Random(42))
        assertSame(chart, StateChartRuntime(chart, stateIdOf, RandomCharts.guards.associateWith { { _: RandomState, _: Action -> true } }).definition)
    }

    @Test
    fun retryGuardReadsStateDataAndStopsAfterThreeAttempts() = RandomCharts.forEachChart { seed, random, chart ->
        // Every guard becomes "attempts < 3"; every guarded transition then fires exactly while the
        // counter in the state is below 3, whatever the action carries.
        val runtime = StateChartRuntime(chart, stateIdOf, RandomCharts.guards.associateWith { { s: RandomState, _: Action -> s.attempts < 3 } })
        repeat(10) {
            val id = chart.states.random(random).id
            val action = RandomCharts.actions.random(random)
            val matching = chart.transitionsFrom(id).filter { referenceMatches(it.on, action) }
            for (attempts in 0..5) {
                val expected = matching.firstOrNull { it.guard == null || attempts < 3 }
                val result = runtime.step(RandomState(id, attempts), action)
                assertEquals(expected?.let { StepResult.Transitioned(it) } ?: StepResult.Ignored, result, "seed $seed: $id/$attempts on $action")
            }
        }
    }
}
