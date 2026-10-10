@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.compose

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MailboxConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * The smallest machine with a retained effect: every `Navigate` action becomes a retained
 * `Navigate` effect in the store's mailbox.
 *
 * ```
 * [*] --> Idle      onAction Navigate(route): event Navigate(route) [Retained]
 * ```
 */
internal object MailboxFixture {
    sealed interface Act : Action {
        data class Navigate(val route: String) : Act
    }

    sealed interface Ev : Event {
        data class Navigate(val route: String) : Ev
    }

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())

    val machine = Machine<Unit, Act, Nothing, Ev>(DefinitionId("mailbox-effect"), DefinitionVersion("1"), chart) {
        onAction(root, ActionMatcher.of<Act.Navigate>("Navigate")) { event(Ev.Navigate((action as Act.Navigate).route)) }
    }

    /**
     * A started store whose mailbox retains every effect; [scope] runs its executor and
     * [coroutineContext] its inputs (the test dispatcher for a deterministic test, a real one
     * for a race).
     */
    fun store(
        scope: CoroutineScope,
        coroutineContext: CoroutineContext,
        maxRetained: Int = 64,
        maxAttempts: Int? = null,
        report: (Throwable) -> Unit = { throw it },
    ): MachineStore<Unit, Act, Nothing, Ev> = MachineStore(
        machine, Unit, CommandHandler<Nothing, Act> { _, _ -> }, scope, coroutineContext = coroutineContext,
        mailbox = MailboxConfig(policy = { EffectPolicy.Retained(maxAttempts) }, maxRetained = maxRetained),
    ) { exceptionHandler(ExceptionHandler { report(it) }) }.also { it.start() }
}
