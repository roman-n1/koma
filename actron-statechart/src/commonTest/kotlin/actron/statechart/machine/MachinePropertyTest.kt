@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.RandomAction
import actron.statechart.RandomCharts
import actron.statechart.StateChartDefinition
import actron.statechart.TimerFired
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Property-based tests of [Machine] over [RandomCharts.forEachTimerChart]: random hierarchical
 * and parallel charts with history states and timers, random guard truth tables, enter rules
 * on random nodes, and random walks of dispatches, timer firings (live and stale) and command
 * results (live and stale).
 *
 * Properties: deciding the same snapshot and input twice gives equal decisions; a handled
 * decision advances the revision by one and an ignored one returns the same snapshot; every
 * active node has exactly one activation; commands and timers belong to live activations and
 * timers to active sources; a timer is due at `now + delay`; exited activations take their
 * commands and timers with them; ids only ever grow.
 */
class MachinePropertyTest {

    data class Ctx(val steps: Int = 0)

    data object Tick : Event

    /** A random truth value per guard label and action, and per label for timers: pure lookups. */
    private class GuardTable(random: Random) {
        private val actions = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        private val timers = RandomCharts.guards.associateWith { random.nextBoolean() }

        fun implementations(): Map<String, (MachineSnapshot<Ctx>, Action) -> Boolean> = RandomCharts.guards.associateWith { label ->
            { _: MachineSnapshot<Ctx>, action: Action ->
                if (action is TimerFired) timers.getValue(label) else actions.getValue(label).getValue(action as RandomAction)
            }
        }
    }

    private fun machineOf(seed: Int, random: Random, chart: StateChartDefinition): Machine<Ctx, RandomAction, String, Tick>? {
        val table = GuardTable(random)
        val withRules = chart.states.filter { random.nextInt(3) == 0 }.map { it.id }
        return try {
            Machine(DefinitionId("random-$seed"), DefinitionVersion("1"), chart) {
                table.implementations().forEach { (label, guard) -> guard(label, guard) }
                chart.transitions.mapNotNull { it.effect }.distinct().forEach { label -> effect(label) { context, _ -> context.copy(steps = context.steps + 1) } }
                withRules.forEach { id ->
                    onEnter(id) {
                        command("load $id", LaneId("lane-${id.value.hashCode() and 1}"), ConcurrencyPolicy.Latest)
                        if (node.value.length % 2 == 0) event(Tick)
                    }
                    onExit(id) { context = context.copy(steps = context.steps + 1) }
                }
            }
        } catch (e: IllegalArgumentException) {
            // Charts the Store would refuse too (undeclared endpoints, instant timer loops).
            null
        }
    }

    @Test
    fun randomWalks_areDeterministic_andKeepTheSnapshotInvariants() = RandomCharts.forEachTimerChart(count = 150) { seed, random, chart ->
        val machine = machineOf(seed, random, chart) ?: return@forEachTimerChart
        var snapshot = machine.initialSnapshot(Ctx())
        var now = MachineTime.Zero
        var maxActivation = 0L
        var maxCommand = 0L
        var maxTimer = 0L
        var maxEffect = 0L
        val inputs = 40

        repeat(inputs + 1) { step ->
            now += random.nextInt(1, 2_000).milliseconds
            val input: MachineInput<RandomAction> = when {
                step == 0 -> MachineInput.Start(now)
                else -> when (random.nextInt(6)) {
                    0 -> snapshot.timers.keys.randomOrNull(random)?.let { MachineInput.TimerFired(it, now) } ?: MachineInput.Dispatch(RandomCharts.actions.random(random), now)
                    1 -> MachineInput.TimerFired(TimerId(random.nextLong(1, maxTimer + 2)), now)
                    2 -> snapshot.commands.keys.randomOrNull(random)?.let { MachineInput.CommandResult(it, RandomCharts.actions.random(random), now) }
                        ?: MachineInput.Dispatch(RandomCharts.actions.random(random), now)
                    3 -> MachineInput.CommandResult(CommandId(random.nextLong(1, maxCommand + 2)), RandomCharts.actions.random(random), now)
                    4 -> snapshot.commands.keys.randomOrNull(random)?.let { MachineInput.CommandCompleted(it, now) } ?: MachineInput.Start(now)
                    else -> MachineInput.Dispatch(RandomCharts.actions.random(random), now)
                }
            }

            val decision = machine.decide(snapshot, input)
            assertEquals(decision, machine.decide(snapshot, input), "seed $seed step $step: deciding twice differs for $input")
            assertTrue(decision.outcome !is DecisionOutcome.Failed, "seed $seed step $step: ${decision.outcome}")

            if (decision.outcome is DecisionOutcome.Ignored) {
                assertSame(snapshot, decision.snapshot, "seed $seed step $step")
                assertTrue(decision.commands.isEmpty() && decision.timersScheduled.isEmpty() && decision.effects.isEmpty(), "seed $seed step $step")
                return@repeat
            }

            val next = decision.snapshot
            assertEquals(snapshot.revision + 1, next.revision, "seed $seed step $step")
            assertEquals(next.configuration.active, next.activations.keys, "seed $seed step $step: every active node has one activation")
            val live = next.activations.values.toSet()
            assertTrue(next.commands.values.all { it.scope in live }, "seed $seed step $step: commands belong to live activations")
            assertTrue(
                next.timers.values.all { it.activation in live && chart.transitions[it.transition.index].source in next.configuration.active },
                "seed $seed step $step: timers belong to live activations of active sources",
            )
            assertTrue(decision.timersScheduled.all { it.deadline == now + chart.transitions[it.transition.index].after!! }, "seed $seed step $step")
            assertEquals(decision.exited.map { it.id }, decision.cancelledScopes, "seed $seed step $step")
            val exitedIds = decision.exited.map { it.id }.toSet()
            assertTrue(exitedIds.none { it in live }, "seed $seed step $step: an exited activation is gone")
            assertTrue(next.commands.values.none { it.scope in exitedIds }, "seed $seed step $step: commands of exited activations are gone")
            assertTrue(next.timers.values.none { it.activation in exitedIds }, "seed $seed step $step: timers of exited activations are gone")
            assertTrue(decision.commands.all { it.scope in decision.entered.map { a -> a.id } }, "seed $seed step $step: new commands belong to entered activations")

            decision.entered.forEach { assertTrue(it.id.value > maxActivation, "seed $seed step $step: activation ids grow"); maxActivation = it.id.value }
            decision.commands.forEach { assertTrue(it.id.value > maxCommand, "seed $seed step $step: command ids grow"); maxCommand = it.id.value }
            decision.timersScheduled.forEach { assertTrue(it.id.value > maxTimer, "seed $seed step $step: timer ids grow"); maxTimer = it.id.value }
            decision.effects.forEach { assertTrue(it.id.value > maxEffect, "seed $seed step $step: effect ids grow"); maxEffect = it.id.value }
            assertEquals(MachineCounters(maxActivation, maxCommand, maxTimer, maxEffect), next.counters, "seed $seed step $step")

            snapshot = next
        }
    }
}
