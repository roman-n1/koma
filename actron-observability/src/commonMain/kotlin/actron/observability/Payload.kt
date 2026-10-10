package actron.observability

import actron.core.Action
import actron.core.Event
import actron.core.State

/**
 * What the journal keeps of a state, action or event, as decided by the [PayloadPolicy] before
 * the record is retained anywhere.
 */
sealed interface Payload<out T : Any> {
    /**
     * The live object, kept as is. For debug recordings the policy explicitly allows; the object
     * is then also what the sinks print.
     */
    data class Retained<out T : Any>(val value: T) : Payload<T>

    /**
     * A safe projection: a label chosen by the policy and the fields it allowed, already masked
     * or truncated. Nothing of the object itself is kept.
     */
    data class Projected(val label: String, val fields: Map<String, String> = emptyMap()) : Payload<Nothing>

    /**
     * The text of a payload that was retained, as a file or a remote sink keeps it: the object's
     * `toString()` at the time of writing, never the object. Produced by readers of the journal
     * file format, not by a policy.
     */
    data class Described(val text: String) : Payload<Nothing>

    /**
     * The policy keeps nothing of this payload.
     */
    data object Omitted : Payload<Nothing>

    /**
     * The policy threw while describing the payload; the error was reported to the session's
     * failure handler and nothing of the object is kept.
     */
    data object Unavailable : Payload<Nothing>
}

/** Disclosed failure metadata. These profiles preserve whether each field was withheld. */
sealed interface FailureDetails {
    val attributes: Map<String, String>

    data object Redacted : FailureDetails {
        override val attributes: Map<String, String> = emptyMap()
    }
    data class MetadataOnly(val type: String) : FailureDetails {
        override val attributes: Map<String, String> get() = mapOf("type" to type)
    }
    data class Detailed(val type: String, val message: String) : FailureDetails {
        override val attributes: Map<String, String> get() = mapOf("type" to type, "message" to message)
    }
    /** Legacy/custom policies can disclose a message while withholding its class name. */
    data class AnonymousMessage(val message: String) : FailureDetails {
        override val attributes: Map<String, String> get() = mapOf("message" to message)
    }
}

/** Edges of the sanitized exception graph, retained in their original order. */
sealed interface FailureRelation {
    val failure: FailureDescriptor
    data class Cause(override val failure: FailureDescriptor) : FailureRelation
    data class Suppressed(override val failure: FailureDescriptor) : FailureRelation
}

/** A sanitized failure graph. It never retains a Throwable or an undisclosed message. */
data class FailureDescriptor(
    val details: FailureDetails,
    val related: List<FailureRelation> = emptyList(),
) {
    init { require(related.count { it is FailureRelation.Cause } <= 1) { "[Actron] A failure has one primary cause" } }
    constructor(type: String) : this(FailureDetails.MetadataOnly(type))
    constructor(type: String, message: String) : this(FailureDetails.Detailed(type, message))
    constructor(type: String, cause: FailureDescriptor) : this(FailureDetails.MetadataOnly(type), listOf(FailureRelation.Cause(cause)))
    constructor(type: String, message: String, cause: FailureDescriptor, suppressed: List<FailureDescriptor> = emptyList()) :
        this(FailureDetails.Detailed(type, message), listOf(FailureRelation.Cause(cause)) + suppressed.map { FailureRelation.Suppressed(it) })

    /** Label for textual identities; withheld metadata is never serialized as this display text. */
    val typeLabel: String get() = details.attributes["type"] ?: "null"
    val suppressed: List<FailureDescriptor> get() = related.filterIsInstance<FailureRelation.Suppressed>().map { it.failure }
    val hasCause: Boolean get() = related.any { it is FailureRelation.Cause }

    fun withType(accept: (String) -> Unit) {
        if ("type" in details.attributes) accept(details.attributes.getValue("type"))
    }
    fun withMessage(accept: (String) -> Unit) {
        if ("message" in details.attributes) accept(details.attributes.getValue("message"))
    }
    fun withCause(accept: (FailureDescriptor) -> Unit): Boolean {
        for (link in related) if (link is FailureRelation.Cause) { accept(link.failure); return true }
        return false
    }
    fun sameType(other: FailureDescriptor): Boolean {
        val left = details.attributes; val right = other.details.attributes
        return ("type" in left) == ("type" in right) && ("type" !in left || left.getValue("type") == right.getValue("type"))
    }

    companion object {
        val Unavailable: FailureDescriptor = FailureDescriptor(FailureDetails.Redacted)

        /** Follows causes to [maxDepth], excludes cycles, and discloses messages only on request. */
        fun of(error: Throwable, includeMessages: Boolean = false, maxDepth: Int = 8): FailureDescriptor =
            describe(error, includeMessages, maxDepth, mutableSetOf())

        private fun describe(error: Throwable, includeMessages: Boolean, depth: Int, seen: MutableSet<Throwable>): FailureDescriptor {
            seen += error
            val related = mutableListOf<FailureRelation>()
            if (depth > 0) {
                error.cause?.let { cause ->
                    if (cause !in seen) related += FailureRelation.Cause(describe(cause, includeMessages, depth - 1, seen))
                }
                val suppressed = error.suppressedExceptions.filter { it !in seen }
                for (failure in suppressed) related += FailureRelation.Suppressed(describe(failure, includeMessages, depth - 1, seen))
            }
            var details: FailureDetails = FailureDetails.Redacted
            error::class.simpleName?.let { details = FailureDetails.MetadataOnly(it) }
            if (includeMessages) error.message?.let { message ->
                val named = details
                details = if (named is FailureDetails.MetadataOnly) FailureDetails.Detailed(named.type, message) else FailureDetails.AnonymousMessage(message)
            }
            return FailureDescriptor(details, related)
        }
    }
}

/**
 * Decides, before anything is retained, what the journal keeps of each state, action, event and
 * failure of one Store.
 *
 * The policy runs on the Store's own thread, under its lock, for every trace: keep it cheap. It
 * must not throw; when it does, the payload becomes [Payload.Unavailable], the error goes to the
 * session's failure handler, and the record is still published. It never sees a payload twice, so
 * masking, truncation and allowlisting happen here or not at all: a policy that returns
 * [Payload.Retained] has decided that the object may sit in memory and reach the sinks as is.
 */
interface PayloadPolicy<S : State, A : Action, E : Event> {
    fun state(state: S): Payload<S>

    fun action(action: A): Payload<A>

    fun event(event: E): Payload<E>

    fun failure(error: Throwable): FailureDescriptor

    companion object {
        /**
         * Keeps no state, action or event, and of a failure only the class names: the production
         * default. Records still carry ids, ordinals, revisions, outcomes and durations.
         */
        fun <S : State, A : Action, E : Event> metadataOnly(): PayloadPolicy<S, A, E> = PayloadPolicy(
            state = { Payload.Omitted },
            action = { Payload.Omitted },
            event = { Payload.Omitted },
            failure = { FailureDescriptor.of(it) },
        )

        /**
         * Keeps every object and every message: for tests and local debug recordings only.
         */
        fun <S : State, A : Action, E : Event> retainAll(): PayloadPolicy<S, A, E> = PayloadPolicy(
            state = { Payload.Retained(it) },
            action = { Payload.Retained(it) },
            event = { Payload.Retained(it) },
            failure = { FailureDescriptor.of(it, includeMessages = true) },
        )
    }
}

/**
 * Builds a [PayloadPolicy] from functions; each defaults to the [PayloadPolicy.metadataOnly] rule.
 */
fun <S : State, A : Action, E : Event> PayloadPolicy(
    state: (S) -> Payload<S> = { Payload.Omitted },
    action: (A) -> Payload<A> = { Payload.Omitted },
    event: (E) -> Payload<E> = { Payload.Omitted },
    failure: (Throwable) -> FailureDescriptor = { FailureDescriptor.of(it) },
): PayloadPolicy<S, A, E> = object : PayloadPolicy<S, A, E> {
    override fun state(state: S): Payload<S> = state(state)
    override fun action(action: A): Payload<A> = action(action)
    override fun event(event: E): Payload<E> = event(event)
    override fun failure(error: Throwable): FailureDescriptor = failure(error)
}
