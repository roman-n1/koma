package koma.observability

import koma.core.Action
import koma.core.Event
import koma.core.State

/**
 * What the journal keeps of a state, action or event, as decided by the [PayloadPolicy] before
 * the record is retained anywhere.
 */
sealed interface Payload<out T> {
    /**
     * The live object, kept as is. For debug recordings the policy explicitly allows; the object
     * is then also what the sinks print.
     */
    data class Retained<out T>(val value: T) : Payload<T>

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

/**
 * A safe description of a failure: never the `Throwable` itself, and by default without its
 * message, which may carry data the logs must not.
 *
 * @property type A label for the kind of failure, or `null` when the policy withholds it
 * @property message The message, only when the policy allows messages
 * @property cause The description of the cause, so causality survives sanitization
 * @property suppressed The descriptions of the suppressed exceptions
 */
data class FailureDescriptor(
    val type: String?,
    val message: String? = null,
    val cause: FailureDescriptor? = null,
    val suppressed: List<FailureDescriptor> = emptyList(),
) {
    companion object {
        /**
         * A descriptor for a failure the policy could not describe.
         */
        val Unavailable: FailureDescriptor = FailureDescriptor(type = null)

        /**
         * Describes [error] with the simple class names of it, its causes and its suppressed
         * exceptions, and with the messages only when [includeMessages] is true. Causes are
         * followed [maxDepth] levels down, and a cycle stops the walk.
         */
        fun of(error: Throwable, includeMessages: Boolean = false, maxDepth: Int = 8): FailureDescriptor =
            describe(error, includeMessages, maxDepth, mutableSetOf())

        private fun describe(error: Throwable, includeMessages: Boolean, depth: Int, seen: MutableSet<Throwable>): FailureDescriptor {
            seen += error
            val cause = error.cause?.takeIf { depth > 0 && it !in seen }?.let { describe(it, includeMessages, depth - 1, seen) }
            val suppressed = if (depth > 0) error.suppressedExceptions.filter { it !in seen }.map { describe(it, includeMessages, depth - 1, seen) } else emptyList()
            return FailureDescriptor(
                type = error::class.simpleName,
                message = error.message.takeIf { includeMessages },
                cause = cause,
                suppressed = suppressed,
            )
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
