package koma.statechart.machine

import koma.core.ExperimentalKomaApi

/**
 * The state of an external source as data, taken in a cut: what a branch needs to script the
 * source's next data, and what the inspector shows. [fields] are the source's own vocabulary
 * (a pagination engine: generation, the window, load states, placeholders, the pending focus;
 * never its caches), already safe to keep.
 */
@ExperimentalKomaApi
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
 */
@ExperimentalKomaApi
interface ExternalSource {
    val id: SourceId

    /** What the source is, for its snapshot's readers: `paging`, `socket`, `subscription`. */
    val kind: String

    /** Stops feeding; returns once no feed is in progress. */
    suspend fun pause()

    /** The source's state now, as data. */
    fun snapshot(): SourceSnapshot

    /** Feeds again. */
    fun resume()
}
