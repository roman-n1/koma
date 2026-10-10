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
 * Property-based tests for timers ([Trigger.After]) over [RandomCharts.forEachTimerChart]: random
 * hierarchical and parallel charts with history states and random timers (self-loops, guards,
 * effects, equal delays from one source), and [RandomCharts.malformedTimers] variants.
 *
 * The reference ([ParallelReference]) takes a timer alone, like any transition, and lists the
 * timers of the states a step entered and exited. The walks simulate the caller's scheduler: the
 * test keeps the running timers from what the runtime says to start and cancel, and fires one of
 * them (or, sometimes, a stale one) at random between actions.
 *
 * ```
 * [*] --> A
 * A --after 1s--> B               starts when A is entered, cancelled when A is exited
 * A --Ping--> A                   a self-loop re-enters A: its timers restart
 * state B {
 *     [*] --> B1
 *     B1 --after 5s [even]--> B2  guard asked with TimerFired
 * }
 * ```
 */
@OptIn(ExperimentalActronApi::class)
class StateChartTimerPropertyTest {

    /** A random truth value per guard label and action, and per guard label for timers. */
    private class GuardTable(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        private val timers = RandomCharts.guards.associateWith { random.nextBoolean() }
        val asked = mutableListOf<TimerFired>()

        fun holds(label: String, action: RandomAction): Boolean = table.getValue(label).getValue(action)

        fun holdsForTimer(label: String): Boolean = timers.getValue(label)

        fun implementations(): Map<String, (RandomState, Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { _: RandomState, action: Action ->
                if (action is TimerFired) {
                    asked += action
                    holdsForTimer(label)
                } else {
                    holds(label, action as RandomAction)
                }
            }
        }
    }

    private fun HistoryConfiguration.asConfiguration() = StateConfiguration(active, history)

    private fun StateConfiguration.asReference() = HistoryConfiguration(active, history)

    private fun ParallelReference.asResult(fired: ParallelFired) = StepResult.Transitioned(
        fired.transitions,
        fired.exited,
        fired.entered,
        fired.after.asConfiguration(),
        timersToStart = timersOf(fired.entered),
        timersToCancel = timersOf(fired.exited),
    )

    private fun <T> List<T>.counts(): Map<T, Int> = groupingBy { it }.eachCount()

    private fun runtimeFor(chart: StateChartDefinition, table: GuardTable) = StateChartRuntime(chart, { s: RandomState -> s.id }, table.implementations())

    // region generator

    @Test
    fun generatorAddsTimersOfEveryKind() {
        var selfLoops = 0
        var guarded = 0
        var inRegions = 0
        var equalDelays = 0
        RandomCharts.forEachTimerChart { seed, _, chart ->
            val timers = chart.transitions.filter { it.isTimer }
            assertTrue(timers.isNotEmpty(), "seed $seed")
            assertTrue(timers.all { it.after!!.isPositive() && chart.node(it.source).let { n -> n != null && n !is HistoryState } }, "seed $seed")
            selfLoops += timers.count { it.source == it.target }
            guarded += timers.count { it.guard != null }
            if (timers.any { t -> chart.ancestorsOf(t.source).any { chart.node(it) is ParallelState } }) inRegions++
            if (chart.validate().any { it is ValidationIssue.AmbiguousTimers }) equalDelays++
        }
        assertTrue(selfLoops >= 100, "only $selfLoops self-loop timers")
        assertTrue(guarded >= 200, "only $guarded guarded timers")
        assertTrue(inRegions >= 100, "only $inRegions charts with a timer inside a region")
        assertTrue(equalDelays >= 20, "only $equalDelays charts with ambiguous timers")
    }

    // endregion

    // region runtime

    @Test
    fun initialTimersAreTheTimersOfTheInitialStatesOutermostFirst() = RandomCharts.forEachTimerChart { seed, random, chart ->
        val runtime = runtimeFor(chart, GuardTable(random))
        val reference = ParallelReference(chart)
        val initial = reference.initialConfiguration().active.sortedWith(compareBy<StateId> { reference.depth(it) }.thenBy { reference.order(it) })
        assertEquals(reference.timersOf(initial), runtime.initialTimers(), "seed $seed")
        assertEquals(runtime.initialTimers(), runtime.activeTimers(runtime.initialConfiguration()), "seed $seed")
    }

    @Test
    fun walksWithTimersAgreeWithTheReferenceAndKeepTheRunningTimersExact() {
        var fired = 0
        var stale = 0
        var rejected = 0
        var restarted = 0
        var kept = 0
        RandomCharts.forEachTimerChart { seed, random, chart ->
            val table = GuardTable(random)
            val runtime = runtimeFor(chart, table)
            val reference = ParallelReference(chart)
            val timers = chart.transitions.filter { it.isTimer }
            var configuration = runtime.initialConfiguration()
            // The caller's scheduler: every timer started and not cancelled yet.
            val running = runtime.initialTimers().toMutableList()
            repeat(40) {
                assertEquals(runtime.activeTimers(configuration).counts(), running.counts(), "seed $seed: running timers")
                val leaves = chart.activeLeaves(configuration)
                val state = RandomState(leaves.first())
                val roll = random.nextInt(10)
                val result: StepResult
                val expected: ParallelFired?
                if (roll < 4 && running.isNotEmpty()) {
                    val timer = running.random(random)
                    table.asked.clear()
                    result = runtime.fire(configuration, state, timer)
                    expected = reference.fireTimer(configuration.asReference(), timer, table::holdsForTimer)
                    // A guard is asked exactly for a guarded timer, with the timer itself.
                    assertEquals(if (timer.guard != null) listOf(TimerFired(timer)) else emptyList(), table.asked, "seed $seed")
                    if (expected == null) rejected++ else fired++
                } else if (roll < 5) {
                    val timer = timers.random(random)
                    result = runtime.fire(configuration, state, timer)
                    expected = reference.fireTimer(configuration.asReference(), timer, table::holdsForTimer)
                    if (timer.source !in configuration.active) {
                        assertEquals(StepResult.Ignored, result, "seed $seed: stale $timer")
                        stale++
                    }
                } else {
                    val action = RandomCharts.actions.random(random)
                    result = runtime.step(configuration, state, action)
                    expected = reference.step(configuration.asReference(), action, table::holds)
                    // An action never fires a timer.
                    if (result is StepResult.Transitioned) assertTrue(result.transitions.none { it.isTimer }, "seed $seed")
                }
                assertEquals(expected?.let { reference.asResult(it) } ?: StepResult.Ignored, result, "seed $seed")
                if (result !is StepResult.Transitioned) return@repeat
                // Cancel, then start: the running timers stay exactly those of the active states.
                for (t in result.timersToCancel) assertTrue(running.remove(t), "seed $seed: cancelled $t is not running")
                running += result.timersToStart
                if (result.timersToStart.any { it in result.timersToCancel }) restarted++
                if (running.any { it !in result.timersToStart }) kept++
                configuration = result.configuration
            }
        }
        assertTrue(fired >= 1500, "only $fired timers fired")
        assertTrue(stale >= 300, "only $stale stale timers ignored")
        assertTrue(rejected >= 100, "only $rejected timers rejected by their guard")
        assertTrue(restarted >= 300, "only $restarted steps restarting a timer")
        assertTrue(kept >= 500, "only $kept steps keeping a timer of an untouched state running")
    }

    @Test
    fun timerFiresAloneEvenWhenAnActionWouldFireInOtherRegions() = RandomCharts.forEachTimerChart(100) { seed, random, chart ->
        val table = GuardTable(random)
        val runtime = runtimeFor(chart, table)
        val configuration = runtime.initialConfiguration()
        for (timer in runtime.activeTimers(configuration)) {
            val result = runtime.fire(configuration, RandomState(timer.source), timer)
            if (result is StepResult.Transitioned) {
                assertEquals(listOf(timer), result.transitions, "seed $seed")
                assertTrue(timer in result.timersToCancel, "seed $seed: the fired timer's source is exited")
            }
        }
    }

    @Test
    fun fireRejectsWhatIsNotADeclaredTimer() = RandomCharts.forEachTimerChart(50) { seed, random, chart ->
        val runtime = runtimeFor(chart, GuardTable(random))
        val configuration = runtime.initialConfiguration()
        chart.transitions.firstOrNull { !it.isTimer }?.let { t ->
            val error = assertFailsWith<IllegalArgumentException>("seed $seed") { runtime.fire(configuration, RandomState(t.source), t) }
            assertEquals("[Actron] Not a timer: $t", error.message)
        }
        val undeclared = Transition(chart.initial, chart.initial, Trigger.After(RandomCharts.delays.last() * 7))
        val error = assertFailsWith<IllegalArgumentException>("seed $seed") { runtime.fire(configuration, RandomState(chart.initial), undeclared) }
        assertEquals("[Actron] Timer is not declared in the chart: $undeclared", error.message)
    }

    // endregion

    // region validation

    @Test
    fun timerIssuesEqualTheReferenceInTheirPlace() {
        var nonPositive = 0
        var ambiguous = 0
        var fromHistory = 0
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedTimers(random, RandomCharts.withTimers(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random))))
            val issues = chart.validate()
            val declared = chart.states.map { it.id }.toSet()
            // Endpoint block: per transition, unknown source, unknown target, from history, non-positive delay.
            val endpoints = chart.transitions.flatMap { t ->
                listOfNotNull(
                    ValidationIssue.UnknownTransitionSource(t).takeIf { t.source !in declared },
                    ValidationIssue.UnknownTransitionTarget(t).takeIf { t.target !in declared },
                    ValidationIssue.TransitionFromHistory(t).takeIf { chart.states.firstOrNull { it.id == t.source } is HistoryState },
                    ValidationIssue.NonPositiveDelay(t).takeIf { t.trigger.let { tr -> tr is Trigger.After && tr.delay.inWholeNanoseconds <= 0 } },
                )
            }
            val endpointKinds = issues.filter {
                it is ValidationIssue.UnknownTransitionSource || it is ValidationIssue.UnknownTransitionTarget ||
                    it is ValidationIssue.TransitionFromHistory || it is ValidationIssue.NonPositiveDelay
            }
            assertEquals(endpoints, endpointKinds, "seed $seed")
            nonPositive += endpoints.count { it is ValidationIssue.NonPositiveDelay }
            fromHistory += endpoints.count { it is ValidationIssue.TransitionFromHistory && it.transition.isTimer }
            // Ambiguous timers: unguarded timers with one source and one delay, groups in order of first member.
            val keys = mutableListOf<Pair<StateId, Trigger>>()
            for (t in chart.transitions) if (t.isTimer && t.guard == null && (t.source to t.trigger) !in keys) keys += t.source to t.trigger
            val expected = keys.mapNotNull { (source, trigger) ->
                val group = chart.transitions.filter { it.source == source && it.trigger == trigger && it.guard == null }
                if (group.size > 1) ValidationIssue.AmbiguousTimers(source, (trigger as Trigger.After).delay, group) else null
            }
            assertEquals(expected, issues.filterIsInstance<ValidationIssue.AmbiguousTimers>(), "seed $seed")
            ambiguous += expected.size
            // They come right after the ambiguous action transitions, and ambiguity never lists a timer.
            val positions = issues.withIndex().filter { it.value is ValidationIssue.AmbiguousTimers }.map { it.index }
            if (positions.isNotEmpty()) {
                assertEquals((positions.first()..positions.last()).toList(), positions, "seed $seed")
                assertTrue(issues.drop(positions.last() + 1).all { it is ValidationIssue.ShadowedTransitions }, "seed $seed")
                assertTrue(issues.take(positions.first()).none { it is ValidationIssue.ShadowedTransitions }, "seed $seed")
            }
            assertTrue(issues.filterIsInstance<ValidationIssue.AmbiguousTransitions>().none { i -> i.transitions.any { it.isTimer } }, "seed $seed")
            // Samples never probe timers.
            val shadowed = chart.validate(RandomCharts.actions).filterIsInstance<ValidationIssue.ShadowedTransitions>()
            assertTrue(shadowed.none { i -> i.transitions.any { it.isTimer } }, "seed $seed")
        }
        assertTrue(nonPositive >= 150, "only $nonPositive non-positive delays")
        assertTrue(ambiguous >= 20, "only $ambiguous groups of ambiguous timers")
        assertTrue(fromHistory >= 50, "only $fromHistory timers from a history state")
    }

    @Test
    fun malformedTimerChartsStillValidateExportAndSearchWithoutLooping() {
        for (seed in RandomCharts.seeds) {
            val random = Random(seed)
            val chart = RandomCharts.malformedTimers(random, RandomCharts.withTimers(random, RandomCharts.malformed(random, RandomCharts.parallelChart(random))))
            val reachable = chart.reachableStates()
            for (id in reachable) assertNotNull(chart.shortestPathTo(id), "seed $seed")
            assertEquals(chart.transitions.filter { it.source in reachable }.toSet(), chart.transitionCoveragePaths().flatMap { it.transitions }.toSet(), "seed $seed")
            assertEquals(chart.toMermaid(), chart.toMermaid(), "seed $seed")
            chart.validate()
        }
    }

    // endregion

    // region mermaid

    @Test
    fun mermaidLabelsEveryTimerWithItsDelayGuardAndEffect() = RandomCharts.forEachTimerChart { seed, _, chart ->
        val lines = chart.toMermaid().lines()
        val timerLines = lines.filter { " : after " in it }
        assertEquals(chart.transitions.count { it.isTimer }, timerLines.size, "seed $seed")
        val labels = chart.transitions.filter { it.isTimer }.map { t ->
            "after ${t.after}" + (t.guard?.let { " [$it]" } ?: "") + (t.effect?.let { " / $it" } ?: "")
        }
        assertEquals(labels.sorted(), timerLines.map { it.substringAfter(" : ") }.sorted(), "seed $seed")
    }

    // endregion

    // region paths

    @Test
    fun reachabilityAndPathsTakeTimersAsStepsAndReplayWithFire() {
        var timerSteps = 0
        RandomCharts.forEachTimerChart(150) { seed, _, chart ->
            val reference = ParallelReference(chart)
            val distances = reference.nodeDistances()
            assertEquals(distances.keys, chart.reachableStates(), "seed $seed")
            for (id in chart.states.map { it.id }) {
                val path = chart.shortestPathTo(id)
                val distance = distances[id]
                if (distance == null) {
                    assertNull(path, "seed $seed: $id")
                    continue
                }
                assertNotNull(path, "seed $seed: $id")
                assertEquals(distance, path.transitions.size, "seed $seed: $id")
                assertTrue(id in replay(seed, chart, reference, path).active, "seed $seed: $id")
                timerSteps += path.transitions.count { it.isTimer }
            }
            val paths = chart.transitionCoveragePaths()
            assertEquals(chart.transitions.filter { it.source in distances }.toSet(), paths.flatMap { it.transitions }.toSet(), "seed $seed")
            for (path in paths) replay(seed, chart, reference, path)
        }
        assertTrue(timerSteps >= 200, "only $timerSteps timer steps in shortest paths")
    }

    /**
     * Replays [path] with the reference's graph step and checks each step against the runtime, as
     * the parallel property test does: action steps on a copy of the chart where every action
     * transition has one common matcher and its own guard enabling exactly the graph step; timer
     * steps with [StateChartRuntime.fire] on the same copy, whose timers keep their trigger. Also
     * checks [StateChartPath.triggers] and [StateChartPath.actions]. Returns the final configuration.
     */
    private fun replay(seed: Int, chart: StateChartDefinition, reference: ParallelReference, path: StateChartPath): HistoryConfiguration {
        assertEquals(path.transitions.map { it.trigger }, path.triggers, "seed $seed")
        assertEquals(path.transitions.filter { !it.isTimer }.map { it.on }, path.actions, "seed $seed")
        var configuration = reference.initialConfiguration()
        var trigger: Transition? = null
        val fire = ActionMatcher("Fire", Fire::class)
        val relabelled = chart.transitions.mapIndexed { i, t -> if (t.isTimer) t.copy(guard = "t$i") else Transition(t.source, t.target, fire, "t$i") }
        val guards = chart.transitions.withIndex().associate { (i, original) ->
            "t$i" to { _: RandomState, _: Action ->
                val t = trigger!!
                original == t || (original.on == t.on && reference.exitSet(configuration.active, original).none { it in reference.exitSet(configuration.active, t) })
            }
        }
        val runtime = StateChartRuntime(chart.copy(transitions = relabelled), { s: RandomState -> s.id }, guards)
        path.transitions.forEachIndexed { i, t ->
            assertTrue(t.source in configuration.active, "seed $seed: ${t.source} is not active before step $i")
            val fired = reference.graphStep(configuration, t)
            trigger = t
            val stepped = if (t.isTimer) {
                runtime.fire(configuration.asConfiguration(), RandomState(t.source), relabelled[chart.transitions.indexOf(t)])
            } else {
                runtime.step(configuration.asConfiguration(), RandomState(t.source), Fire)
            }
            assertEquals(fired.after.asConfiguration(), (stepped as? StepResult.Transitioned)?.configuration, "seed $seed: step $i")
            configuration = fired.after
            assertEquals(reference.leaves(configuration.active), path.activeLeaves[i], "seed $seed: leaves after step $i")
        }
        return configuration
    }

    /** The one action of the relabelled chart in [replay]. */
    private data object Fire : Action

    // endregion
}
