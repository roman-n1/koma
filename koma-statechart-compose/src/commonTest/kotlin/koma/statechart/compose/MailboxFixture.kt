@file:OptIn(ExperimentalKomaApi::class)

package koma.statechart.compose

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.EffectPolicy
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MailboxConfig
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
        coroutineContext: CoroutineContext?,
        maxRetained: Int = 64,
        maxAttempts: Int? = null,
        report: (Throwable) -> Unit = { throw it },
    ): MachineStore<Unit, Act, Nothing, Ev> = MachineStore(
        machine, Unit, CommandHandler<Nothing, Act> { _, _ -> }, scope, coroutineContext = coroutineContext,
        mailbox = MailboxConfig(policy = { EffectPolicy.Retained(maxAttempts) }, maxRetained = maxRetained),
    ) { exceptionHandler(ExceptionHandler { report(it) }) }.also { it.start() }
}
