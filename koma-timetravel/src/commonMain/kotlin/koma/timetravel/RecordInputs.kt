package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.statechart.machine.*

/**
 * Converts finite generated/shrunk inputs to an inspectable Recording. Decides each input once,
 * retains ignored/failed steps and carries executor bookkeeping, but executes no commands/events.
 * Use authentic checkpoint data for a started workflow, including its unfinished command payloads.
 * This debug artifact retains supplied context/action/command data; codecs/redaction belong to the app.
 */
@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.recordInputs(
    start: ExecutorCheckpoint<C, CMD>, inputs: List<MachineInput<A>>,
): Recording<C, A, CMD, E> {
    require(start.snapshot.definition == id && start.snapshot.version == version) { "[Koma] Recording checkpoint belongs to another machine or version" }
    require(start.registrations.keys == start.snapshot.commands.keys && start.registrations.all { (id, command) ->
        start.snapshot.commands[id] == CommandRecord(command.scope, command.lane)
    }) { "[Koma] Recording checkpoint command payloads do not match its snapshot" }
    var current = start
    val steps = inputs.map { input ->
        require(input.now >= current.now) { "[Koma] Recording inputs moved logical time backwards" }
        val decision = decide(current.snapshot, input)
        val step: RecordedStep<C, A, CMD, E> = when (val outcome = decision.outcome) {
            DecisionOutcome.Handled -> RecordedStep.Committed(input, decision)
            is DecisionOutcome.Ignored -> RecordedStep.Ignored(input, outcome.reason)
            is DecisionOutcome.Failed -> RecordedStep.Failed(input, outcome.failure)
        }
        current = current.carriedPast(step)
        step
    }
    return Recording(id, version, start, steps)
}

/** Whole generated/shrunk run from an unstarted snapshot; started workflows require the checkpoint overload. */
@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.recordInputs(
    initial: MachineSnapshot<C>, inputs: List<MachineInput<A>>,
): Recording<C, A, CMD, E> {
    require(!initial.isStarted) { "[Koma] Use an executor checkpoint to record a started workflow" }
    return recordInputs(ExecutorCheckpoint.initial(initial), inputs)
}
