# koma-message

A small **process-wide, transient message bus** for ordinary Koma Stores. A sender calls
`message(...)` from a Store DSL scope; the receiving Store installs `receiveMessages` and
turns a message into its own action. The receiver remains responsible for state changes.

Use it for simple notifications among ordinary Stores. For replay-ready groups, use
[MachineGroup routes](../koma-statechart/README.md#running-a-machine): those carry explicit
member/message identities and recorded delivery ordering.

## Dependency

Follow [installation](../README.md#installation), then add:

```kotlin
implementation("io.github.roman-n1:koma-message:5.0.0-alpha.1")
```

## A selection notification

```kotlin
import koma.core.Action
import koma.core.State
import koma.core.Store
import koma.core.StoreScope
import koma.message.Message
import koma.message.message
import koma.message.receiveMessages
import kotlinx.coroutines.CoroutineScope

data class ContactSelected(val id: String) : Message
data class SelectionState(val selectedId: String? = null) : State
data class SelectContact(val id: String) : Action

fun selectionStore(scope: CoroutineScope): Store<SelectionState, SelectContact, Nothing> =
    Store(SelectionState(), context = scope.coroutineContext) {
        plugin(receiveMessages { incoming ->
            if (incoming is ContactSelected) dispatch(SelectContact(incoming.id))
        })
        state<SelectionState> {
            action<SelectContact> {
                nextState { state.copy(selectedId = action.id) }
            }
        }
    }

// Call from the sender's action/enter/launch scope; the receiver handles its own action.
suspend fun StoreScope.notifySelection(id: String) {
    message(ContactSelected(id))
}
```

The plugin subscribes as the Store starts, before that Store's initial enter hooks proceed.
Sending does not start another Store for you: a receiver must already be subscribed to see
the message. Past messages are not replayed, and all subscribers share one process-wide bus.
Keep receiver callbacks short; dispatch an action and let the Store own the resulting work.
Closing the Store ends its subscription. A failed receiver callback is reported, and later
messages can still be received.

This bus does not promise persisted delivery, request/reply pairing or deterministic replay.
Do not use it as a replacement for recorded MachineGroup routes.
See [Message.kt](src/commonMain/kotlin/koma/message/Message.kt),
[Plugin.kt](src/commonMain/kotlin/koma/message/Plugin.kt) and the
[extended message example](../doc/guides/store-api.md#message).
