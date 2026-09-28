package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import kotlin.reflect.KClass

/**
 * A registered `action {}` handler, described by the types it was declared for.
 *
 * @property index Position of the handler in first-match order
 * @property stateType The state type given to `state<S2> {}`, or `null` if unknown
 * @property actionType The action type given to `action<A2> {}`, or `null` if unknown
 */
data class ActionHandlerDescription(
    val index: Int,
    val stateType: KClass<*>?,
    val actionType: KClass<*>?,
) {
    override fun toString(): String {
        val stateName = stateType?.simpleName ?: "?"
        val actionName = actionType?.simpleName ?: "?"
        return "#$index state<$stateName> / action<$actionName>"
    }
}

/**
 * Result of checking which `action {}` handlers match a state and an action.
 *
 * This is an observation, not a verdict. Zero matches means the action is unhandled in that state.
 * One match means exactly one handler handles it. Two or more matches can be intended, for example
 * a broad fallback placed last, or accidental shadowing; only the Store author can tell.
 *
 * @property state The state that was checked
 * @property action The action that was checked
 * @property handlers All registered action handlers, in first-match order
 * @property matchedHandlerIndices Indices of the handlers that match, in first-match order
 */
class ActionMatchDiagnostics<S : State, A : Action> internal constructor(
    val state: S,
    val action: A,
    val handlers: List<ActionHandlerDescription>,
    val matchedHandlerIndices: List<Int>,
) {
    /**
     * Number of handlers that match.
     */
    val matchedHandlerCount: Int get() = matchedHandlerIndices.size

    /**
     * Index of the handler the Store would run, or `null` if the action is unhandled.
     */
    val selectedHandlerIndex: Int? get() = matchedHandlerIndices.firstOrNull()

    /**
     * Handlers that match, in first-match order. The first one is the handler the Store would run.
     */
    val matchedHandlers: List<ActionHandlerDescription> get() = matchedHandlerIndices.map { handlers[it] }

    override fun toString(): String {
        val matched = if (matchedHandlerIndices.isEmpty()) {
            "no handler matches"
        } else {
            matchedHandlers.joinToString(prefix = "selected ", separator = ", shadowed ")
        }
        return "ActionMatchDiagnostics(state=$state, action=$action: $matched)"
    }
}

/**
 * Checks which `action {}` handlers match [state] and [action], without dispatching.
 *
 * No handler runs and the Store is not started. Use this to test routing directly, for example
 * that an action is handled in exactly one state, or that a broad handler does not hide a more
 * specific one.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param state The state to check against
 * @param action The action to check
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    state: S,
    action: A,
): ActionMatchDiagnostics<S, A> {
    val matches = requireStoreInternalApi().matchActionHandlers(state, action)
    return ActionMatchDiagnostics(
        state = state,
        action = action,
        handlers = matches.map { ActionHandlerDescription(it.index, it.stateType, it.actionType) },
        matchedHandlerIndices = matches.filter { it.matches }.map { it.index },
    )
}

/**
 * Starts the Store if needed, then checks which `action {}` handlers match the current state and
 * [action], without dispatching.
 *
 * Startup is awaited first, as with [startAndAwait], so the check uses the state after the
 * startup `enter {}` chain. Like [startAndAwait], this waits for the Store's lock even when the
 * Store is already running: if an action is being processed, the check runs after it finishes
 * and sees the resulting state. Do not call it from inside a Store handler, where it would wait
 * for itself; use the non-suspending overload with an explicit state there.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param action The action to check
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    action: A,
): ActionMatchDiagnostics<S, A> {
    startAndAwait()
    return diagnoseActionMatches(currentState, action)
}
