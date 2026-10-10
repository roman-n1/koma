@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.ExperimentalActronApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure lane bookkeeping of the executor: what each policy does on admission, how a lane
 * frees its place when a command finishes, and what an exited activation takes with it.
 */
class LanesTest {

    private val net = LaneId("net")
    private val scope = ActivationId(1)

    private fun registration(id: Long, policy: ConcurrencyPolicy? = null, lane: LaneId? = policy?.let { net }, scope: ActivationId = this.scope) =
        CommandRegistration(CommandId(id), "cmd$id", scope, lane ?: CommandLane.Independent, policy ?: ConcurrencyPolicy.Independent)

    @Test
    fun aCommandWithoutALane_startsAtOnce() {
        val change = Lanes<String>().admit(registration(1))

        assertEquals(listOf(registration(1)), change.started)
        assertEquals(mapOf(CommandId(1) to registration(1)), change.lanes.running)
        assertTrue(change.lanes.queued.isEmpty() && change.cancelled.isEmpty() && change.abandoned.isEmpty())
    }

    @Test
    fun latest_supersedesTheRunningAndQueuedOfItsLane_andStarts() {
        val first = Lanes<String>().admit(registration(1, ConcurrencyPolicy.Latest)).lanes
        // A queued command in the lane (mixed policies are allowed, if odd): superseded as well.
        val withQueued = first.admit(registration(2, ConcurrencyPolicy.Sequential)).lanes
        assertEquals(listOf(registration(2)), withQueued.queued[net]?.map { it.copy(lane = CommandLane.Independent, policy = ConcurrencyPolicy.Independent) })

        val change = withQueued.admit(registration(3, ConcurrencyPolicy.Latest))

        assertEquals(listOf(registration(3, ConcurrencyPolicy.Latest)), change.started)
        assertEquals(setOf(CommandId(1), CommandId(2)), change.abandoned.map { it.registration.id }.toSet())
        assertTrue(change.abandoned.all { it.reason == AbandonReason.Superseded })
        assertEquals(setOf(CommandId(3)), change.lanes.running.keys, "the superseded ones left the lane at once")
        assertTrue(change.lanes.queued.isEmpty())
        // A late finish of the superseded command changes nothing.
        assertEquals(LaneChange(change.lanes), change.lanes.finished(CommandId(1)))
    }

    @Test
    fun sequential_queuesBehindTheRunningOne_andStartsTheNextOnFinish() {
        var lanes = Lanes<String>()
        lanes = lanes.admit(registration(1, ConcurrencyPolicy.Sequential)).lanes
        val queued = lanes.admit(registration(2, ConcurrencyPolicy.Sequential))
        assertTrue(queued.started.isEmpty())
        assertEquals(listOf(registration(2, ConcurrencyPolicy.Sequential)), queued.lanes.queued[net])
        lanes = queued.lanes.admit(registration(3, ConcurrencyPolicy.Sequential)).lanes

        val finished = lanes.finished(CommandId(1))

        assertEquals(listOf(registration(2, ConcurrencyPolicy.Sequential)), finished.started, "one at a time")
        assertEquals(setOf(CommandId(2)), finished.lanes.running.keys)
        assertEquals(listOf(registration(3, ConcurrencyPolicy.Sequential)), finished.lanes.queued[net])
        val last = finished.lanes.finished(CommandId(2))
        assertEquals(listOf(registration(3, ConcurrencyPolicy.Sequential)), last.started)
        assertTrue(last.lanes.queued.isEmpty(), "an emptied lane is absent")
    }

    @Test
    fun parallel_runsUpToTheLimit_andFillsTheFreedPlaces() {
        val policy = ConcurrencyPolicy.Parallel(2)
        var lanes = Lanes<String>()
        for (id in 1L..4L) lanes = lanes.admit(registration(id, policy)).lanes
        assertEquals(setOf(CommandId(1), CommandId(2)), lanes.running.keys)
        assertEquals(listOf(CommandId(3), CommandId(4)), lanes.queued.getValue(net).map { it.id })

        val finished = lanes.finished(CommandId(2))

        assertEquals(listOf(CommandId(3)), finished.started.map { it.id })
        assertEquals(setOf(CommandId(1), CommandId(3)), finished.lanes.running.keys)
        assertEquals(listOf(CommandId(4)), finished.lanes.queued.getValue(net).map { it.id })
    }

    @Test
    fun dropIfRunning_abandonsTheNewOne_whileTheLaneIsBusy() {
        val lanes = Lanes<String>().admit(registration(1, ConcurrencyPolicy.DropIfRunning)).lanes

        val dropped = lanes.admit(registration(2, ConcurrencyPolicy.DropIfRunning))

        assertEquals(lanes, dropped.lanes, "nothing changed")
        assertEquals(listOf(LaneChange.Abandoned(registration(2, ConcurrencyPolicy.DropIfRunning), AbandonReason.Dropped)), dropped.abandoned)
        val free = lanes.finished(CommandId(1)).lanes
        assertEquals(listOf(CommandId(2)), free.admit(registration(2, ConcurrencyPolicy.DropIfRunning)).started.map { it.id })
    }

    @Test
    fun anExitedActivation_cancelsItsRunningCommands_andDropsItsQueuedOnes() {
        val other = ActivationId(2)
        var lanes = Lanes<String>()
        lanes = lanes.admit(registration(1, ConcurrencyPolicy.Sequential)).lanes
        lanes = lanes.admit(registration(2, ConcurrencyPolicy.Sequential, scope = other)).lanes
        lanes = lanes.admit(registration(3, ConcurrencyPolicy.Sequential)).lanes
        lanes = lanes.admit(registration(4)).lanes

        val exited = lanes.exited(listOf(scope))

        assertEquals(listOf(CommandId(1), CommandId(4)), exited.cancelled, "running ones are cancelled, in start order")
        assertTrue(exited.abandoned.isEmpty(), "nothing is reported: the decision deregistered them")
        assertEquals(setOf(CommandId(1), CommandId(4)), exited.lanes.running.keys, "a cancelled command holds its place until it has finished")
        assertEquals(listOf(CommandId(2)), exited.lanes.queued.getValue(net).map { it.id }, "the queued one of the exited scope is gone")
        // Only the finish frees the lane: then the other activation's command starts.
        val freed = exited.lanes.finished(CommandId(1))
        assertEquals(listOf(CommandId(2)), freed.started.map { it.id })
        assertEquals(LaneChange(exited.lanes), exited.lanes.exited(emptyList()))
    }

    @Test
    fun theLanesRefuseAnEmptyQueue() {
        assertFailsWith<IllegalArgumentException> { Lanes<String>(queued = mapOf(net to emptyList())) }
    }
}
