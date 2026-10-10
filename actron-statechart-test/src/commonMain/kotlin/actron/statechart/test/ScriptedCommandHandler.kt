package actron.statechart.test

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.statechart.machine.CommandEnvelope
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandId
import actron.statechart.machine.ResultSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A [CommandHandler] that runs no command by itself: every command it is asked to run waits
 * until the test answers it ([answer]), completes it ([complete]) or fails it ([fail]), as a
 * `Branch` does in a replay (handoff §12: the harness does not execute commands). What the
 * executor started, cancelled and finished is recorded for the test to read.
 */
@ExperimentalActronApi
class ScriptedCommandHandler<CMD : Any, A : Action> : CommandHandler<CMD, A> {
    private class Running<A : Action>(val sink: ResultSink<A>, val end: CompletableDeferred<Unit>)

    private val runningNow = MutableStateFlow<Map<CommandId, Running<A>>>(emptyMap())
    private val startedList = mutableListOf<CommandEnvelope<CMD>>()
    private val cancelledList = mutableListOf<CommandId>()

    /** Every command the executor started, in order. */
    val started: List<CommandEnvelope<CMD>> get() = startedList.toList()

    /** The commands the executor cancelled while they ran. */
    val cancelled: List<CommandId> get() = cancelledList.toList()

    /** The commands running now: started and neither answered to an end, completed, failed nor cancelled. */
    val running: List<CommandEnvelope<CMD>> get() = startedList.filter { it.id in runningNow.value.keys }

    override suspend fun execute(command: CommandEnvelope<CMD>, results: ResultSink<A>) {
        val end = CompletableDeferred<Unit>()
        startedList += command
        runningNow.update { it + (command.id to Running(results, end)) }
        try {
            end.await()
        } catch (e: CancellationException) {
            if (!end.isCompleted) cancelledList += command.id
            throw e
        } finally {
            runningNow.update { it - command.id }
        }
    }

    /**
     * Feeds [result] to the running [command]; it keeps running, as a live one does until it completes.
     *
     * @throws IllegalArgumentException if [command] is not running
     */
    fun answer(command: CommandId, result: A) {
        requireNotNull(runningNow.value[command]) { "[Actron] c${command.value} is not running: ${runningNow.value.keys}" }.sink.result(result)
    }

    /**
     * Ends the running [command]: the executor feeds its completion.
     *
     * @throws IllegalArgumentException if [command] is not running
     */
    fun complete(command: CommandId) {
        requireNotNull(runningNow.value[command]) { "[Actron] c${command.value} is not running: ${runningNow.value.keys}" }.end.complete(Unit)
    }

    /**
     * Fails the running [command] with [error]: the executor feeds its failure.
     *
     * @throws IllegalArgumentException if [command] is not running
     */
    fun fail(command: CommandId, error: Throwable = IllegalStateException("failed by the test")) {
        requireNotNull(runningNow.value[command]) { "[Actron] c${command.value} is not running: ${runningNow.value.keys}" }.end.completeExceptionally(error)
    }
}
