package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Carries out the intents of committed [Decision]s: runs commands through the [CommandHandler]
 * under their lane policies, fires timers through the [MachineClock], and feeds what comes back
 * as [MachineInput]s.
 *
 * All bookkeeping lives in one actor coroutine, so it needs no lock: [apply] only enqueues a
 * message and returns, which is what a caller holding the store's lock may do. Commands and
 * timers run as children of [parent] in a scope of their own, cancelled by [close].
 */
@OptIn(ExperimentalKomaApi::class)
internal class CommandScheduler<C, A : Action, CMD, E : Event>(
    parent: CoroutineScope,
    private val handler: CommandHandler<CMD, A>,
    private val clock: MachineClock,
    private val feed: (MachineInput<A>) -> Unit,
    private val report: (Throwable) -> Unit,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val messages = Channel<Message>(Channel.UNLIMITED)

    // Actor state: touched only from `actor`.
    private val running = mutableMapOf<CommandId, Running<CMD>>()
    private val lanes = mutableMapOf<LaneId, Lane<CMD>>()
    private val timers = mutableMapOf<TimerId, Job>()

    private val actor: Job = scope.launch {
        for (message in messages) handle(message)
    }

    /**
     * Enqueues [decision]'s intents; returns at once. Safe to call under the store's lock.
     */
    fun apply(decision: Decision<C, CMD, E>) {
        messages.trySend(Message.Apply(decision))
    }

    /**
     * Cancels every command and timer and stops the actor.
     */
    fun close() {
        scope.cancel()
        messages.close()
    }

    private sealed interface Message {
        class Apply<C, CMD, E : Event>(val decision: Decision<C, CMD, E>) : Message

        class Finished(val command: CommandId) : Message

        class TimerDone(val timer: TimerId) : Message
    }

    private class Running<CMD>(val registration: CommandRegistration<CMD>) {
        lateinit var job: Job
    }

    private class Lane<CMD> {
        val running = mutableSetOf<CommandId>()
        val queue = ArrayDeque<CommandRegistration<CMD>>()
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.Apply<*, *, *> -> {
                @Suppress("UNCHECKED_CAST")
                carryOut(message.decision as Decision<C, CMD, E>)
            }
            is Message.Finished -> finished(message.command)
            is Message.TimerDone -> timers.remove(message.timer)
        }
    }

    private fun carryOut(decision: Decision<C, CMD, E>) {
        val exited = decision.cancelledScopes.toSet()
        for (command in running.values.filter { it.registration.scope in exited }) command.job.cancel()
        for (timer in decision.timersCancelled) timers.remove(timer)?.cancel()
        for (schedule in decision.timersScheduled) timers[schedule.id] = scope.launch {
            clock.delayUntil(schedule.deadline)
            feed(MachineInput.TimerFired(schedule.id, clock.now()))
            messages.trySend(Message.TimerDone(schedule.id))
        }
        for (registration in decision.commands) admit(registration)
    }

    private fun admit(registration: CommandRegistration<CMD>) {
        val laneId = registration.lane ?: return start(registration)
        val lane = lanes.getOrPut(laneId) { Lane() }
        when (val policy = registration.policy) {
            ConcurrencyPolicy.Latest -> {
                for (id in lane.running.toList()) abandon(running.getValue(id), AbandonReason.Superseded)
                start(registration)
            }
            ConcurrencyPolicy.Sequential -> if (lane.running.isEmpty()) start(registration) else lane.queue += registration
            ConcurrencyPolicy.DropIfRunning -> if (lane.running.isEmpty()) start(registration) else feed(MachineInput.CommandAbandoned(registration.id, AbandonReason.Dropped, clock.now()))
            is ConcurrencyPolicy.Parallel -> if (lane.running.size < policy.limit) start(registration) else lane.queue += registration
            null -> start(registration)
        }
    }

    // Cancels the command and tells the machine at once: the job may never have started, so its
    // body cannot be relied on to report anything.
    private fun abandon(command: Running<CMD>, reason: AbandonReason) {
        command.job.cancel()
        feed(MachineInput.CommandAbandoned(command.registration.id, reason, clock.now()))
    }

    private fun start(registration: CommandRegistration<CMD>) {
        val command = Running(registration)
        running[registration.id] = command
        registration.lane?.let { lanes.getValue(it).running += registration.id }
        val envelope = CommandEnvelope(registration.id, registration.command, registration.scope, registration.lane)
        command.job = scope.launch {
            try {
                handler.execute(envelope) { action -> feed(MachineInput.CommandResult(registration.id, action, clock.now())) }
                feed(MachineInput.CommandCompleted(registration.id, clock.now()))
            } catch (e: CancellationException) {
                // The command's own cancellation ends it: its scope exited (the machine already dropped
                // it), a newer command superseded it (the actor reported it), or the store closed. A
                // CancellationException thrown while the job is active is a failure, as in core.
                if (currentCoroutineContext().isActive) fail(registration.id, e) else throw e
            } catch (e: Exception) {
                fail(registration.id, e)
            }
        }
        // Runs also for a job cancelled before it started, which its body could not report.
        command.job.invokeOnCompletion { messages.trySend(Message.Finished(registration.id)) }
    }

    private fun fail(command: CommandId, error: Exception) {
        feed(MachineInput.CommandFailed(command, FailureDescriptor.of(error), clock.now()))
        report(error)
    }

    private fun finished(id: CommandId) {
        val command = running.remove(id) ?: return
        val laneId = command.registration.lane ?: return
        val lane = lanes.getValue(laneId)
        lane.running -= id
        while (lane.queue.isNotEmpty()) {
            val next = lane.queue.first()
            val fits = when (val policy = next.policy) {
                ConcurrencyPolicy.Sequential -> lane.running.isEmpty()
                is ConcurrencyPolicy.Parallel -> lane.running.size < policy.limit
                else -> true
            }
            if (!fits) break
            lane.queue.removeFirst()
            start(next)
        }
    }
}
