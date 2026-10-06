package legacy

import koma.core.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

// This body is compiled against published Koma 4.0.0, then frozen as jar/klib.
// Idle(0) -> Idle(1) -> Idle(2) -> recover Idle(12) -> Done(12).
private sealed interface Snapshot : State {
    data class Idle(val count: Int) : Snapshot
    data class Done(val count: Int) : Snapshot
}
private sealed interface Input : Action {
    data object Increment : Input
    data object Launch : Input
    data object Fail : Input
    data object Finish : Input
}
private data class Notice(val count: Int) : Event
private class LegacyPlugin(private val seen: MutableList<String>) : Plugin<Snapshot, Input, Notice> {
    override suspend fun onEvent(scope: PluginScope<Snapshot, Input>, state: Snapshot, event: Notice) { seen += "event:${event.count}" }
}

suspend fun exercise(context: CoroutineContext): String {
    val owner = SupervisorJob(context[Job])
    val scope = CoroutineScope(context + owner)
    val started = CompletableDeferred<Unit>()
    val incremented = CompletableDeferred<Unit>()
    val launched = CompletableDeferred<Unit>()
    val recovered = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val doneEntered = CompletableDeferred<Unit>()
    val saved = mutableListOf<Snapshot>()
    val seen = mutableListOf<String>()
    var exits = 0
    val store = Store<Snapshot, Input, Notice>(Snapshot.Idle(-1), scope.coroutineContext) {
        stateSaver(StateSaver(save = { saved += it }, restore = { Snapshot.Idle(0) }))
        pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)
        plugin(LegacyPlugin(seen))
        plugin(Plugin(onState = { _, state ->
            when (state) {
                Snapshot.Idle(1) -> incremented.complete(Unit)
                Snapshot.Idle(2) -> launched.complete(Unit)
                Snapshot.Idle(12) -> recovered.complete(Unit)
                Snapshot.Done(12) -> finished.complete(Unit)
                else -> Unit
            }
        }))
        state<Snapshot.Idle> {
            enter { started.complete(Unit) }
            action<Input.Increment> { event(Notice(1)); nextState { state.copy(count = state.count + 1) } }
            action<Input.Launch> { launch { transaction { event(Notice(2)); nextState { state.copy(count = state.count + 1) } } } }
            action<Input.Fail> { throw IllegalStateException("legacy recovery") }
            recover<IllegalStateException> { check(error.message == "legacy recovery"); nextState { state.copy(count = state.count + 10) } }
            action<Input.Finish> { nextState { Snapshot.Done(state.count) } }
            exit { exits++ }
        }
        state<Snapshot.Done> { enter { doneEntered.complete(Unit) } }
    }
    try {
        check(store.currentState == Snapshot.Idle(0))
        store.start(); started.await()
        store.dispatch(Input.Increment); incremented.await()
        store.dispatch(Input.Launch); launched.await()
        store.dispatch(Input.Fail); recovered.await()
        store.dispatch(Input.Finish); finished.await(); doneEntered.await()
        check(store.currentState == Snapshot.Done(12))
        check(saved == listOf(Snapshot.Idle(1), Snapshot.Idle(2), Snapshot.Idle(12), Snapshot.Done(12))) { "Saved: $saved" }
        check(seen == listOf("event:1", "event:2")) { "Events: $seen" }
        check(exits == 1)
        return "stable-4.0.0: state/action/launch/transaction/recover/plugin/saver/exit passed"
    } finally {
        store.close()
        withContext(NonCancellable) { owner.cancelAndJoin() }
    }
}
