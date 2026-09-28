package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.HandlerMetadata
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import kotlin.reflect.KClass

/**
 * A registered `enter {}` or `exit {}` handler, described by its state type.
 *
 * @property index Position of the handler in first-match order
 * @property stateType The state type given to `state<S2> {}`, or `null` if unknown
 */
data class StateHandlerDescription(
    val index: Int,
    val stateType: KClass<*>?,
) {
    override fun toString(): String = "#$index state<${stateType?.simpleName ?: "?"}>"
}

/**
 * A registered `recover {}` handler, described by the types it was declared for.
 *
 * @property index Position of the handler in first-match order
 * @property stateType The state type given to `state<S2> {}`, or `null` if unknown
 * @property exceptionType The exception type given to `recover<T> {}`, or `null` if unknown
 */
data class RecoverHandlerDescription(
    val index: Int,
    val stateType: KClass<*>?,
    val exceptionType: KClass<*>?,
) {
    override fun toString(): String =
        "#$index state<${stateType?.simpleName ?: "?"}> / recover<${exceptionType?.simpleName ?: "?"}>"
}

/**
 * All handlers registered in a Store, per handler kind, in first-match order.
 *
 * This is a read-only view of the Store definition. It shows which state, action and exception
 * types each handler was declared for. It does not show the target of a transition, because
 * `nextState {}` is ordinary code.
 *
 * [toString] renders one handler per line, so the value can be compared against an expected text
 * in a test, printed while debugging, or pasted into documentation.
 */
class StoreHandlers internal constructor(
    val enter: List<StateHandlerDescription>,
    val action: List<ActionHandlerDescription>,
    val exit: List<StateHandlerDescription>,
    val recover: List<RecoverHandlerDescription>,
) {
    override fun toString(): String = buildString {
        fun section(name: String, handlers: List<Any>) {
            append(name).append(':')
            if (handlers.isEmpty()) {
                append(" none\n")
            } else {
                append('\n')
                handlers.forEach { append("  ").append(it).append('\n') }
            }
        }
        section("enter", enter)
        section("action", action)
        section("exit", exit)
        section("recover", recover)
    }.trimEnd()
}

/**
 * Describes all handlers registered in this Store.
 *
 * No handler runs and the Store is not started.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
fun <S : State, A : Action, E : Event> Store<S, A, E>.describeHandlers(): StoreHandlers {
    val metadata = requireStoreInternalApi().handlerMetadata()
    fun List<HandlerMetadata>.toStateHandlers() = map { StateHandlerDescription(it.index, it.stateType) }
    return StoreHandlers(
        enter = metadata.enter.toStateHandlers(),
        action = metadata.action.map { ActionHandlerDescription(it.index, it.stateType, it.inputType) },
        exit = metadata.exit.toStateHandlers(),
        recover = metadata.recover.map { RecoverHandlerDescription(it.index, it.stateType, it.inputType) },
    )
}
