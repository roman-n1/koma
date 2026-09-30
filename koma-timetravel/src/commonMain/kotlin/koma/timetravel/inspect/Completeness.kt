package koma.timetravel.inspect

import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.observability.file.SegmentMark

/**
 * Why an inspected history is not the whole story. A history with no reason is complete: every
 * input, every processing, every payload the inspector can show is there. Each reason names
 * what is missing, so the person reading the history knows what they are not seeing (handoff §6).
 */
@ExperimentalKomaApi
sealed interface Incompleteness {
    /** [count] payloads of [store] were omitted by its policy: states, actions or events are not in the journal. */
    data class PayloadsOmitted(val store: StoreInstanceId, val count: Int) : Incompleteness

    /** [count] payloads of [store] were lost because its policy threw. */
    data class PayloadsUnavailable(val store: StoreInstanceId, val count: Int) : Incompleteness

    /** [dropped] records never reached the sinks, in [gaps] holes the session marked. */
    data class RecordsDropped(val gaps: Int, val dropped: Long) : Incompleteness

    /** [count] records were pushed out of the session's memory: the beginning is gone. */
    data class RecordsEvicted(val count: Long) : Incompleteness

    /** [count] records were published after the session stopped and were not kept. */
    data class RecordsAfterStop(val count: Long) : Incompleteness

    /** Segments or frames of the journal's files are missing or damaged. */
    data class Damaged(val mark: SegmentMark) : Incompleteness

    /** The registration of [store] is not in the journal: its beginning is gone, and its capability unknown. */
    data class NotRegistered(val store: StoreInstanceId) : Incompleteness

    /** The session stopped while these Stores were still open: their later history is not here. */
    data class StoppedWhileOpen(val stores: List<StoreInstanceId>) : Incompleteness

    /** [count] inputs of [store] were accepted and have no end in the journal. */
    data class InputsPending(val store: StoreInstanceId, val count: Int) : Incompleteness

    /** [count] records of [store] could not be attributed to a processing. */
    data class Unattributed(val store: StoreInstanceId?, val count: Int) : Incompleteness

    /** The recording attached for [store] is not the run the journal shows. */
    data class RecordingMismatch(val store: StoreInstanceId, val reason: String) : Incompleteness

    /** [count] bridge messages of [store] went to members that were not attached: part of the group is not here. */
    data class MessagesUndelivered(val store: StoreInstanceId, val count: Int) : Incompleteness
}

/**
 * The completeness of a history: complete when nothing is missing, else the reasons.
 */
@ExperimentalKomaApi
data class Completeness(val reasons: List<Incompleteness>) {
    val isComplete: Boolean get() = reasons.isEmpty()

    companion object {
        val Complete: Completeness = Completeness(emptyList())
    }
}

/**
 * Whether something can be done, and if not, why: the inspector's answer to a button that would
 * otherwise be silently disabled (handoff §11).
 */
@ExperimentalKomaApi
sealed interface Availability {
    data object Available : Availability

    data class Unavailable(val reasons: List<String>) : Availability
}

/**
 * How a Store's recording relates to the journal.
 */
@ExperimentalKomaApi
sealed interface RecordingStatus {
    /** No recording attached: the history shows what the journal kept. */
    data object None : RecordingStatus

    /** A recording of [steps] steps, matching the journal's decisions. */
    data class Attached(val steps: Int) : RecordingStatus

    /** A recording that does not match the journal; it is not used. */
    data class Mismatch(val reason: String) : RecordingStatus
}

/**
 * One Store of the inspected group.
 *
 * @property capability What the journal says the Store's recording can be used for; `null` when
 * the registration is not in the journal
 * @property records How many of the journal's records are the Store's
 * @property processings How many processings the journal holds for it
 * @property revision The last revision it committed, as far as the journal shows
 * @property closed Whether the journal holds its `StoreClosed`
 */
@ExperimentalKomaApi
data class StoreView(
    val id: StoreInstanceId,
    val capability: koma.observability.Capability?,
    val records: Int,
    val processings: Int,
    val revision: Long?,
    val closed: Boolean,
    val recording: RecordingStatus,
    val completeness: Completeness,
)
