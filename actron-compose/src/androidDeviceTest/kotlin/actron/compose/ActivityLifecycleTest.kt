package actron.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.State
import actron.core.StateSaver
import actron.core.Store
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Real Android lifecycle: active -> recreate/stop/remove/key replacement -> active. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalActronApi::class)
class ActivityLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<LifecycleTestActivity>? = null

    @Before fun setUp() { LifecycleHarness.fixture = Fixture() }

    @After fun tearDown() {
        scenario?.close()
        LifecycleHarness.fixture.allStores.forEach { it.delegate.close() }
    }

    private fun launch(): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().context
        scenario = ActivityScenario.launch(Intent(context, LifecycleTestActivity::class.java))
        compose.waitForIdle()
        return LifecycleHarness.fixture
    }

    @Test fun recreationRetainsSaverRestoresSnapshotAndClosesOldCompositionStore() {
        val fixture = launch()
        lateinit var original: Handle
        compose.runOnIdle { original = fixture.handles.getValue("left"); original.view.dispatch(Increment) }
        compose.waitUntil(timeoutMillis = 10_000) { original.store.currentState.value == 1 && original.view.state.value == 1 }

        scenario!!.recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            val restored = fixture.handles.getValue("left")
            assertSame(original.saver, restored.saver)
            assertNotSame(original.store, restored.store)
            assertEquals(Counter(1), restored.view.state)
            assertEquals(1, original.store.closes.get())
            assertEquals(0, restored.store.closes.get())
        }
        scenario!!.close()
        compose.runOnIdle { assertEquals(1, fixture.handles.getValue("left").store.closes.get()) }
    }

    @Test fun removingScreenDiscardsSaverAndReturningCreatesFreshStore() {
        val fixture = launch()
        lateinit var original: Handle
        compose.runOnIdle { original = fixture.handles.getValue("left"); original.view.dispatch(Increment) }
        compose.waitUntil(timeoutMillis = 10_000) { original.view.state.value == 1 }
        compose.runOnIdle { fixture.tabs.value = emptyList() }
        compose.runOnIdle { assertEquals(1, original.store.closes.get()); fixture.tabs.value = listOf("left") }
        compose.runOnIdle {
            val fresh = fixture.handles.getValue("left")
            assertNotSame(original.saver, fresh.saver)
            assertNotSame(original.store, fresh.store)
            assertEquals(Counter(0), fresh.view.state)
        }
    }

    @Test fun keyedInstancesForSameChatKeepStateAcrossReorderAndCloseOnlyRemovedTab() {
        val fixture = LifecycleHarness.fixture
        fixture.tabs.value = listOf("left", "right")
        launch()
        lateinit var left: Handle
        lateinit var right: Handle
        compose.runOnIdle {
            left = fixture.handles.getValue("left"); right = fixture.handles.getValue("right")
            assertNotSame(left.saver, right.saver)
            left.view.dispatch(Increment)
            right.view.dispatch(Increment); right.view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) { left.view.state.value == 1 && right.view.state.value == 2 }
        compose.runOnIdle { fixture.tabs.value = listOf("right", "left") }
        compose.runOnIdle {
            assertSame(left.store, fixture.handles.getValue("left").store)
            assertSame(right.saver, fixture.handles.getValue("right").saver)
            assertEquals(Counter(1), left.saver.restore())
            assertEquals(Counter(2), right.saver.restore())
            fixture.tabs.value = listOf("right")
        }
        compose.runOnIdle {
            assertEquals(1, left.store.closes.get())
            assertEquals(0, right.store.closes.get())
            fixture.tabs.value = listOf("right", "left")
        }
        compose.runOnIdle {
            assertEquals(Counter(0), fixture.handles.getValue("left").view.state)
            assertEquals(Counter(2), fixture.handles.getValue("right").view.state)
        }
    }

    @Test fun storeKeyReplacementClosesOwnedStoreButKeepsRetainedSnapshot() {
        val fixture = launch()
        lateinit var original: Handle
        compose.runOnIdle { original = fixture.handles.getValue("left"); original.view.dispatch(Increment) }
        compose.waitUntil(timeoutMillis = 10_000) { original.view.state.value == 1 }
        compose.runOnIdle { fixture.storeKey.value = "replacement" }
        compose.runOnIdle {
            val replacement = fixture.handles.getValue("left")
            assertNotSame(original.store, replacement.store)
            assertSame(original.saver, replacement.saver)
            assertEquals(Counter(1), replacement.view.state)
            assertEquals(1, original.store.closes.get())
        }
    }

    @Test fun backgroundAndRecreationDoNotCloseExternallyOwnedStore() {
        val fixture = LifecycleHarness.fixture
        fixture.ownsStore = false
        launch()
        lateinit var original: Handle
        compose.runOnIdle { original = fixture.handles.getValue("left"); original.view.dispatch(Increment) }
        compose.waitUntil(timeoutMillis = 10_000) { original.view.state.value == 1 }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            val current = fixture.handles.getValue("left")
            assertSame(original.store, current.store)
            assertSame(original.saver, current.saver)
            assertEquals(Counter(1), current.view.state)
            assertEquals(0, original.store.closes.get())
        }
        scenario!!.close()
        assertEquals(0, original.store.closes.get())
    }
}

/** Only in the instrumentation APK: an actual Activity with real owners and a Compose root. */
class LifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LifecycleHarness.fixture.Content() }
    }
}

private object LifecycleHarness { lateinit var fixture: Fixture }
private data class Counter(val value: Int) : State
private data object Increment : Action
private data class Handle(
    val saver: StateSaver<Counter>,
    val store: TrackedStore,
    val view: ViewStore<Counter, Increment, Nothing>,
)
private class TrackedStore(val delegate: Store<Counter, Increment, Nothing>) : Store<Counter, Increment, Nothing> by delegate {
    val closes = AtomicInteger()
    override fun close() { closes.incrementAndGet(); delegate.close() }
}

@OptIn(ExperimentalActronApi::class)
private class Fixture {
    val tabs = mutableStateOf(listOf("left"))
    val storeKey = mutableStateOf("same-chat")
    var ownsStore = true
    val handles = mutableMapOf<String, Handle>()
    val allStores = mutableListOf<TrackedStore>()
    private val external = mutableMapOf<String, TrackedStore>()
    private val adopted = mutableMapOf<String, TrackedStore>()

    private fun newStore(saver: StateSaver<Counter>): TrackedStore = TrackedStore(
        Store<Counter, Increment, Nothing>(Counter(0)) {
            stateSaver(saver)
            state<Counter> { action<Increment> { nextState { state.copy(value = state.value + 1) } } }
        },
    ).also { allStores += it }

    @Composable fun Content() {
        for (tab in tabs.value) key(tab) {
            val saver = rememberStateSaver<Counter>()
            val view = if (ownsStore) {
                rememberViewStore(key = storeKey.value, autoClose = true) {
                    newStore(saver).also { adopted[tab] = it }
                }
            } else {
                val store = external.getOrPut(tab) { newStore(saver) }
                adopted[tab] = store
                rememberViewStore(store = store, autoClose = false)
            }
            SideEffect { handles[tab] = Handle(saver, adopted.getValue(tab), view) }
        }
    }

}
