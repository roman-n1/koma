package koma.logging

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.State
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates a plugin that logs actions, events, and committed state changes.
 *
 * Without a [dispatcher], each entry is logged from the Store hook itself, so entries keep the
 * order in which the Store processed them. With a [dispatcher], each entry is logged in its own
 * coroutine on that dispatcher so slow loggers never delay the Store; entries may then appear out
 * of order on multi-threaded dispatchers, and entries still pending when the Store closes are
 * dropped.
 *
 * Entries include the full `toString()` of actions, events and states. Avoid enabling this plugin
 * in release builds when those contain personal data or credentials.
 *
 * An exception thrown by [logger] (or by a `toString()`) is reported to the Store's exception
 * handler; the action, event or state change being logged is processed as usual.
 *
 * @param tag The tag to use for logging
 * @param severity The severity level for log messages
 * @param logger The logger implementation to use
 * @param dispatcher Optional CoroutineDispatcher to log on instead of the Store's hook
 * @return Plugin that logs common Store operations
 */
fun <S : State, A : Action, E : Event> simpleLogging(
    tag: String = "Koma",
    severity: Logger.Severity = Logger.Severity.Debug,
    logger: Logger = DefaultLogger,
    dispatcher: CoroutineDispatcher? = null,
): Plugin<S, A, E> {
    return object : Plugin<S, A, E> {
        override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
            log(scope) { "Action: $action" }
        }

        override suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {
            log(scope) { "Event: $event" }
        }

        override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
            log(scope) { "State: $state <- $prevState" }
        }

        private fun log(scope: PluginScope<S, A>, message: () -> String) {
            if (dispatcher == null) {
                try {
                    logger.log(severity = severity, tag = tag, throwable = null, message = message)
                } catch (e: Exception) {
                    // A failing logger (or a toString() that throws) must not abort the action or
                    // transition being logged; report it through the Store's exception handler.
                    scope.launch { throw e }
                }
            } else {
                scope.launch(dispatcher) {
                    logger.log(severity = severity, tag = tag, throwable = null, message = message)
                }
            }
        }
    }
}
