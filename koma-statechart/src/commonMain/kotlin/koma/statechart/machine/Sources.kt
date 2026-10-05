package koma.statechart.machine

import koma.core.ExperimentalKomaApi

/**
 * The state of an external source as data, taken in a cut: what a branch needs to script the
 * source's next data, and what the inspector shows. [fields] are the source's own vocabulary
 * (a pagination engine: generation, the window, load states, placeholders, the pending focus;
 * never its caches), already safe to keep.
 */
data class SourceSnapshot(val source: SourceId, val kind: String, val version: Int, val fields: Map<String, String>)

/**
 * An external source of inputs attached to a [MachineGroup] (handoff §8, §10): a pagination
 * engine, a socket reader, a subscription. It feeds members through [MachineStore.feed], so its
 * inputs are [MachineInput.External] and a recording knows they are its.
 *
 * In a cut the group calls [pause] before freezing the members, [snapshot] once every member
 * has finished what it had accepted, and [resume] after thawing them, so the snapshot is the
 * source as the members had decided it: nothing it fed is missing from the members, nothing the
 * members decided is missing from it. [pause] returns once no input is being fed and none will
 * be until [resume]; it never waits for a Store, and the group gives up the cut when it does
 * not return within the timeout.
 *
 * Serialize feeding and snapshot state changes with the pause boundary. Advance an admission
 * cursor only for [Admission.Accepted]; a rejected input was not queued and may be retried.
 * Accepted means queued, not committed: the group waits for members before taking [snapshot].
 * Keep network reads and retry waits outside the feed lock so a stalled socket or a full queue
 * cannot prevent a cut. A reconnect can redeliver data: source protocols need their own stable
 * sequence/cursor and deduplication; `feed` does not deduplicate actions.
 *
 * A successful [pause] transfers responsibility for [resume] to the group. If [pause] throws
 * or is cancelled before returning, the source must roll back any partial pause itself; the
 * group cannot know which resources it acquired. Cleanup should not suspend in a cancelled
 * context (use a non-cancellable context when suspending cleanup is necessary).
 */
@ExperimentalKomaApi
interface ExternalSource {
    val id: SourceId

    /** What the source is, for its snapshot's readers: `paging`, `socket`, `subscription`. */
    val kind: String

    /**
     * Stops feeding; returns once no feed is in progress. Roll back acquired pause resources
     * before propagating failure or cancellation. Never wait for a Store to process an input.
     */
    suspend fun pause()

    /**
     * The source's state while paused, as data. Admission cursors reflect accepted inputs;
     * pending requests, load states and connection generations may be separate fields.
     */
    fun snapshot(): SourceSnapshot

    /**
     * Releases a successful pause and permits feeding again. Release owned resources even if
     * resuming another resource fails. Called once per successful pause, including failed cuts.
     */
    fun resume()
}
