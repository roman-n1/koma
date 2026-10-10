package actron.test

import actron.core.ExceptionHandler
import actron.core.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Property-based checks for [describeHandlers]: for random definitions, each kind lists its handlers
 * in registration order with matching indices, the text rendering matches an independent renderer,
 * and the table predicts which enter, exit and recover handler the running Store picks.
 *
 * ```
 * start(s) --enter #e--> s --Fail(ex)--> recover #r (first state<..>/recover<T> matching s, ex)
 *          --Go(t), other class--> exit #x (first matching s) --> enter #e' (first matching t)
 * ```
 */
class StoreHandlersPropertyTest {

    private fun render(regs: List<Registration>): String {
        fun name(k: kotlin.reflect.KClass<*>?) = k?.simpleName ?: "?"
        fun section(kind: String, line: (Int, Registration) -> String): String {
            val ofKind = regs.filter { it.kind == kind }
            return if (ofKind.isEmpty()) "$kind: none" else "$kind:\n" + ofKind.mapIndexed { i, r -> "  " + line(i, r) }.joinToString("\n")
        }
        return listOf(
            section("enter") { i, r -> "#$i state<${name(r.stateType)}>" },
            section("action") { i, r -> "#$i state<${name(r.stateType)}> / action<${name(r.inputType)}>" },
            section("exit") { i, r -> "#$i state<${name(r.stateType)}>" },
            section("recover") { i, r -> "#$i state<${name(r.stateType)}> / recover<${name(r.inputType)}>" },
        ).joinToString("\n")
    }

    @Test
    fun describesRandomDefinitionsInRegistrationOrderAndRendersExactly() {
        repeat(400) { seed ->
            val regs = randomRegistrations(Random(seed), max = 24)
            val handlers = buildKtStore(regs, KtState.B).describeHandlers()
            fun of(kind: String) = regs.filter { it.kind == kind }
            val context = "seed=$seed"

            assertEquals(of("enter").mapIndexed { i, r -> StateHandlerDescription(i, r.stateType) }, handlers.enter, context)
            assertEquals(of("exit").mapIndexed { i, r -> StateHandlerDescription(i, r.stateType) }, handlers.exit, context)
            assertEquals(of("action").mapIndexed { i, r -> ActionHandlerDescription(i, r.stateType, r.inputType) }, handlers.action, context)
            assertEquals(of("recover").mapIndexed { i, r -> RecoverHandlerDescription(i, r.stateType, r.inputType) }, handlers.recover, context)
            assertEquals(render(regs), handlers.toString(), context)
        }
    }

    @Test
    fun actionTableIsTheSameListRoutingDiagnosticsReport() {
        repeat(200) { seed ->
            val random = Random(seed)
            val store = buildKtStore(randomRegistrations(random, max = 24), KtState.B)
            assertEquals(
                store.describeHandlers().action,
                store.diagnoseActionMatches(ktStates.random(random), ktActions.random(random)).handlers,
                "seed=$seed",
            )
        }
    }

    @Test
    fun tablePredictsTheEnterExitAndRecoverHandlersTheStoreRuns() = runTest {
        var recovered = 0
        var moved = 0
        repeat(300) { seed ->
            val random = Random(seed)
            val regs = randomRegistrations(random, max = 24).filter { it.kind != "action" }
            val initial = ktStates.random(random)
            val target = ktStates.random(random)
            val error: Exception = listOf(IllegalStateException("s"), IllegalArgumentException("a"), KtBoom(), Exception("e")).random(random)
            val fail = random.nextBoolean()
            val log = mutableListOf<String>()
            val store = buildKtStore(regs, initial, log) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                exceptionHandler(ExceptionHandler.Ignore)
                // One routing action at the end, so the action table stays out of the way.
                state<KtState> {
                    action<KtAction> {
                        if (fail) throw error
                        nextState { target }
                    }
                }
            }
            val table = store.describeHandlers()
            fun firstState(list: List<StateHandlerDescription>, s: KtState) = list.firstOrNull { it.stateType!!.isInstance(s) }?.index

            val expected = mutableListOf<String>()
            firstState(table.enter, initial)?.let { expected += "enter#$it" }
            if (fail) {
                table.recover.firstOrNull { it.stateType!!.isInstance(initial) && it.exceptionType!!.isInstance(error) }
                    ?.let { expected += "recover#${it.index}"; recovered++ }
            } else if (target::class != initial::class) {
                firstState(table.exit, initial)?.let { expected += "exit#$it" }
                firstState(table.enter, target)?.let { expected += "enter#$it" }
                moved++
            }

            store.startAndAwait()
            store.dispatchAndAwait(KtAction.P)

            assertEquals(expected, log, "seed=$seed initial=$initial target=$target fail=$fail error=$error")
            store.close()
        }
        assertTrue(recovered > 30 && moved > 60, "recovered=$recovered moved=$moved")
    }

    @Test
    fun describingIsImmediateWhileADispatchHoldsTheStore() = runTest {
        lateinit var store: Store<KtState, KtAction, KtEvent>
        var inside: StoreHandlers? = null
        store = Store(KtState.B) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<KtState.B> {
                action<KtAction.P> {
                    inside = store.describeHandlers()
                    delay(10_000)
                }
                exit { }
            }
        }
        store.dispatch(KtAction.P)
        testScheduler.advanceTimeBy(5)

        val outside = store.describeHandlers()

        assertEquals(5, currentTime)
        assertEquals(inside.toString(), outside.toString())
        assertEquals("enter: none\naction:\n  #0 state<B> / action<P>\nexit:\n  #0 state<B>\nrecover: none", outside.toString())
        store.close()
    }

    @Test
    fun nonActronStoreIsRejected() {
        val foreign = object : Store<KtState, KtAction, KtEvent> by buildKtStore(emptyList(), KtState.B) {}
        assertFailsWith<IllegalStateException> { foreign.describeHandlers() }
    }

    @Test
    fun unknownTypesRenderAsQuestionMarks() {
        val handlers = StoreHandlers(
            enter = listOf(StateHandlerDescription(0, null)),
            action = listOf(ActionHandlerDescription(0, KtState.B::class, null)),
            exit = emptyList(),
            recover = listOf(RecoverHandlerDescription(0, null, null), RecoverHandlerDescription(1, KtState::class, KtBoom::class)),
        )
        assertEquals(
            "enter:\n  #0 state<?>\naction:\n  #0 state<B> / action<?>\nexit: none\nrecover:\n  #0 state<?> / recover<?>\n  #1 state<KtState> / recover<KtBoom>",
            handlers.toString(),
        )
    }

    @Test
    fun typesWithoutASimpleNameRenderLikeUnknownTypes() {
        // Anonymous classes have no simple name on the JVM; whatever a platform reports must be used as is.
        val anonymousState = object : actron.core.State {}::class
        val anonymousError = object : Exception() {}::class
        val name = { k: kotlin.reflect.KClass<*> -> k.simpleName ?: "?" }

        assertEquals("#0 state<${name(anonymousState)}>", StateHandlerDescription(0, anonymousState).toString())
        assertEquals(
            "#1 state<${name(anonymousState)}> / recover<${name(anonymousError)}>",
            RecoverHandlerDescription(1, anonymousState, anonymousError).toString(),
        )
    }
}
