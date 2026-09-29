package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Store
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Scratch fuzzing of the statechart tooling against the runtime (round 4 review). Deleted after the review. */
@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class ScratchFuzzTest {

    private data object NoEvent : Event

    private data object Inject : Action

    private data object Fire : Action

    private val seeds: List<Int> = List(1200) { it * 104729 + 3 }

    private fun log(msg: String) = println("SCRATCH $msg")

    private val trueGuards: Map<String, (RandomState, Action) -> Boolean> =
        RandomCharts.guards.associateWith { { _: RandomState, _: Action -> true } }

    private fun randomTimerChart(seed: Int, random: Random): StateChartDefinition {
        val base = if (seed % 3 == 0) RandomCharts.hierarchicalChart(random) else RandomCharts.parallelChart(random)
        return RandomCharts.withTimers(random, RandomCharts.withHistory(random, base))
    }

    private fun StateChartDefinition.dump(): String = buildString {
        append("initial=").append(initial).append("; states=")
        append(
            states.joinToString { n ->
                when (n) {
                    is AtomicState -> "A(${n.id}<${n.parent})"
                    is CompoundState -> "C(${n.id}<${n.parent}, init=${n.initial})"
                    is ParallelState -> "P(${n.id}<${n.parent})"
                    is HistoryState -> "H(${n.id}<${n.parent}, deep=${n.deep}, default=${n.default})"
                }
            },
        )
        append("; transitions=")
        append(
            transitions.joinToString { t ->
                val trigger = when (val tr = t.trigger) {
                    is Trigger.After -> "after ${tr.delay}"
                    is Trigger.OnAction -> tr.matcher.name
                }
                "${t.source} -> ${t.target} [$trigger${t.guard?.let { " if $it" } ?: ""}]"
            },
        )
    }

    private class Table(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        private val timers = RandomCharts.guards.associateWith { random.nextBoolean() }

        fun holds(label: String, action: Action): Boolean = when (action) {
            is TimerFired -> timers.getValue(label)
            is RandomAction -> table.getValue(label).getValue(action)
            else -> false
        }

        fun runtimeGuards(): Map<String, (RandomState, Action) -> Boolean> =
            RandomCharts.guards.associateWith { label -> { _: RandomState, a: Action -> holds(label, a) } }
    }

    // region 1: instant timer cycles vs runtime simulation

    private fun withInstantTimers(random: Random, chart: StateChartDefinition): StateChartDefinition {
        val sources = chart.states.filter { it !is HistoryState }.map { it.id }
        if (sources.isEmpty()) return chart
        val targets = chart.states.map { it.id }
        val transitions = chart.transitions.toMutableList()
        val delays = listOf(Duration.ZERO, Duration.ZERO, (-1).seconds, 1.seconds)
        repeat(random.nextInt(1, 6)) {
            val source = sources.random(random)
            val target = when (random.nextInt(4)) {
                0 -> source
                1 -> (chart.ancestorsOf(source) + source).random(random)
                else -> targets.random(random)
            }
            val guard = if (random.nextInt(4) == 0) RandomCharts.guards.random(random) else null
            transitions.add(random.nextInt(transitions.size + 1), Transition(source, target, Trigger.After(delays.random(random)), guard))
        }
        return chart.copy(transitions = transitions)
    }

    private data class TimerNode(val configuration: StateConfiguration, val timer: Transition)

    private fun reachableConfigurations(chart: StateChartDefinition): List<StateConfiguration> =
        ParallelReference(chart).distances().keys.map { StateConfiguration(it.active, it.history) }

    private fun simulatedInstantLoop(chart: StateChartDefinition, configurations: List<StateConfiguration>): List<Transition>? {
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, trueGuards)
        val instant = chart.transitions.filter { it.after?.isPositive() == false }.toSet()
        if (instant.isEmpty()) return null
        val done = HashSet<TimerNode>()
        fun dfs(node: TimerNode, stack: MutableList<TimerNode>): List<Transition>? {
            val at = stack.indexOf(node)
            if (at >= 0) return stack.drop(at).map { it.timer }
            if (node in done) return null
            stack += node
            val result = runtime.fire(node.configuration, RandomState(node.timer.source), node.timer)
            if (result is StepResult.Transitioned) {
                for (next in result.timersToStart) {
                    if (next !in instant) continue
                    dfs(TimerNode(result.configuration, next), stack)?.let { return it }
                }
            }
            stack.removeAt(stack.size - 1)
            done += node
            return null
        }
        for (configuration in configurations) {
            for (timer in instant) {
                if (timer.source !in configuration.active) continue
                dfs(TimerNode(configuration, timer), mutableListOf())?.let { return it }
            }
        }
        return null
    }

    private fun cycleReplays(chart: StateChartDefinition, cycle: List<Transition>, configurations: List<StateConfiguration>): Boolean {
        val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, trueGuards)
        return configurations.any { start ->
            var current = start
            var started: List<Transition>? = null
            for (timer in cycle) {
                if (started != null && timer !in started) return@any false
                if (timer.source !in current.active) return@any false
                val result = runtime.fire(current, RandomState(timer.source), timer) as? StepResult.Transitioned ?: return@any false
                current = result.configuration
                started = result.timersToStart
            }
            cycle.first() in started!!
        }
    }

    @Test
    fun instantTimerCyclesAgreeWithRuntimeSimulation() {
        var tried = 0
        var withLoops = 0
        var withInstant = 0
        val findings = mutableListOf<String>()
        for (seed in seeds) {
            val random = Random(seed)
            val chart = withInstantTimers(random, randomTimerChart(seed, random))
            tried++
            val configurations = reachableConfigurations(chart)
            val reported = chart.instantTimerCycles()
            val simulated = simulatedInstantLoop(chart, configurations)
            if (chart.transitions.any { it.after?.isPositive() == false }) withInstant++
            if (simulated != null) withLoops++
            if (reported.isEmpty() != (simulated == null)) {
                findings += "seed $seed: library=$reported simulation=$simulated chart=${chart.dump()}"
            }
            for (cycle in reported) {
                if (!cycleReplays(chart, cycle, configurations)) findings += "seed $seed: reported cycle does not replay: $cycle chart=${chart.dump()}"
            }
            val storeRejects = runCatching {
                StateChartStore<Int, Action, NoEvent>(chart, 0) {
                    RandomCharts.guards.forEach { g -> guard(g) { _, _ -> true } }
                    listOf("effect 0", "effect 1", "effect 2").forEach { e -> effect(e) { c, _ -> c } }
                }.close()
            }.isFailure
            if (storeRejects != reported.isNotEmpty()) findings += "seed $seed: store rejects=$storeRejects but cycles=$reported"
        }
        log("instant-timers: tried=$tried withInstant=$withInstant withLoops=$withLoops findings=${findings.size}")
        findings.take(15).forEach { log(it) }
        assertTrue(findings.isEmpty(), findings.take(5).joinToString("\n"))
    }

    // endregion

    // region 1b: validate vs runtime

    private fun isWarning(issue: ValidationIssue) =
        issue is ValidationIssue.TooFewRegions || issue is ValidationIssue.UnreachableState ||
            issue is ValidationIssue.AmbiguousTransitions || issue is ValidationIssue.AmbiguousTimers ||
            issue is ValidationIssue.ShadowedTransitions || issue is ValidationIssue.NonPositiveDelay ||
            issue is ValidationIssue.TransitionFromHistory

    /** Explores every configuration the runtime's step can reach; returns the first inconsistency found. */
    private fun explore(chart: StateChartDefinition, limit: Int = 3000): String? {
        val declared = chart.states.filter { it !is HistoryState }.map { it.id }.toSet()
        val initial = chart.initialConfiguration()
        val seen = linkedSetOf(initial)
        val queue = ArrayDeque(listOf(initial))
        fun check(configuration: StateConfiguration, how: String): String? {
            if (!chart.isConsistent(configuration)) return "inconsistent configuration ${configuration.active} after $how"
            if (configuration.active.any { it !in declared }) return "undeclared or history node active in ${configuration.active} after $how"
            return null
        }
        check(initial, "start")?.let { return it }
        while (queue.isNotEmpty() && seen.size < limit) {
            val current = queue.removeFirst()
            for (transition in chart.transitions) {
                if (transition.source !in current.active) continue
                val step = try {
                    chart.graphStep(current, transition)
                } catch (e: Exception) {
                    return "exception ${e::class.simpleName}: ${e.message} on $transition from ${current.active}"
                }
                if (step.entered.any { chart.node(it) is HistoryState }) return "history state entered by $transition from ${current.active}"
                check(step.configuration, "$transition from ${current.active}")?.let { return it }
                if (seen.add(step.configuration)) queue.addLast(step.configuration)
            }
        }
        return null
    }

    @Test
    fun validateAgreesWithWhatTheRuntimeDoes() {
        var tried = 0
        var clean = 0
        var rejectedMisbehaving = 0
        var rejectedBenign = 0
        val benignKinds = mutableMapOf<String, Int>()
        val findings = mutableListOf<String>()
        for (seed in seeds) {
            val random = Random(seed)
            val chart = when (seed % 6) {
                0 -> RandomCharts.chart(random, valid = false)
                1 -> RandomCharts.malformed(random, RandomCharts.hierarchicalChart(random))
                2 -> RandomCharts.malformedHistory(random, RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random)))
                3 -> RandomCharts.malformedParallel(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random)))
                4 -> RandomCharts.malformedTimers(random, RandomCharts.withTimers(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random))))
                else -> randomTimerChart(seed, random)
            }
            tried++
            val hard = chart.validate().filterNot(::isWarning)
            val runtime = runCatching { StateChartRuntime(chart, { s: RandomState -> s.id }, trueGuards) }
            val problem = if (runtime.isSuccess) explore(chart) else "runtime rejects: ${runtime.exceptionOrNull()?.message}"
            when {
                hard.isEmpty() && problem != null -> findings += "seed $seed: validate() is clean but $problem; chart=${chart.dump()}"
                hard.isEmpty() -> clean++
                problem == null -> {
                    rejectedBenign++
                    val reachable = chart.reachableStates()
                    hard.map { issue ->
                        when (issue) {
                            is ValidationIssue.UnknownTransitionTarget -> "UnknownTransitionTarget(sourceReachable=${issue.transition.source in reachable})"
                            is ValidationIssue.UnknownTransitionSource -> "UnknownTransitionSource"
                            is ValidationIssue.DuplicateStateId -> "DuplicateStateId(identical=${chart.states.filter { it.id == issue.id }.distinct().size == 1})"
                            else -> issue::class.simpleName!!
                        }
                    }.distinct().forEach { benignKinds[it] = (benignKinds[it] ?: 0) + 1 }
                }
                else -> rejectedMisbehaving++
            }
        }
        log("validate-vs-runtime: tried=$tried clean=$clean rejectedMisbehaving=$rejectedMisbehaving rejectedBenign=$rejectedBenign kinds=$benignKinds")
        findings.take(15).forEach { log(it) }
        assertTrue(findings.isEmpty(), findings.take(5).joinToString("\n"))
    }

    // endregion

    // region 5: runtime invariants and history record vs re-entry

    private var restoreChecks = 0

    private fun invariantProblems(chart: StateChartDefinition, before: StateConfiguration, result: StepResult.Transitioned): List<String> {
        val problems = mutableListOf<String>()
        val after = result.configuration
        if (!chart.isConsistent(after)) problems += "inconsistent after step: ${after.active}"
        if (result.entered.any { chart.node(it) is HistoryState } || after.active.any { chart.node(it) is HistoryState }) problems += "history state entered"
        fun depth(id: StateId) = chart.ancestorsOf(id).size
        if (result.exited.zipWithNext().any { (a, b) -> depth(a) < depth(b) }) problems += "exit order not innermost-first: ${result.exited}"
        if (result.entered.zipWithNext().any { (a, b) -> depth(a) > depth(b) }) problems += "entry order not outermost-first: ${result.entered}"
        val exited = result.exited.toSet()
        val entered = result.entered.toSet()
        if (result.exited.size != exited.size || result.entered.size != entered.size) problems += "duplicates in exited/entered"
        if (exited.any { it !in before.active }) problems += "exited a node that was not active"
        for (x in exited) for (d in before.active) if (chart.isDescendant(d, x) && d !in exited) problems += "exited $x but not its active descendant $d"
        for (y in entered) {
            val p = chart.node(y)?.parent
            if (p != null && p !in entered && (p !in before.active || p in exited)) problems += "entered $y without its parent $p"
        }
        for (y in entered) if (y in before.active && y !in exited) problems += "re-entered $y without exiting it"
        if (after.active != before.active - exited + entered) problems += "configuration != before - exited + entered"
        if (result.timersToStart != result.entered.flatMap { id -> chart.transitions.filter { it.isTimer && it.source == id } }) problems += "timersToStart mismatch"
        if (result.timersToCancel != result.exited.flatMap { id -> chart.transitions.filter { it.isTimer && it.source == id } }) problems += "timersToCancel mismatch"
        // Every transition's source was active and is exited (all transitions are external).
        for (t in result.transitions) {
            if (t.source !in before.active) problems += "taken ${t.source} was not active"
            if (t.source !in exited) problems += "source ${t.source} of a taken transition not exited"
        }
        // History: what was recorded restores exactly what was active.
        for (x in result.exited) {
            for (h in chart.childrenOf(x).filterIsInstance<HistoryState>()) {
                val recorded = after.history[h.id]
                val expectedRecord = if (h.deep) {
                    chart.activeLeaves(before).filter { chart.isDescendant(it, x) }.toSet()
                } else {
                    chart.childrenOf(x).filter { it !is HistoryState && it.id in before.active }.map { it.id }.toSet()
                }
                if (recorded != expectedRecord) {
                    problems += "history ${h.id} recorded $recorded, expected $expectedRecord"
                    continue
                }
                for (source in chart.activeLeaves(after)) {
                    restoreChecks++
                    val restore = chart.microstep(after, listOf(Transition(source, h.id, ActionMatcher("Probe"))))
                    val restoredLeavesInside = chart.activeLeaves(restore.configuration).filter { chart.isDescendant(it, x) }.toSet()
                    val restoredChildren = chart.childrenOf(x).filter { it !is HistoryState && it.id in restore.configuration.active }.map { it.id }.toSet()
                    if (!chart.isConsistent(restore.configuration)) problems += "restore via ${h.id} from $source inconsistent: ${restore.configuration.active}"
                    if (h.deep && restoredLeavesInside != expectedRecord) problems += "deep restore of ${h.id} from $source gave $restoredLeavesInside, recorded $expectedRecord"
                    if (!h.deep && restoredChildren != expectedRecord) problems += "shallow restore of ${h.id} from $source gave $restoredChildren, recorded $expectedRecord"
                    if (restore.entered.any { chart.node(it) is HistoryState }) problems += "restore entered a history state"
                }
            }
        }
        return problems
    }

    @Test
    fun runtimeStepsKeepTheInvariantsAndHistoryRestoresWhatWasRecorded() {
        var tried = 0
        var steps = 0
        var timerSteps = 0
        val findings = mutableListOf<String>()
        for (seed in seeds) {
            val random = Random(seed)
            val chart = randomTimerChart(seed, random)
            val table = Table(random)
            val runtime = StateChartRuntime(chart, { s: RandomState -> s.id }, table.runtimeGuards())
            var configuration = runtime.initialConfiguration()
            val running = runtime.initialTimers().toMutableList()
            tried++
            if (!chart.isConsistent(configuration)) findings += "seed $seed: initial configuration inconsistent ${configuration.active}"
            repeat(50) {
                val state = RandomState(chart.activeLeaves(configuration).first())
                val timer = random.nextInt(10) < 4 && running.isNotEmpty()
                val result = if (timer) runtime.fire(configuration, state, running.random(random)) else runtime.step(configuration, state, RandomCharts.actions.random(random))
                if (result !is StepResult.Transitioned) return@repeat
                steps++
                if (timer) timerSteps++
                val problems = invariantProblems(chart, configuration, result)
                if (problems.isNotEmpty()) findings += "seed $seed: ${problems.joinToString()}; before=${configuration.active}; step=${result.transitions}; chart=${chart.dump()}"
                for (t in result.timersToCancel) running.remove(t)
                running += result.timersToStart
                configuration = result.configuration
            }
        }
        log("runtime-invariants: tried=$tried steps=$steps timerSteps=$timerSteps restoreChecks=$restoreChecks findings=${findings.size}")
        findings.take(15).forEach { log(it) }
        assertTrue(findings.isEmpty(), findings.take(5).joinToString("\n"))
    }

    // endregion

    // region 2: paths replay on the runtime, minimality, timing

    /** Replays [path] on a runtime over a copy of the chart whose guards enable exactly the graph step. */
    private fun replay(chart: StateChartDefinition, path: StateChartPath): String? {
        var trigger: Transition? = null
        var configuration = chart.initialConfiguration()
        val fire = ActionMatcher("Fire", Fire::class)
        val relabelled = chart.transitions.mapIndexed { i, t -> if (t.isTimer) t.copy(guard = "t$i") else Transition(t.source, t.target, fire, "t$i") }
        val guards = chart.transitions.withIndex().associate { (i, original) ->
            "t$i" to { _: RandomState, _: Action ->
                val t = trigger!!
                original == t || (original.on == t.on && chart.exitSet(configuration, original).none { it in chart.exitSet(configuration, t) })
            }
        }
        val runtime = StateChartRuntime(chart.copy(transitions = relabelled), { s: RandomState -> s.id }, guards)
        if (chart.activeLeaves(configuration) != path.startLeaves) return "start leaves ${path.startLeaves} != ${chart.activeLeaves(configuration)}"
        if (path.start != path.startLeaves.first()) return "start != startLeaves.first()"
        path.transitions.forEachIndexed { i, t ->
            if (t.source !in configuration.active) return "step $i: source ${t.source} not active"
            trigger = t
            val result = if (t.isTimer) {
                runtime.fire(configuration, RandomState(t.source), relabelled[chart.transitions.indexOf(t)])
            } else {
                runtime.step(configuration, RandomState(t.source), Fire)
            }
            val stepped = result as? StepResult.Transitioned ?: return "step $i: ignored"
            val taken = stepped.transitions.map { rt -> chart.transitions[relabelled.indexOf(rt)] }
            if (t !in taken) return "step $i: $t not taken, took $taken"
            configuration = stepped.configuration
            if (chart.activeLeaves(configuration) != path.activeLeaves[i]) return "step $i: leaves ${chart.activeLeaves(configuration)} != ${path.activeLeaves[i]}"
            if (path.leaves[i] != path.activeLeaves[i].first()) return "step $i: leaves[i] != activeLeaves[i].first()"
        }
        return null
    }

    @Test
    fun pathsReplayOnTheRuntimeAndAreMinimal() {
        var tried = 0
        var paths = 0
        var maxMillis = 0L
        var slowest = 0
        val findings = mutableListOf<String>()
        for (seed in seeds) {
            val random = Random(seed)
            val chart = randomTimerChart(seed, random)
            tried++
            val mark = TimeSource.Monotonic.markNow()
            val shortest = chart.states.map { it.id }.distinct().associateWith { chart.shortestPathTo(it) }
            val coverage = chart.transitionCoveragePaths()
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            if (elapsed > maxMillis) {
                maxMillis = elapsed
                slowest = seed
            }
            val distances = ParallelReference(chart).nodeDistances()
            for ((id, path) in shortest) {
                val distance = distances[id]
                if ((path == null) != (distance == null)) {
                    findings += "seed $seed: path to $id is $path but reference distance $distance"
                    continue
                }
                if (path == null) continue
                paths++
                if (path.transitions.size != distance) findings += "seed $seed: path to $id has ${path.transitions.size} steps, reference $distance"
                if (id !in chart.configurationOf(path.activeLeaves.lastOrNull() ?: path.startLeaves).active) findings += "seed $seed: path to $id does not end with $id active"
                replay(chart, path)?.let { findings += "seed $seed: shortest path to $id: $it" }
            }
            val reachableSources = chart.transitions.filter { it.source in distances }.toSet()
            val covered = coverage.flatMap { it.transitions }.toSet()
            if (covered != reachableSources) findings += "seed $seed: coverage misses ${reachableSources - covered} or adds ${covered - reachableSources}"
            for (path in coverage) {
                paths++
                replay(chart, path)?.let { findings += "seed $seed: coverage path: $it" }
                val last = path.transitions.last()
                val prefix = chart.shortestPathTo(last.source)
                if (prefix == null || prefix.transitions != path.transitions.dropLast(1)) findings += "seed $seed: coverage path prefix for $last is not the shortest path to its source"
            }
        }
        log("paths: tried=$tried paths=$paths maxMillis=$maxMillis (seed $slowest) findings=${findings.size}")
        findings.take(15).forEach { log(it) }
        assertTrue(findings.isEmpty(), findings.take(5).joinToString("\n"))
    }

    private fun wideParallel(regions: Int, size: Int, history: Boolean, instantTimer: Boolean): StateChartDefinition {
        val states = mutableListOf<StateNode>()
        val transitions = mutableListOf<Transition>()
        val p = StateId("P")
        val out = StateId("Out")
        val c = StateId("C")
        val h = StateId("H")
        if (history) {
            states += AtomicState(out)
            states += CompoundState(c, initial = p)
            states += HistoryState(h, parent = c, deep = true)
            states += ParallelState(p, parent = c)
        } else {
            states += ParallelState(p)
        }
        for (r in 0 until regions) {
            val region = StateId("R$r")
            states += CompoundState(region, initial = StateId("R${r}_0"), parent = p)
            for (s in 0 until size) states += AtomicState(StateId("R${r}_$s"), parent = region)
            for (s in 0 until size) transitions += Transition(StateId("R${r}_$s"), StateId("R${r}_${(s + 1) % size}"), ActionMatcher("Next$r"))
        }
        if (history) {
            transitions += Transition(p, out, ActionMatcher("Leave"))
            transitions += Transition(out, h, ActionMatcher("Back"))
        }
        if (instantTimer) transitions += Transition(StateId("R0_0"), StateId("R0_1"), Trigger.After(Duration.ZERO))
        return StateChartDefinition(if (history) out else p, states, transitions)
    }

    @Test
    fun configurationGraphBlowupTiming() {
        val shapes = listOf(
            Triple(3, 4, false), Triple(5, 4, false), Triple(6, 4, false), Triple(7, 4, false),
            Triple(3, 4, true), Triple(4, 4, true), Triple(5, 3, true), Triple(8, 4, false),
        )
        for ((regions, size, history) in shapes) {
            val chart = wideParallel(regions, size, history, instantTimer = false)
            val mark = TimeSource.Monotonic.markNow()
            val reachable = chart.reachableStates().size
            val t1 = mark.elapsedNow()
            val configs = chart.configurationGraph.reachable.size
            val paths = chart.transitionCoveragePaths().size
            val t2 = mark.elapsedNow()
            val issues = chart.validate().size
            val t3 = mark.elapsedNow()
            val withTimer = wideParallel(regions, size, history, instantTimer = true)
            val mark2 = TimeSource.Monotonic.markNow()
            val cycles = withTimer.instantTimerCycles().size
            val t4 = mark2.elapsedNow()
            val mark3 = TimeSource.Monotonic.markNow()
            val storeOk = runCatching { StateChartStore<Int, Action, NoEvent>(withTimer, 0).close() }.isSuccess
            val t5 = mark3.elapsedNow()
            log("blowup regions=$regions size=$size history=$history: states=${chart.states.size} configurations=$configs reachable=$reachable in $t1, coverage($paths paths) by $t2, validate($issues) by $t3; instantTimerCycles=$cycles in $t4; StateChartStore(ok=$storeOk) in $t5")
            if (t3 > 90.seconds || t4 > 90.seconds) {
                log("blowup: stopping, too slow")
                break
            }
        }
    }

    // endregion

    // region 3: Mermaid

    private val extraTricky = listOf(
        "a{b}", "%%c", "x\ny", "a;b", "--", "  ", "state Foo", "a --> b", "a:b:c", "#1", "q\"q", "note left of x",
        "end.", "[*]x", "é\tè", "\\", "a\rb", "as", "koma_state_5", "P", "R0", "{", "}", "a}b{",
    )

    private val trickyLabels = listOf("x;y", "a\nb", "%%c", "q\"q", "[g]", "a/b", "c:d", "--", "state", "end", "a{b}", "}", "Go [x] / y")

    private fun trickify(random: Random, chart: StateChartDefinition): StateChartDefinition {
        val ids = chart.states.map { it.id }.distinct()
        val renamed = ids.filter { random.nextInt(3) == 0 }.associateWith { StateId(extraTricky.random(random) + random.nextInt(3)) }
        fun f(id: StateId) = renamed[id] ?: id
        fun node(n: StateNode): StateNode = when (n) {
            is AtomicState -> AtomicState(f(n.id), n.parent?.let(::f))
            is CompoundState -> CompoundState(f(n.id), f(n.initial), n.parent?.let(::f))
            is ParallelState -> ParallelState(f(n.id), n.parent?.let(::f))
            is HistoryState -> HistoryState(f(n.id), f(n.parent), n.deep, n.default?.let(::f))
        }
        val transitions = chart.transitions.map { t ->
            val trigger = when (val tr = t.trigger) {
                is Trigger.OnAction -> if (random.nextInt(4) == 0) Trigger.OnAction(ActionMatcher(trickyLabels.random(random))) else tr
                is Trigger.After -> tr
            }
            Transition(
                f(t.source),
                f(t.target),
                trigger,
                if (random.nextInt(4) == 0) trickyLabels.random(random) else t.guard,
                if (random.nextInt(4) == 0) trickyLabels.random(random) else t.effect,
            )
        }
        return StateChartDefinition(f(chart.initial), chart.states.map(::node), transitions)
    }

    private val mermaidId = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val mermaidKeywords = setOf("state", "note", "direction", "class", "classdef", "style", "scale", "hide", "end", "click", "acctitle", "accdescr")

    private fun checkMermaid(chart: StateChartDefinition): List<String> {
        val problems = mutableListOf<String>()
        val text = chart.toMermaid()
        val lines = text.lines()
        if (lines.first() != "stateDiagram-v2") problems += "header '${lines.first()}'"
        var depth = 0
        val declared = mutableMapOf<String, String?>()
        val referenced = mutableSetOf<String>()
        var startLines = 0
        var transitionLines = 0
        var defaultLines = 0
        var blocks = 0
        val start = Regex("""^\[\*\] --> (\S+)$""")
        val arrow = Regex("""^(\S+) --> (\S+)(?: : (.*))?$""")
        val aliasDecl = Regex("""^state "(.*)" as (\S+)( \{)?$""")
        val blockDecl = Regex("""^state (\S+) \{$""")
        fun id(ref: String, where: String) {
            if (!mermaidId.matches(ref) || ref.lowercase() in mermaidKeywords) problems += "bad id '$ref' in $where"
        }
        fun declare(ref: String, label: String?, where: String) {
            id(ref, where)
            if (ref in declared) problems += "duplicate declaration of $ref in $where"
            declared[ref] = label
        }
        for ((i, raw) in lines.withIndex().drop(1)) {
            val line = raw.trim()
            val indent = raw.length - raw.trimStart().length
            val expected = 4 * (if (line == "}") depth else depth + 1)
            if (indent != expected) problems += "line $i indent $indent != $expected: '$raw'"
            when {
                line == "}" -> {
                    depth--
                    if (depth < 0) problems += "unbalanced } at line $i"
                }
                line == "--" -> if (depth == 0) problems += "-- at top level"
                start.matches(line) -> {
                    val ref = start.matchEntire(line)!!.groupValues[1]
                    id(ref, "start line $i")
                    referenced += ref
                    startLines++
                }
                aliasDecl.matches(line) -> {
                    val m = aliasDecl.matchEntire(line)!!
                    val label = m.groupValues[1]
                    if ('"' in label || '\n' in label || '\r' in label || ';' in label) problems += "unsafe label '$label' at line $i"
                    declare(m.groupValues[2], label, "alias line $i")
                    if (m.groupValues[3].isNotEmpty()) {
                        depth++
                        blocks++
                    }
                }
                blockDecl.matches(line) -> {
                    declare(blockDecl.matchEntire(line)!!.groupValues[1], null, "block line $i")
                    depth++
                    blocks++
                }
                arrow.matches(line) -> {
                    val m = arrow.matchEntire(line)!!
                    id(m.groupValues[1], "arrow line $i")
                    id(m.groupValues[2], "arrow line $i")
                    referenced += m.groupValues[1]
                    referenced += m.groupValues[2]
                    val label = m.groups[3]?.value
                    if (label == null) defaultLines++ else transitionLines++
                    if (label != null && (';' in label || '\n' in label)) problems += "unsafe transition label '$label'"
                }
                mermaidId.matches(line) && line.lowercase() !in mermaidKeywords -> declare(line, null, "bare line $i")
                else -> problems += "unparsable line $i: '$raw'"
            }
        }
        if (depth != 0) problems += "unbalanced braces (depth $depth at end)"
        referenced.filter { it !in declared }.forEach { problems += "referenced but never declared: $it" }
        val compoundInitials = chart.states.filterIsInstance<CompoundState>().map { it.initial }
        val histories = chart.states.filterIsInstance<HistoryState>()
        val ids = (chart.states.map { it.id } + chart.initial + compoundInitials + histories.mapNotNull { it.default } + chart.transitions.flatMap { listOf(it.source, it.target) }).distinct()
        val known = declared.keys + referenced
        if (known.size != ids.size) problems += "diagram knows ${known.size} refs for ${ids.size} ids (declared=${declared.size}, referenced=${referenced.size})"
        if (transitionLines != chart.transitions.size) problems += "$transitionLines transition lines for ${chart.transitions.size} transitions"
        if (defaultLines != histories.count { it.default != null }) problems += "$defaultLines default lines for ${histories.count { it.default != null }} defaults"
        val compoundBlocks = chart.node(chart.initial).let { chart.states.map { it.id }.distinct().count { id -> chart.node(id) is CompoundState } }
        if (startLines != 1 + compoundBlocks) problems += "$startLines [*] lines for $compoundBlocks compound states"
        // Labels are the ids (with " -> ')
        for ((ref, label) in declared) {
            if (label == null || label == "[H]" || label == "[H*]") continue
            val matches = ids.filter { it.value.replace('"', '\'').replace(Regex("\r\n|\r|\n"), " ").replace(';', ',') == label }
            if (matches.isEmpty()) problems += "label '$label' of $ref is not an id"
        }
        return problems
    }

    @Test
    fun mermaidIsWellFormedForEveryChart() {
        var tried = 0
        var printed = 0
        val findings = mutableListOf<String>()
        val kinds = mutableMapOf<String, Int>()
        for (seed in seeds) {
            val random = Random(seed)
            val base = when (seed % 7) {
                0 -> RandomCharts.chart(random, valid = false)
                1 -> RandomCharts.malformed(random, RandomCharts.hierarchicalChart(random))
                2 -> RandomCharts.malformedHistory(random, RandomCharts.withHistory(random, RandomCharts.hierarchicalChart(random)))
                3 -> RandomCharts.malformedParallel(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random)))
                4 -> RandomCharts.malformedTimers(random, RandomCharts.withTimers(random, RandomCharts.withHistory(random, RandomCharts.parallelChart(random))))
                5 -> RandomCharts.withHistory(random, RandomCharts.parallelChart(random))
                else -> randomTimerChart(seed, random)
            }
            val chart = if (random.nextBoolean()) trickify(random, base) else base
            tried++
            val problems = checkMermaid(chart)
            for (p in problems) {
                val kind = p.substringBefore(" ").substringBefore(":")
                kinds[kind] = (kinds[kind] ?: 0) + 1
            }
            if (problems.isNotEmpty()) findings += "seed $seed: ${problems.joinToString()}; chart=${chart.dump()}\n${chart.toMermaid()}"
            if (printed < 700) {
                printed++
                println("MERMAID-BEGIN $seed")
                println(chart.toMermaid())
                println("MERMAID-END")
            }
        }
        log("mermaid: tried=$tried findings=${findings.size} kinds=$kinds")
        findings.take(12).forEach { log(it) }
        assertTrue(findings.isEmpty(), findings.take(3).joinToString("\n"))
    }

    // endregion

    // region 4: conformance against a real StateChartStore

    private sealed interface Step {
        data class Act(val action: Action) : Step
        data class Advance(val duration: Duration) : Step
    }

    private val advances = listOf(300.milliseconds, 500.milliseconds, 1.seconds, 2.seconds, 3.seconds, 5.seconds, 10.seconds, 90.seconds)

    private fun TestScope.chartStore(
        chart: StateChartDefinition,
        table: Table,
        conformance: StateChartConformance<ChartState<Int>, Action, NoEvent>,
    ): Store<ChartState<Int>, Action, NoEvent> = StateChartStore<Int, Action, NoEvent>(chart, 0, backgroundScope.coroutineContext) {
        RandomCharts.guards.forEach { label -> guard(label) { _, action -> table.holds(label, action) } }
        listOf("effect 0", "effect 1", "effect 2").forEach { label -> effect(label) { context, _ -> context + 1 } }
        store { plugin(conformance) }
    }

    private suspend fun TestScope.walk(store: Store<ChartState<Int>, Action, NoEvent>, chart: StateChartDefinition, schedule: List<Step>, trace: MutableList<String>, conformance: StateChartConformance<ChartState<Int>, Action, NoEvent>) {
        for (step in schedule) {
            when (step) {
                is Step.Act -> store.dispatchAndAwait(step.action)
                is Step.Advance -> advanceTimeBy(step.duration)
            }
            runCurrent()
            trace += "$step -> ${store.currentState.activeLeaves(chart)} history=${store.currentState.configuration.history} violations=${conformance.violations.size}"
        }
    }

    private fun injection(chart: StateChartDefinition, configuration: StateConfiguration): Pair<StateId, StateId>? {
        for (leaf in chart.activeLeaves(configuration)) {
            val parent = chart.node(leaf)?.parent ?: continue
            if (chart.node(parent) !is CompoundState) continue
            val enterable = chart.transitions.filter { it.source in configuration.active }.flatMap { t ->
                val target = chart.node(t.target)
                if (target is HistoryState) {
                    chart.states.filter { chart.isDescendant(it.id, target.parent) || it.id == target.parent }.map { it.id }
                } else {
                    chart.microstep(configuration, listOf(t)).entered
                }
            }.toSet()
            val sibling = chart.childrenOf(parent).firstOrNull { it is AtomicState && it.id != leaf && it.id !in enterable } ?: continue
            return leaf to sibling.id
        }
        return null
    }

    @Test
    fun conformanceNeverFlagsAStateChartStoreAndCatchesAnInjectedTransition() = runTest(timeout = 20.minutes) {
        var tried = 0
        var injected = 0
        var timerChanges = 0
        val falsePositives = mutableListOf<String>()
        val falseNegatives = mutableListOf<String>()
        for (seed in seeds.take(700)) {
            val random = Random(seed)
            val chart = randomTimerChart(seed, random)
            val schedule = List(40) {
                if (random.nextInt(10) < 6) Step.Act(RandomCharts.actions.random(random)) else Step.Advance(advances.random(random))
            }
            val tableSeed = random.nextInt()
            tried++

            val conformance = StateChartConformance.withActiveLeaves<ChartState<Int>, Action, NoEvent>(chart) { it.activeLeaves(chart).toSet() }
            val store = chartStore(chart, Table(Random(tableSeed)), conformance)
            store.startAndAwait()
            runCurrent()
            val trace = mutableListOf<String>()
            walk(store, chart, schedule, trace, conformance)
            timerChanges += trace.count { it.startsWith("Advance") }
            if (conformance.violations.isNotEmpty()) {
                falsePositives += "seed $seed: ${conformance.violations}\n  chart=${chart.dump()}\n  " + trace.joinToString("\n  ")
            }
            val final = store.currentState.configuration
            store.close()
            runCurrent()

            val pair = injection(chart, final) ?: continue
            val (leaf, wrong) = pair
            val mutated = chart.copy(transitions = listOf(Transition(leaf, wrong, ActionMatcher("Inject"))) + chart.transitions)
            val conformance2 = StateChartConformance.withActiveLeaves<ChartState<Int>, Action, NoEvent>(chart) { it.activeLeaves(chart).toSet() }
            val store2 = chartStore(mutated, Table(Random(tableSeed)), conformance2)
            store2.startAndAwait()
            runCurrent()
            val trace2 = mutableListOf<String>()
            walk(store2, chart, schedule, trace2, conformance2)
            if (store2.currentState.configuration != final) {
                falseNegatives += "seed $seed: mutated store diverged before the injection"
            } else {
                store2.dispatchAndAwait(Inject)
                runCurrent()
                injected++
                if (wrong !in store2.currentState.configuration.active) {
                    falseNegatives += "seed $seed: injection $leaf -> $wrong was not taken"
                } else if (conformance2.violations.isEmpty()) {
                    falseNegatives += "seed $seed: injected $leaf -> $wrong not reported; chart=${chart.dump()}\n  " + trace2.joinToString("\n  ")
                }
            }
            store2.close()
            runCurrent()
        }
        log("conformance-store: tried=$tried injected=$injected timerAdvances=$timerChanges falsePositives=${falsePositives.size} falseNegatives=${falseNegatives.size}")
        falsePositives.take(8).forEach { log("FALSE POSITIVE $it") }
        falseNegatives.take(8).forEach { log("FALSE NEGATIVE $it") }
        assertTrue(falsePositives.isEmpty() && falseNegatives.isEmpty(), (falsePositives.take(2) + falseNegatives.take(2)).joinToString("\n"))
    }

    /**
     * Minimal reproducer: an unobserved self-loop of P (same leaf before and after) records
     * history that the plugin never sees; a later transition into that history from inside P
     * then restores something the plugin does not expect.
     */
    @Test
    fun conformanceHistoryStaleAfterUnobservedSelfLoop() = runTest {
        val (q, p, a, b, h) = listOf("Q", "P", "A", "B", "H").map(::StateId)
        val ping = ActionMatcher.of<RandomAction.Ping>("Ping")
        val pong = ActionMatcher.of<RandomAction.Pong>("Pong")
        val go = ActionMatcher.of<RandomAction.Go>("Go")
        val reset = ActionMatcher("Reset")
        val chart = StateChartDefinition(
            initial = q,
            states = listOf(AtomicState(q), CompoundState(p, initial = b), AtomicState(a, p), AtomicState(b, p), HistoryState(h, parent = p)),
            transitions = listOf(
                Transition(q, a, ping),
                Transition(a, q, pong),
                Transition(q, b, reset),
                Transition(p, p, go),
                Transition(b, a, pong),
                Transition(a, h, ping),
            ),
        )
        val conformance = StateChartConformance.withActiveLeaves<ChartState<Int>, Action, NoEvent>(chart) { it.activeLeaves(chart).toSet() }
        val store = chartStore(chart, Table(Random(1)), conformance)
        store.startAndAwait()
        val trace = mutableListOf<String>()
        for (action in listOf(RandomAction.Ping, RandomAction.Pong, RandomAction.Reset, RandomAction.Go(1), RandomAction.Pong, RandomAction.Ping)) {
            store.dispatchAndAwait(action)
            runCurrent()
            trace += "$action -> ${store.currentState.activeLeaves(chart)} history=${store.currentState.configuration.history} violations=${conformance.violations}"
        }
        log("history-staleness repro:\n  " + trace.joinToString("\n  "))
        store.close()
        assertTrue(conformance.violations.isEmpty(), "false positive: ${conformance.violations}")
    }

    // endregion
}
