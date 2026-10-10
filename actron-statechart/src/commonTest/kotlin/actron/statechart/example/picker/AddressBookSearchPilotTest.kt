@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.example.picker

import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.observability.JournalEntry
import actron.observability.JournalFormat
import actron.observability.MachineGroupId
import actron.observability.PayloadPolicy
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.statechart.recordTo
import actron.statechart.example.picker.AddressBookSearchMachine.Contact
import actron.statechart.example.picker.AddressBookSearchMachine.Group
import actron.statechart.example.picker.AddressBookSearchMachine.Guest
import actron.statechart.example.picker.AddressBookSearchMachine.Role
import actron.statechart.example.picker.AddressBookSearchMachine.SearchAction
import actron.statechart.example.picker.AddressBookSearchMachine.SearchCommand
import actron.statechart.example.picker.AddressBookSearchMachine.SearchContext
import actron.statechart.example.picker.AddressBookSearchMachine.SearchEvent
import actron.statechart.example.picker.AddressBookSearchMachine.debouncing
import actron.statechart.example.picker.AddressBookSearchMachine.groupsReady
import actron.statechart.example.picker.AddressBookSearchMachine.idle
import actron.statechart.example.picker.AddressBookSearchMachine.results
import actron.statechart.example.picker.AddressBookSearchMachine.searching
import actron.statechart.example.picker.AddressBookSearchMachine.toUiModel
import actron.statechart.machine.CommandEnvelope
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandId
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.ResultSink
import actron.test.startAndAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The stage 3 scenarios of the handoff on [AddressBookSearchMachine]: fast query changes, old
 * answers, closing during a request, selection without leaving the search, two tabs of the same
 * component in one journal.
 */
class AddressBookSearchPilotTest {

    private val alice = Contact("1", "Alice", email = "alice@example.com")
    private val alina = Contact("2", "Alina")
    private val bob = Contact("3", "Bob")
    private val directory = listOf(alice, alina, bob)
    private val groups = listOf(Group("g1", "Engineering"), Group("g2", "Sales"))

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** A fake `SearchContactsUseCase` and `GetContactGroupsUseCase` with controllable latency. */
    private inner class FakeRepository : CommandHandler<SearchCommand, SearchAction> {
        val searches = mutableListOf<SearchCommand.Search>()
        val cancelledSearches = mutableListOf<String>()
        val sinks = mutableMapOf<CommandId, ResultSink<SearchAction>>()
        var searchLatency = 100.milliseconds
        var failNext = false
        var groupsFail = false

        override suspend fun execute(command: CommandEnvelope<SearchCommand>, results: ResultSink<SearchAction>) {
            sinks[command.id] = results
            when (val c = command.command) {
                is SearchCommand.LoadGroups -> {
                    delay(10.milliseconds)
                    if (groupsFail) throw IllegalStateException("groups unavailable")
                    results.result(SearchAction.GroupsLoaded(groups))
                }
                is SearchCommand.Search -> {
                    searches += c
                    try {
                        delay(searchLatency)
                    } catch (e: CancellationException) {
                        cancelledSearches += c.query
                        throw e
                    }
                    if (failNext) {
                        failNext = false
                        results.result(SearchAction.SearchFailed("search error"))
                        return
                    }
                    results.result(SearchAction.ContactsFound(directory.filter { it.displayName.startsWith(c.query, ignoreCase = true) }))
                }
            }
        }
    }

    private class Tab(scope: TestScope, val repository: FakeRepository, session: RecordingSession?, id: String) {
        val handled = mutableListOf<Throwable>()
        val events = mutableListOf<SearchEvent>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val store = MachineStore(AddressBookSearchMachine.machine(), SearchContext(), repository, executionScope, TestClock(scope.testScheduler), dispatcher) {
            exceptionHandler(ExceptionHandler { handled += it })
            if (session != null) recordTo(session, StoreInstanceId(id), PayloadPolicy.metadataOnly())
        }
        private val collector = executionScope.launch { store.event.collect { events += it } }

        val ui get() = store.currentState.toUiModel()

        fun type(query: String) = store.dispatch(SearchAction.QueryChanged(query))

        fun close() {
            store.close()
            collector.cancel()
            executionScope.cancel()
        }
    }

    private suspend fun TestScope.tab(repository: FakeRepository = FakeRepository(), session: RecordingSession? = null, id: String = "picker-search-1"): Tab {
        val tab = Tab(this, repository, session, id)
        tab.store.startAndAwait()
        runCurrent()
        return tab
    }

    @Test
    fun typingFast_searchesOnceAfterTheDebounce_withTheLastQuery() = runTest {
        val tab = tab()

        tab.type("a")
        runCurrent()
        assertFalse(tab.ui.isLoading, "one letter is too short")
        tab.type("al")
        advanceTimeBy(100.milliseconds); runCurrent()
        tab.type("ali")
        advanceTimeBy(100.milliseconds); runCurrent()
        assertTrue(tab.ui.isLoading, "debouncing shows as loading, as in the app")
        assertTrue(tab.repository.searches.isEmpty(), "no search before the debounce")
        advanceTimeBy(AddressBookSearchMachine.debounce); runCurrent()

        assertEquals(listOf(SearchCommand.Search("ali", null)), tab.repository.searches, "one search, for the last query")
        advanceTimeBy(100.milliseconds); runCurrent()
        assertEquals(listOf(alice, alina), tab.ui.contacts)
        assertTrue(tab.ui.isSearchDone && !tab.ui.isLoading)
        assertEquals("ali", tab.ui.query)
        tab.close()
    }

    @Test
    fun aSlowAnswerToAnOldQuery_neverReachesTheScreen() = runTest {
        val repository = FakeRepository().apply { searchLatency = 1_000.milliseconds }
        val tab = tab(repository)

        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce); runCurrent()
        assertEquals(listOf("al"), repository.searches.map { it.query })
        val oldSearch = repository.sinks.keys.last()
        tab.type("bo")
        advanceTimeBy(AddressBookSearchMachine.debounce); runCurrent()

        assertEquals(listOf("al"), repository.cancelledSearches, "the old search was cancelled with its activation")
        assertEquals(listOf("al", "bo"), repository.searches.map { it.query })
        // The old request answers late anyway (a handler that ignored cancellation): stale, ignored.
        repository.sinks.getValue(oldSearch).result(SearchAction.ContactsFound(listOf(alice, alina)))
        runCurrent()
        assertTrue(tab.ui.isLoading && tab.ui.contacts.isEmpty(), "still waiting for 'bo'")
        advanceTimeBy(1_000.milliseconds); runCurrent()
        assertEquals(listOf(bob), tab.ui.contacts)
        tab.close()
    }

    @Test
    fun closingDuringARequest_cancelsIt_andCommitsNothingAfterwards() = runTest {
        val repository = FakeRepository().apply { searchLatency = 1_000.milliseconds }
        val tab = tab(repository)
        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce); runCurrent()
        val pending = repository.sinks.keys.last()
        val before = tab.store.currentState

        tab.store.close()
        runCurrent()

        assertEquals(listOf("al"), repository.cancelledSearches)
        repository.sinks.getValue(pending).result(SearchAction.ContactsFound(listOf(alice)))
        advanceTimeBy(2_000.milliseconds); runCurrent()
        assertEquals(before, tab.store.currentState, "nothing commits after close")
        assertTrue(tab.handled.isEmpty())
        tab.close()
    }

    @Test
    fun selection_updatesTheContextWithoutLeavingResults_orTouchingTheSearch() = runTest {
        val tab = tab()
        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()
        val settled = tab.store.currentState
        assertTrue(settled.isActive(results))

        tab.store.dispatch(SearchAction.ToggleSelect(alice.id))
        tab.store.dispatch(SearchAction.RoleSelected(alice.id, Role.Speaker))
        tab.store.dispatch(SearchAction.ToggleSelect("missing"))
        runCurrent()

        val after = tab.store.currentState
        assertEquals(settled.configuration, after.configuration, "still in Results")
        assertEquals(settled.activations, after.activations, "no node was re-entered")
        assertEquals(settled.commands, after.commands, "no command registered or cancelled")
        assertEquals(setOf(alice.id), tab.ui.selectedContactIds)
        assertEquals(mapOf(alice.id to Role.Speaker), tab.ui.selectedRoles)
        assertTrue(tab.ui.isDoneEnabled)
        assertEquals(settled.revision + 3, after.revision, "three handled inputs; the unknown contact left the context equal, yet its input was handled")

        tab.store.dispatch(SearchAction.Done)
        runCurrent()
        assertEquals(
            listOf<SearchEvent>(SearchEvent.DoneClicked(AddressBookSearchMachine.PickerResult(listOf(alice), mapOf(alice.id to Role.Speaker), emptyList()))),
            tab.events,
        )
        tab.close()
    }

    @Test
    fun groupChange_reSearchesAtOnce_butOnlyRemembersTheGroupWhileIdle() = runTest {
        val tab = tab()

        tab.store.dispatch(SearchAction.GroupChanged("g1"))
        runCurrent()
        assertTrue(tab.store.currentState.isActive(idle) && tab.repository.searches.isEmpty())
        assertEquals("g1", tab.ui.selectedGroupId)

        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()
        assertTrue(tab.store.currentState.isActive(results))
        tab.store.dispatch(SearchAction.GroupChanged("g2"))
        runCurrent()

        assertTrue(tab.store.currentState.isActive(searching))
        assertEquals(listOf(SearchCommand.Search("al", "g1"), SearchCommand.Search("al", "g2")), tab.repository.searches)
        tab.close()
    }

    @Test
    fun aShortQuery_clearsTheResults_andAGuestIsSuggestedForAnAddress() = runTest {
        val tab = tab()
        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()
        assertEquals(2, tab.ui.contacts.size)

        tab.type("a")
        runCurrent()
        assertTrue(tab.store.currentState.isActive(idle))
        assertTrue(tab.ui.contacts.isEmpty() && !tab.ui.isSearchDone && tab.ui.query == "a")

        tab.type("new@example.com")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()
        assertTrue(tab.ui.contacts.isEmpty() && tab.ui.canAddGuestEmail && !tab.ui.canAddGuestPhone)
        tab.store.dispatch(SearchAction.ToggleGuest(Guest.Email("new@example.com ")))
        runCurrent()
        assertEquals(listOf<Guest>(Guest.Email("new@example.com")), tab.ui.guests)
        assertTrue(tab.store.currentState.isActive(idle) && tab.ui.query.isEmpty())
        assertTrue(tab.ui.isDoneEnabled)
        tab.close()
    }

    @Test
    fun searchFailure_showsAnError_andRetrySearchesAgain() = runTest {
        val repository = FakeRepository().apply { failNext = true }
        val tab = tab(repository)
        tab.type("al")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()

        assertEquals("search error", tab.ui.errorMessage)
        assertEquals(listOf<SearchEvent>(SearchEvent.ShowError("search error")), tab.events)
        tab.store.dispatch(SearchAction.Retry)
        advanceTimeBy(100.milliseconds); runCurrent()
        assertEquals(listOf(alice, alina), tab.ui.contacts)
        assertEquals(null, tab.ui.errorMessage)
        tab.close()
    }

    @Test
    fun groups_loadOnStart_andAFailureLeavesTheAllContactsEntry() = runTest {
        val tab = tab()
        advanceTimeBy(10.milliseconds); runCurrent()
        assertEquals(listOf(Group(null, "")) + groups, tab.ui.groups)
        assertTrue(tab.store.currentState.isActive(groupsReady))
        tab.close()

        val failing = tab(FakeRepository().apply { groupsFail = true })
        advanceTimeBy(10.milliseconds); runCurrent()
        assertTrue(failing.store.currentState.isActive(groupsReady))
        assertTrue(failing.ui.groups.isEmpty(), "the app falls back to an empty list")
        assertEquals(1, failing.handled.size, "the failure was reported")
        failing.close()
    }

    @Test
    fun twoTabsOfTheSameComponent_areIndependent_andShareOneJournalWithoutQueries() = runTest {
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("run-1"), group = MachineGroupId("picker"))
        val first = tab(session = session, id = "picker-search-tab-1")
        val second = tab(session = session, id = "picker-search-tab-2")

        first.type("al")
        second.type("bo")
        advanceTimeBy(AddressBookSearchMachine.debounce + 100.milliseconds); runCurrent()
        assertEquals(listOf(alice, alina), first.ui.contacts)
        assertEquals(listOf(bob), second.ui.contacts)

        first.close()
        runCurrent()
        second.store.dispatch(SearchAction.ToggleSelect(bob.id))
        runCurrent()
        assertEquals(setOf(bob.id), second.ui.selectedContactIds, "the surviving tab keeps working")

        val records = session.records()
        assertEquals((1L..records.size).toList(), records.map { it.groupSeq.value }, "one dense sequence for the group")
        val stores = records.mapNotNull { it.store }.distinct()
        assertEquals(listOf(StoreInstanceId("picker-search-tab-1"), StoreInstanceId("picker-search-tab-2")), stores)
        stores.forEach { store ->
            val own = records.filter { it.store == store }
            assertEquals((1L..own.size).toList(), own.map { it.storeSeq?.value }, "dense per tab")
        }
        assertTrue(records.filter { it.store == stores.first() }.any { it.entry is JournalEntry.StoreClosed })
        val text = records.joinToString("\n") { JournalFormat.line(it) }
        assertFalse("al" in text.substringAfter("run-1 picker picker-search-tab-1").take(0) || Regex("\\b(al|bo|Alice|Bob)\\b").containsMatchIn(text), "no query or contact reaches the journal under the production policy")
        second.close()
        session.close()
    }
}
