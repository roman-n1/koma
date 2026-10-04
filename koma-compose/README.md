# koma-compose

Render an ordinary Koma Store with Compose Multiplatform. `ViewStore` collects state,
dispatches actions and collects transient events; it does not create a second state machine.
This module depends on core, not on the statechart or debug inspector.

## Dependency

See [installation](../README.md#installation) for the fork's composite-build setup.

```kotlin
implementation("io.github.roman-n1:koma-compose:5.0.0-alpha.1")
```

## Render the counter

Use `CounterState`, `CounterAction` and `counterStore` from the
[root example](../README.md#1-a-store-for-a-counter). The owner creates the Store once and
passes it to the UI:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import koma.compose.rememberViewStore
import koma.core.Store

@Composable
fun CounterScreen(store: Store<CounterState, CounterAction, Nothing>) {
    val viewStore = rememberViewStore(store)
    val count = viewStore.select { it.count }
    Text("Count: $count")
    Button(onClick = { viewStore.dispatch(CounterAction.Increment) }) {
        Text("Increment")
    }
}
```

`select` derives a value with a pure mapper; readers recompose when that value changes.
Use `viewStore.state` when you need the complete current state.

## Pick the helper you need

| Helper | Purpose |
|---|---|
| `rememberViewStore(store)` | Collect an externally owned Store; does not close it by default |
| `rememberViewStore(store, autoClose = true)` | Close this Store when the composition leaves, if the composition owns its lifetime |
| `viewStore.stateContent<S2> { … }` | Render one sealed-state variant with a narrowed type |
| `viewStore.eventEffect<E2> { … }` | Collect transient events of a selected type |
| `ViewStore(state, dispatch = …)` | A fixed initial value for previews and UI tests |
| `rememberStateSaver<S>()` | Compose-backed state saving; see its [source contract](src/commonMain/kotlin/koma/compose/StateSaver.kt) |

Do not create a new Store on every recomposition. A navigation/retained owner usually owns
the Store, so leaving and re-entering the composition need not discard it. Transient events
are not replayed to collectors that appear later. For retained Machine effects, use
[koma-statechart-compose](../koma-statechart-compose/README.md).

See the [Compose reference](../doc/guides/store-api.md#compose) and
[ViewStore source](src/commonMain/kotlin/koma/compose/ViewStore.kt) for complete helper contracts.
