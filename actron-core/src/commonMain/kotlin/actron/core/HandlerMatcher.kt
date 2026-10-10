package actron.core

import kotlin.reflect.KClass

/**
 * Describes which types a registered handler was declared for.
 *
 * The matcher is kept next to the handler predicate so the handler registry can be inspected
 * without running any handler. Handler selection still uses the predicates only.
 *
 * @property stateType The state type given to `state<S2> {}`
 * @property inputType The action type given to `action<A2> {}` or the exception type given to
 * `recover<T> {}`; `Unit::class` describes the input-free `enter {}` and `exit {}` handlers
 */
@PublishedApi
internal data class HandlerMatcher(
    val stateType: KClass<*>,
    val inputType: KClass<*> = Unit::class,
)

/**
 * Matchers of all registered handlers, in registration order.
 *
 * Each list lines up with the order the Store uses for first-match selection.
 * Every entry has declared metadata. Consumers of older inline registries must recompile.
 *
 * [actionPredicates] lines up with [action] and holds the predicates the Store uses to select an
 * action handler, so routing can be checked without running any handler.
 */
internal class HandlerRegistry<S : State, A : Action>(
    val enter: List<HandlerMatcher>,
    val action: List<HandlerMatcher>,
    val exit: List<HandlerMatcher>,
    val recover: List<HandlerMatcher>,
    val actionPredicates: List<(S, A) -> Boolean>,
)

/**
 * One registered action handler and whether it matches a given state and action.
 *
 * Used by `:actron-test` routing diagnostics through [StoreInternalApi.matchActionHandlers].
 *
 * @property index Position of the handler in first-match order
 * @property stateType The state type given to `state<S2> {}`
 * @property actionType The action type given to `action<A2> {}`
 * @property matches Whether the handler matches the given state and action
 */
@InternalActronApi
class ActionHandlerMatch(
    val index: Int,
    val stateType: KClass<*>,
    val actionType: KClass<*>,
    val matches: Boolean,
)

/**
 * Declared types of one registered handler.
 *
 * Used by `:actron-test` through [StoreInternalApi.handlerMetadata].
 *
 * @property index Position of the handler in first-match order within its kind
 * @property stateType The state type given to `state<S2> {}`
 * @property inputType The action type for `action {}`, the exception type for `recover {}`,
 * `Unit::class` for input-free `enter {}` and `exit {}`
 */
@InternalActronApi
class HandlerMetadata(
    val index: Int,
    val stateType: KClass<*>,
    val inputType: KClass<*>,
)

/**
 * Declared types of all registered handlers, per handler kind, in first-match order.
 */
@InternalActronApi
class StoreHandlerMetadata(
    val enter: List<HandlerMetadata>,
    val action: List<HandlerMetadata>,
    val exit: List<HandlerMetadata>,
    val recover: List<HandlerMetadata>,
)
