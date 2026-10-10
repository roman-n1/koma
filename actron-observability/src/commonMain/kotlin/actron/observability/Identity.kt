package actron.observability

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * One run of the process, or of a runtime inside it: the scope in which [GroupSeq] and
 * [StoreSeq] are assigned. A replay does not reuse the id of the recording it replays.
 */
@JvmInline
value class RuntimeSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Actron] RuntimeSessionId must not be blank" }
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
        require(value.isNotBlank()) { "[Actron] MachineGroupId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One Store instance. Two tabs showing the same chat are two instances: the id is never a class
 * name or a domain id such as a chat id.
 */
@JvmInline
value class StoreInstanceId(val value: String) : RecordSubject {
    init {
        require(value.isNotBlank()) { "[Actron] StoreInstanceId must not be blank" }
    }

    override fun toString(): String = value
    override fun withStore(visit: (StoreInstanceId) -> Unit) { visit(this) }
}

/**
 * Position of a record in its group's journal: 1 for the first record, then dense in
 * publication order. Assigned under the journal's lock together with the record's retention, so
 * the order of two records is the order they were published in, whatever thread published them.
 */
@JvmInline
value class GroupSeq(val value: Long) : JournalBoundary {
    override fun toString(): String = "#$value"
    override fun precedes(record: GroupSeq): Boolean = value < record.value
}

/** A cut precedes all records or follows one published group record. */
sealed interface JournalBoundary {
    fun precedes(record: GroupSeq): Boolean
    data object Beginning : JournalBoundary {
        override fun precedes(record: GroupSeq): Boolean = true
    }
}

/**
 * Position of a record among the records of one Store: 1 for the first, then dense.
 */
@JvmInline
value class StoreSeq(val value: Long) : RecordOrdinal {
    override fun toString(): String = "#$value"
    override fun appendTo(text: StringBuilder) { text.append('/').append(value) }
}

/** A journal record describes a Store instance or the recording session itself. */
sealed interface RecordSubject {
    fun withStore(visit: (StoreInstanceId) -> Unit)
    data object Session : RecordSubject {
        override fun withStore(visit: (StoreInstanceId) -> Unit) {}
    }
}

/** Session records use group order; Store records additionally carry a Store ordinal. */
sealed interface RecordOrdinal {
    fun appendTo(text: StringBuilder)
    data object Session : RecordOrdinal {
        override fun appendTo(text: StringBuilder) {}
    }
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
