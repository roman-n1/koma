package koma.observability

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * One run of the process, or of a runtime inside it: the scope in which [GroupSeq] and
 * [StoreSeq] are assigned. A replay does not reuse the id of the recording it replays.
 */
@JvmInline
value class RuntimeSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] RuntimeSessionId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * A fresh id: 16 hex digits from [Random]. Unique enough to tell recordings apart; not a UUID.
         */
        fun random(): RuntimeSessionId = RuntimeSessionId(Random.nextLong().toULong().toString(16).padStart(16, '0'))
    }
}

/**
 * A set of Stores whose records are ordered together, for example one open screen with its
 * root, main and bridge Stores. [GroupSeq] is dense within a group.
 */
@JvmInline
value class MachineGroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] MachineGroupId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One Store instance. Two tabs showing the same chat are two instances: the id is never a class
 * name or a domain id such as a chat id.
 */
@JvmInline
value class StoreInstanceId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] StoreInstanceId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Position of a record in its group's journal: 1 for the first record, then dense in
 * publication order. Assigned under the journal's lock together with the record's retention, so
 * the order of two records is the order they were published in, whatever thread published them.
 */
@JvmInline
value class GroupSeq(val value: Long) {
    override fun toString(): String = "#$value"
}

/**
 * Position of a record among the records of one Store: 1 for the first, then dense.
 */
@JvmInline
value class StoreSeq(val value: Long) {
    override fun toString(): String = "#$value"
}

/**
 * Whether the recorded Store runs live or replays a recording.
 */
enum class ExecutionMode {
    Live,
    Replay,
}

/**
 * What a recording of a Store can be used for.
 *
 * A Store whose handlers may do arbitrary I/O and whose payloads may be omitted by the policy is
 * [InspectOnly]: its records can be shown, not replayed. [DeterministicReplay] is reserved for
 * Stores driven through the replay-ready decision pipeline; nothing grants it yet.
 */
enum class Capability {
    InspectOnly,
    DeterministicReplay,
}
