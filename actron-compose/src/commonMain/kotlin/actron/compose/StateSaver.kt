package actron.compose

import androidx.compose.runtime.Composable
import io.github.takahirom.rin.rememberRetained
import actron.core.ExperimentalActronApi
import actron.core.State
import actron.core.StateSaver

private class StateSaverImpl<S : State> : StateSaver<S> {
    private var restoredState: (S) -> S = { it }

    override fun save(state: S) {
        restoredState = { state }
    }

    override fun restore(initialState: S): S {
        return restoredState(initialState)
    }
}

/**
 * Remembers an in-memory [StateSaver] that survives recomposition and configuration changes.
 *
 * The saver is retained through `rememberRetained` (rin), which needs a `ViewModelStoreOwner`
 * and a `LifecycleOwner` in the composition. It keeps the value while the composable leaves
 * during a configuration change and drops it when the composable leaves an active screen; it
 * does not survive process death. Retained values are keyed by the call site's position, so
 * content repeated in a loop or a list must wrap each item in `key(itemId) { }`, or the savers
 * are handed out in composition order and swap on reorder.
 *
 * @return A [StateSaver] for preserving state snapshots in Compose
 */
@ExperimentalActronApi
@Composable
fun <S : State> rememberStateSaver(): StateSaver<S> {
    return rememberRetained {
        StateSaverImpl()
    }
}
