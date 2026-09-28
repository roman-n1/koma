package koma.core

import kotlin.reflect.KClass

/**
 * Describes which types a registered handler was declared for.
 *
 * The matcher is kept next to the handler predicate so the handler registry can be inspected
 * without running any handler. Handler selection still uses the predicates only.
 *
 * @property stateType The state type given to `state<S2> {}`
 * @property inputType The action type given to `action<A2> {}` or the exception type given to
 * `recover<T> {}`; `null` for `enter {}` and `exit {}` handlers
 */
@PublishedApi
internal data class HandlerMatcher(
    val stateType: KClass<*>,
    val inputType: KClass<*>? = null,
)

/**
 * Matchers of all registered handlers, in registration order.
 *
 * Each list lines up with the order the Store uses for first-match selection.
 * An entry is `null` when the handler was registered by inline code compiled against an earlier
 * Koma version that did not record matchers.
 */
internal class HandlerRegistry(
    val enter: List<HandlerMatcher?>,
    val action: List<HandlerMatcher?>,
    val exit: List<HandlerMatcher?>,
    val recover: List<HandlerMatcher?>,
)
