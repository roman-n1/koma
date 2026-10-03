# koma-core

The smallest Koma module: a `Store` receives **actions**, changes immutable **state**, and
emits optional **events**. It exposes `StateFlow` for rendering and owns coroutine work with
state lifetimes. You do not need a statechart or Time Travel to use it.

## Dependency

Follow the [root installation guide](../README.md#installation) for composite-build setup and
release availability. The fork coordinates are:

```kotlin
implementation("io.github.roman-n1:koma-core:5.0.0-alpha.1")
```

## Start with one data state

Use the [complete counter example](../README.md#1-a-store-for-a-counter). A data class such as
`CounterState(count)` is enough: an Increment handler calls
`nextState { state.copy(count = state.count + 1) }`. The UI dispatches actions and collects
`store.state`; it never mutates the Store state directly.

## States for a loading screen

Make phases explicit when they change what the feature can do. Here only Idle/Error can
start loading; Loading owns the request, and Content holds its result.

```kotlin
import koma.core.Action
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

sealed interface ItemsState : State {
    data object Idle : ItemsState
    data object Loading : ItemsState
    data class Content(val items: List<String>) : ItemsState
    data class Error(val message: String) : ItemsState
}

data object LoadItems : Action

fun itemsStore(scope: CoroutineScope, load: suspend () -> List<String>): Store<ItemsState, LoadItems, Nothing> =
    Store(ItemsState.Idle, context = scope.coroutineContext) {
        state<ItemsState.Idle> {
            action<LoadItems> { nextState { ItemsState.Loading } }
        }
        state<ItemsState.Loading> {
            enter {
                launch {
                    val result: ItemsState = try {
                        ItemsState.Content(load())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ItemsState.Error(e.message ?: "Could not load items")
                    }
                    transaction { nextState { result } }
                }
            }
        }
        state<ItemsState.Error> {
            action<LoadItems> { nextState { ItemsState.Loading } }
        }
    }
```

Dispatch `LoadItems` to load or retry. A Content state needs no handler here because this
example does not offer Refresh; add a Content → Loading handler if your screen does.
`launch` is cancelled when Loading exits or the Store closes. `transaction` commits against
the Store's state under its lock; a cancelled/expired launch cannot commit a late result.

Use typed actions/state for expected business outcomes. `recover<T> { … }` is available for
exception recovery; the [full guide](../doc/guides/store-api.md#error-handling) explains it.

## Lifetimes and asynchronous work

| API | What it owns |
|---|---|
| `enter {}` / `exit {}` | Entering or leaving a state type |
| `action<A> {}` | One matching input's processing |
| `launch {}` | Finite asynchronous work belonging to that state's runtime |
| `subscribe {}` | A long-lived Flow/socket subscription; settling tests do not wait for it to end |
| `transaction {}` | A launched task's serialized state update |
| `StateSaver` | Restoring/saving application state; schema migrations belong to the application |

Keep the Store in a ViewModel, retained component or other lifecycle owner. Supply its scope
and call `close()` when that owner is destroyed. `dispatch()` queues an action; awaiting its
processing in a test uses [koma-test](../koma-test/README.md). Ordinary Store events are
transient: attach a collector before emitting them when delivery matters.

Use [koma-compose](../koma-compose/README.md) to render a Store. Choose
[koma-statechart](../koma-statechart/README.md) when nested/parallel phases or replay-ready
decisions are needed. Core's Kotlin state objects and chart nodes are different concepts.

## Reference

- [Full Store API guide](../doc/guides/store-api.md): events, launches, persistence, recovery and plugins.
- [Store interface](src/commonMain/kotlin/koma/core/Store.kt) and
  [builder](src/commonMain/kotlin/koma/core/StoreBuilder.kt).
- [Fork differences](../README.md#what-differs-from-the-original-project) and
  [stability boundary](../doc/internal/adr/2026-10-01-stable-core.md).
