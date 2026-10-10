package actron.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.reflect.KClass

/*
 * Random Store definitions over a fixed set of types, for property-based handler registry tests.
 *
 * Types cannot be created at runtime, so the fixture fixes a small sealed hierarchy with parent and
 * child types and lets a seeded Random pick the registration order, the handler kinds, the declared
 * types and a few legacy (metadata-less) handlers. Each generated handler knows its own ground-truth
 * predicate, so tests can check the registry and the running Store against an independent model.
 */

internal sealed interface RtState : State {
    data class A(val n: Int) : RtState
    data object B : RtState
    sealed interface Sub : RtState {
        data class X(val n: Int) : Sub
        data object Y : Sub
    }
}

internal sealed interface RtAction : Action {
    val fail: Exception?
    val goto: RtState?

    data class P(override val fail: Exception? = null, override val goto: RtState? = null) : RtAction
    data class Q(override val fail: Exception? = null, override val goto: RtState? = null) : RtAction
    sealed interface G : RtAction {
        data class R(override val fail: Exception? = null, override val goto: RtState? = null) : G
        data class T(override val fail: Exception? = null, override val goto: RtState? = null) : G
    }
}

internal sealed interface RtEvent : Event

internal class CustomBoom : IllegalStateException("custom")

internal enum class HandlerKind { ENTER, ACTION, EXIT, RECOVER }

internal typealias RtBuilder = StoreBuilder<RtState, RtAction, RtEvent>
internal typealias RtConfig<S2> = StoreBuilder.StateHandlerConfig<RtState, RtAction, RtEvent, S2>

internal val statePool: List<RtState> = listOf(
    RtState.A(0), RtState.A(1), RtState.B, RtState.Sub.X(0), RtState.Sub.X(1), RtState.Sub.Y,
)

internal fun exceptionPool(): List<Exception> = listOf(
    IllegalStateException("ise"), IllegalArgumentException("iae"), CustomBoom(),
    UnsupportedOperationException("uoe"), Exception("plain"),
)

internal fun actionOf(type: Int, fail: Exception? = null, goto: RtState? = null): RtAction = when (type) {
    0 -> RtAction.P(fail, goto)
    1 -> RtAction.Q(fail, goto)
    2 -> RtAction.G.R(fail, goto)
    else -> RtAction.G.T(fail, goto)
}

internal val actionPool: List<RtAction> = (0..3).map { actionOf(it) }

internal interface StateKind {
    val type: KClass<out RtState>
    fun register(builder: RtBuilder, block: (RtConfigSink) -> Unit)
}

/** Lets non-generic code register handlers inside a `state<S2> {}` block whose S2 is unknown. */
internal interface RtConfigSink {
    fun <R> with(body: ConfigBody<R>): R
}

internal interface ConfigBody<R> {
    fun <S2 : RtState> run(cfg: RtConfig<S2>): R
}

internal inline fun <reified S2 : RtState> stateKind(): StateKind = object : StateKind {
    override val type: KClass<out RtState> = S2::class
    override fun register(builder: RtBuilder, block: (RtConfigSink) -> Unit) {
        builder.state<S2> {
            val cfg = this
            block(
                object : RtConfigSink {
                    override fun <R> with(body: ConfigBody<R>): R = body.run(cfg)
                },
            )
        }
    }
}

internal interface ActionKind {
    val type: KClass<out RtAction>
    fun <S2 : RtState> add(cfg: RtConfig<S2>, dispatcher: CoroutineDispatcher?, id: Int, log: MutableList<Int>)
}

internal inline fun <reified A2 : RtAction> actionKind(): ActionKind = object : ActionKind {
    override val type: KClass<out RtAction> = A2::class
    override fun <S2 : RtState> add(cfg: RtConfig<S2>, dispatcher: CoroutineDispatcher?, id: Int, log: MutableList<Int>) {
        cfg.action<A2>(dispatcher) {
            log += id
            action.fail?.let { throw it }
            action.goto?.let { target -> nextState { target } }
        }
    }
}

internal interface RecoverKind {
    val type: KClass<out Exception>
    fun <S2 : RtState> add(cfg: RtConfig<S2>, deprecatedAlias: Boolean, id: Int, log: MutableList<Int>)
}

internal inline fun <reified T : Exception> recoverKind(): RecoverKind = object : RecoverKind {
    override val type: KClass<out Exception> = T::class
    override fun <S2 : RtState> add(cfg: RtConfig<S2>, deprecatedAlias: Boolean, id: Int, log: MutableList<Int>) {
        if (deprecatedAlias) {
            @Suppress("DEPRECATION")
            cfg.error<T> { log += id }
        } else {
            cfg.recover<T> { log += id }
        }
    }
}

internal val stateKinds: List<StateKind> = listOf(
    stateKind<RtState>(), stateKind<RtState.A>(), stateKind<RtState.B>(),
    stateKind<RtState.Sub>(), stateKind<RtState.Sub.X>(), stateKind<RtState.Sub.Y>(),
)

internal val actionKinds: List<ActionKind> = listOf(
    actionKind<RtAction>(), actionKind<RtAction.P>(), actionKind<RtAction.Q>(),
    actionKind<RtAction.G>(), actionKind<RtAction.G.R>(), actionKind<RtAction.G.T>(),
)

internal val recoverKinds: List<RecoverKind> = listOf(
    recoverKind<Exception>(), recoverKind<RuntimeException>(), recoverKind<IllegalStateException>(),
    recoverKind<IllegalArgumentException>(), recoverKind<CustomBoom>(),
)

/** Predicates that no single type can express, used by legacy handlers without metadata. */
internal class LegacyPredicate(val name: String, val onState: (RtState) -> Boolean, val onInput: (Any) -> Boolean)

internal val legacyPredicates: List<LegacyPredicate> = listOf(
    LegacyPredicate("even", { it is RtState.A && it.n % 2 == 0 || it is RtState.Sub.X && it.n % 2 == 0 }, { it is RtAction.P || it is RtAction.G.T }),
    LegacyPredicate("odd", { it is RtState.A && it.n % 2 == 1 || it is RtState.Sub.X && it.n % 2 == 1 }, { it is IllegalArgumentException || it is CustomBoom }),
    LegacyPredicate("objects", { it is RtState.B || it is RtState.Sub.Y }, { it is RtAction.Q || it is UnsupportedOperationException }),
    LegacyPredicate("always", { true }, { true }),
    LegacyPredicate("never", { false }, { false }),
)

/**
 * One handler as the generator planned it: its kind, the matcher the registry should report for it,
 * and its ground-truth predicate over (state, input). `input` is the action or the exception, and
 * is ignored for enter and exit handlers.
 */
internal class PlannedHandler(
    val id: Int,
    val kind: HandlerKind,
    val expectedMatcher: HandlerMatcher?,
    val matches: (RtState, Any?) -> Boolean,
)

internal sealed interface Block {
    /** A `state<S2> {}` block holding handlers of mixed kinds, in the order they are written. */
    class StateBlock(val stateKind: StateKind, val inner: List<Inner>) : Block

    /** A handler added the way inline code compiled against an older Actron did: no matcher at all. */
    class LegacyTop(val kind: HandlerKind, val id: Int, val predicate: LegacyPredicate) : Block
}

internal class Inner(
    val kind: HandlerKind,
    val id: Int,
    val actionKind: ActionKind? = null,
    val recoverKind: RecoverKind? = null,
    val legacyPredicate: LegacyPredicate? = null,
    val deprecatedAlias: Boolean = false,
    val dispatcher: CoroutineDispatcher? = null,
)

internal class RoutingProgram(val blocks: List<Block>) {

    /** Handlers of [kind] in the order the Store must try them. */
    fun planned(kind: HandlerKind): List<PlannedHandler> = blocks.flatMap { block ->
        when (block) {
            is Block.LegacyTop -> if (block.kind == kind) {
                listOf(
                    PlannedHandler(block.id, kind, null) { s, input ->
                        block.predicate.onState(s) && (input == null || block.predicate.onInput(input))
                    },
                )
            } else {
                emptyList()
            }

            is Block.StateBlock -> block.inner.filter { it.kind == kind }.map { inner ->
                val stateType = block.stateKind.type
                val inputType = inner.effectiveInputType()
                PlannedHandler(inner.id, kind, HandlerMatcher(stateType, inputType)) { s, input ->
                    stateType.isInstance(s) && when {
                        input == null -> true
                        inner.legacyPredicate != null -> inner.legacyPredicate.onInput(input)
                        else -> inputType!!.isInstance(input)
                    }
                }
            }
        }
    }

    fun install(builder: RtBuilder, log: MutableList<Int>) {
        for (block in blocks) {
            when (block) {
                is Block.LegacyTop -> installLegacyTop(builder, block, log)
                is Block.StateBlock -> block.stateKind.register(builder) { sink ->
                    for (inner in block.inner) sink.with(InnerBody(inner, log))
                }
            }
        }
    }

    private class InnerBody(val inner: Inner, val log: MutableList<Int>) : ConfigBody<Unit> {
        override fun <S2 : RtState> run(cfg: RtConfig<S2>) {
            val id = inner.id
            when (inner.kind) {
                HandlerKind.ENTER -> cfg.enter(inner.dispatcher) { log += id }
                HandlerKind.EXIT -> cfg.exit(inner.dispatcher) { log += id }
                HandlerKind.ACTION -> if (inner.legacyPredicate != null) {
                    val predicate = inner.legacyPredicate
                    cfg.stateActionHandlers.add(
                        StoreBuilder.StateHandlerConfig.ThreadedHandler(
                            dispatcher = inner.dispatcher,
                            predicate = { action: RtAction -> predicate.onInput(action) },
                            handler = {
                                log += id
                                action.fail?.let { throw it }
                                action.goto?.let { target -> nextState { target } }
                            },
                        ),
                    )
                } else {
                    inner.actionKind!!.add(cfg, inner.dispatcher, id, log)
                }

                HandlerKind.RECOVER -> if (inner.legacyPredicate != null) {
                    val predicate = inner.legacyPredicate
                    cfg.stateErrorHandlers.add(
                        StoreBuilder.StateHandlerConfig.ThreadedHandler(
                            dispatcher = inner.dispatcher,
                            predicate = { error: Exception -> predicate.onInput(error) },
                            handler = { log += id },
                        ),
                    )
                } else {
                    inner.recoverKind!!.add(cfg, inner.deprecatedAlias, id, log)
                }
            }
        }
    }

    private fun installLegacyTop(builder: RtBuilder, block: Block.LegacyTop, log: MutableList<Int>) {
        val id = block.id
        val p = block.predicate
        when (block.kind) {
            HandlerKind.ENTER -> builder.registeredEnterHandlers.add(StoreBuilder.StateHandler(predicate = { s: RtState -> p.onState(s) }, handler = { log += id }))
            HandlerKind.EXIT -> builder.registeredExitHandlers.add(StoreBuilder.StateHandler(predicate = { s: RtState -> p.onState(s) }, handler = { log += id }))
            HandlerKind.ACTION -> builder.registeredActionHandlers.add(
                StoreBuilder.StateHandler(
                    predicate = { s: RtState, a: RtAction -> p.onState(s) && p.onInput(a) },
                    handler = {
                        log += id
                        action.fail?.let { throw it }
                        action.goto?.let { target -> nextState { target } }
                    },
                ),
            )

            HandlerKind.RECOVER -> builder.registeredErrorHandlers.add(
                StoreBuilder.StateHandler(predicate = { s: RtState, e: Exception -> p.onState(s) && p.onInput(e) }, handler = { log += id }),
            )
        }
    }

    companion object {
        fun random(random: Random, maxBlocks: Int = 8, legacy: Boolean = true): RoutingProgram {
            var nextId = 0
            val blocks = List(random.nextInt(0, maxBlocks + 1)) {
                if (legacy && random.nextInt(6) == 0) {
                    Block.LegacyTop(HandlerKind.entries.random(random), nextId++, legacyPredicates.random(random))
                } else {
                    val inner = List(random.nextInt(0, 5)) {
                        val kind = HandlerKind.entries.random(random)
                        val dispatcher = if (random.nextInt(4) == 0) Dispatchers.Unconfined else null
                        val isLegacy = legacy && random.nextInt(8) == 0
                        when (kind) {
                            HandlerKind.ACTION -> Inner(
                                kind, nextId++,
                                actionKind = actionKinds.random(random),
                                legacyPredicate = if (isLegacy) legacyPredicates.random(random) else null,
                                dispatcher = dispatcher,
                            )

                            HandlerKind.RECOVER -> Inner(
                                kind, nextId++,
                                recoverKind = recoverKinds.random(random),
                                legacyPredicate = if (isLegacy) legacyPredicates.random(random) else null,
                                deprecatedAlias = random.nextBoolean(),
                            )

                            else -> Inner(kind, nextId++, dispatcher = dispatcher)
                        }
                    }
                    Block.StateBlock(stateKinds.random(random), inner)
                }
            }
            return RoutingProgram(blocks)
        }
    }
}

/** A legacy inner handler has a state type but no input type, since the old constructor did not take one. */
internal fun Inner.effectiveInputType(): KClass<*>? = if (legacyPredicate != null) null else actionKind?.type ?: recoverKind?.type

internal fun randomAction(random: Random): RtAction {
    val fail = if (random.nextInt(3) == 0) exceptionPool().random(random) else null
    val goto = if (fail == null && random.nextBoolean()) statePool.random(random) else null
    return actionOf(random.nextInt(4), fail, goto)
}

internal fun buildRtStore(
    program: RoutingProgram,
    initialState: RtState,
    log: MutableList<Int>,
    configure: RtBuilder.() -> Unit = {},
): Store<RtState, RtAction, RtEvent> = Store(initialState) {
    configure()
    program.install(this, log)
}

/** What the Store should do when started in [initial] and sent one action, per the ground-truth predicates. */
internal class Expectation(val log: List<Int>, val finalState: RtState, val unhandled: List<Exception>)

internal fun RoutingProgram.expect(initial: RtState, action: RtAction): Expectation {
    fun first(kind: HandlerKind, s: RtState, input: Any?) = planned(kind).firstOrNull { it.matches(s, input) }?.id
    val log = mutableListOf<Int>()
    val unhandled = mutableListOf<Exception>()
    var state = initial
    first(HandlerKind.ENTER, initial, null)?.let(log::add)
    val selected = first(HandlerKind.ACTION, initial, action)
    if (selected != null) {
        log += selected
        val fail = action.fail
        val goto = action.goto
        if (fail != null) {
            first(HandlerKind.RECOVER, initial, fail)?.let(log::add) ?: unhandled.add(fail)
        } else if (goto != null && goto != initial) {
            if (goto::class != initial::class) {
                first(HandlerKind.EXIT, initial, null)?.let(log::add)
                first(HandlerKind.ENTER, goto, null)?.let(log::add)
            }
            state = goto
        }
    }
    return Expectation(log, state, unhandled)
}
