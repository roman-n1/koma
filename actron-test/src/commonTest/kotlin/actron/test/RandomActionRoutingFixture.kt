package actron.test

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.core.StoreBuilder
import kotlin.random.Random
import kotlin.reflect.KClass

/*
 * Random Store definitions over a fixed sealed hierarchy, for property-based tests of the
 * actron-test inspection helpers. Types cannot be generated at runtime, so a seeded Random picks
 * the registration order and the declared (state, action) or (state, exception) type pairs.
 */

internal sealed interface KtState : State {
    data class A(val n: Int) : KtState
    data object B : KtState
    sealed interface Sub : KtState {
        data class X(val n: Int) : Sub
        data object Y : Sub
    }
}

internal sealed interface KtAction : Action {
    data object P : KtAction
    data object Q : KtAction
    sealed interface G : KtAction {
        data object R : G
        data class T(val n: Int) : G
    }
}

internal sealed interface KtEvent : Event

internal class KtBoom : IllegalStateException("boom")

internal typealias KtBuilder = StoreBuilder<KtState, KtAction, KtEvent>

internal val ktStates: List<KtState> = listOf(KtState.A(0), KtState.A(1), KtState.B, KtState.Sub.X(0), KtState.Sub.Y)
internal val ktActions: List<KtAction> = listOf(KtAction.P, KtAction.Q, KtAction.G.R, KtAction.G.T(1))

/** Registers one handler of a fixed (state type, input type) pair; [kind] is enter, action, exit or recover. */
internal class Registration(
    val kind: String,
    val stateType: KClass<*>,
    val inputType: KClass<*>?,
    val register: KtBuilder.(tag: Int, log: MutableList<String>) -> Unit,
)

internal inline fun <reified S2 : KtState, reified A2 : KtAction> actionReg() =
    Registration("action", S2::class, A2::class) { tag, log -> state<S2> { action<A2> { log += "action#$tag" } } }

internal inline fun <reified S2 : KtState> enterReg() =
    Registration("enter", S2::class, null) { tag, log -> state<S2> { enter { log += "enter#$tag" } } }

internal inline fun <reified S2 : KtState> exitReg() =
    Registration("exit", S2::class, null) { tag, log -> state<S2> { exit { log += "exit#$tag" } } }

internal inline fun <reified S2 : KtState, reified T : Exception> recoverReg() =
    Registration("recover", S2::class, T::class) { tag, log -> state<S2> { recover<T> { log += "recover#$tag" } } }

internal inline fun <reified S2 : KtState> perState(): List<Registration> = listOf(
    actionReg<S2, KtAction>(), actionReg<S2, KtAction.P>(), actionReg<S2, KtAction.Q>(),
    actionReg<S2, KtAction.G>(), actionReg<S2, KtAction.G.R>(), actionReg<S2, KtAction.G.T>(),
    enterReg<S2>(), exitReg<S2>(),
    recoverReg<S2, Exception>(), recoverReg<S2, IllegalStateException>(), recoverReg<S2, IllegalArgumentException>(),
    recoverReg<S2, KtBoom>(),
)

/** Every (state type, input type) pair the generator can use: 6 state types x 12 handler shapes. */
internal val allRegistrations: List<Registration> =
    perState<KtState>() + perState<KtState.A>() + perState<KtState.B>() +
        perState<KtState.Sub>() + perState<KtState.Sub.X>() + perState<KtState.Sub.Y>()

internal fun randomRegistrations(random: Random, max: Int = 16): List<Registration> =
    List(random.nextInt(0, max + 1)) { allRegistrations.random(random) }

/**
 * Builds a Store from [regs] in order. Each handler logs its tag, as "<kind>#<index within kind>",
 * so a log entry can be compared directly with a handler index.
 */
internal fun buildKtStore(
    regs: List<Registration>,
    initialState: KtState,
    log: MutableList<String> = mutableListOf(),
    configure: KtBuilder.() -> Unit = {},
): Store<KtState, KtAction, KtEvent> = Store(initialState) {
    configure()
    val perKind = mutableMapOf<String, Int>()
    for (reg in regs) {
        val tag = perKind[reg.kind] ?: 0
        perKind[reg.kind] = tag + 1
        reg.register(this, tag, log)
    }
}
