@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.example.picker

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandFailure
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineSnapshot
import kotlin.time.Duration.Companion.milliseconds

/**
 * The stage 3 pilot: the search screen of the messenger's address-book picker as a replay-ready
 * [Machine]. It mirrors the real `AddressBookPickerSearchComponent`: a query with a minimum
 * length and a 350 ms debounce, a search that the latest query supersedes, results or an error,
 * a group filter that re-searches at once, contact selection with roles, guests suggested from
 * a query that looks like an email or a phone, and Back/Done news for the parent.
 *
 * ```
 * [*] --> Search                                          parallel
 * state Search {
 *     state Query {
 *         [*] --> Idle
 *         Idle --QueryChanged [longEnough] / rememberQuery--> Debouncing
 *         Debouncing --QueryChanged [longEnough] / rememberQuery--> Debouncing   restarts the debounce
 *         Debouncing --after 350ms--> Searching                                 onEnter: command Search(query, group) in lane "search", Latest
 *         Searching --ContactsFound / storeContacts--> Results
 *         Searching --SearchFailed / storeError--> Failed                        onEnter: event ShowError
 *         Searching --QueryChanged [longEnough] / rememberQuery--> Debouncing    the running search is cancelled with Searching
 *         Searching --GroupChanged / rememberGroup--> Searching                  a new search
 *         Results --QueryChanged [longEnough] / rememberQuery--> Debouncing
 *         Results --GroupChanged / rememberGroup--> Searching
 *         Failed --QueryChanged [longEnough] / rememberQuery--> Debouncing
 *         Failed --Retry--> Searching
 *         Failed --GroupChanged / rememberGroup--> Searching
 *         Query --QueryChanged [tooShort] / forgetResults--> Idle                from every state but Idle
 *         Query --ToggleGuest / toggleGuest--> Idle
 *     }
 *     --
 *     state Groups {
 *         [*] --> LoadingGroups                                                 onEnter: command LoadGroups
 *         LoadingGroups --GroupsLoaded / storeGroups--> GroupsReady
 *         LoadingGroups --CommandFailure--> GroupsReady                         an empty group list, as the app does
 *     }
 * }
 * Search: onAction QueryChanged (short, in Idle) / GroupChanged (no search running) / ToggleSelect / RoleSelected / Back / Done
 * ```
 *
 * What no transition takes is an action handler on `Search`: it updates the context (the query
 * text, the group, the selection) without leaving any node, so no search is cancelled by a
 * selection and no timer restarts because a group was picked while idle.
 */
object AddressBookSearchMachine {

    data class Contact(val id: String, val displayName: String, val email: String? = null, val isEnabled: Boolean = true)

    data class Group(val id: String?, val title: String)

    enum class Role { Participant, Speaker }

    sealed interface Guest {
        val value: String

        data class Email(override val value: String) : Guest
        data class Phone(override val value: String) : Guest
    }

    data class PickerResult(val contacts: List<Contact>, val roles: Map<String, Role>, val guests: List<Guest>)

    sealed interface SearchAction : Action {
        data class QueryChanged(val query: String) : SearchAction
        data class GroupChanged(val groupId: String?) : SearchAction
        data class ToggleSelect(val contactId: String) : SearchAction
        data class RoleSelected(val contactId: String, val role: Role) : SearchAction
        data class ToggleGuest(val guest: Guest) : SearchAction
        data object Retry : SearchAction
        data object Back : SearchAction
        data object Done : SearchAction

        /** Results of commands. */
        data class ContactsFound(val contacts: List<Contact>) : SearchAction
        data class SearchFailed(val message: String) : SearchAction
        data class GroupsLoaded(val groups: List<Group>) : SearchAction
    }

    sealed interface SearchEvent : Event {
        data object BackClicked : SearchEvent
        data class DoneClicked(val result: PickerResult) : SearchEvent
        data class ShowError(val message: String) : SearchEvent
    }

    sealed interface SearchCommand {
        data class Search(val query: String, val groupId: String?) : SearchCommand
        data object LoadGroups : SearchCommand
    }

    /** The business data; everything the UI shows derives from it and the configuration. */
    data class SearchContext(
        val query: String = "",
        val groupId: String? = null,
        val groups: List<Group> = emptyList(),
        val contacts: List<Contact> = emptyList(),
        val isSearchDone: Boolean = false,
        val error: String? = null,
        val selectedIds: List<String> = emptyList(),
        val selectedById: Map<String, Contact> = emptyMap(),
        val roles: Map<String, Role> = emptyMap(),
        val guests: List<Guest> = emptyList(),
        val canAddGuestEmail: Boolean = false,
        val canAddGuestPhone: Boolean = false,
        val minQueryLength: Int = 2,
        val allowGuests: Boolean = true,
    ) {
        val trimmedQuery: String get() = query.trim()
        val isQueryLongEnough: Boolean get() = trimmedQuery.length >= minQueryLength
    }

    val search = StateId("Search")
    val queryRegion = StateId("Query")
    val idle = StateId("Idle")
    val debouncing = StateId("Debouncing")
    val searching = StateId("Searching")
    val results = StateId("Results")
    val failed = StateId("Failed")
    val groupsRegion = StateId("Groups")
    val loadingGroups = StateId("LoadingGroups")
    val groupsReady = StateId("GroupsReady")

    val searchLane = LaneId("search")
    val debounce = 350.milliseconds

    private val queryChanged = ActionMatcher.of<SearchAction.QueryChanged>("QueryChanged")
    private val groupChanged = ActionMatcher.of<SearchAction.GroupChanged>("GroupChanged")
    private val contactsFound = ActionMatcher.of<SearchAction.ContactsFound>("ContactsFound")
    private val searchFailed = ActionMatcher.of<SearchAction.SearchFailed>("SearchFailed")
    private val groupsLoaded = ActionMatcher.of<SearchAction.GroupsLoaded>("GroupsLoaded")
    private val commandFailure = ActionMatcher.of<CommandFailure>("CommandFailure")
    private val retry = ActionMatcher.of<SearchAction.Retry>("Retry")
    private val toggleGuest = ActionMatcher.of<SearchAction.ToggleGuest>("ToggleGuest")

    val chart = StateChartDefinition(
        initial = search,
        states = listOf(
            ParallelState(search),
            CompoundState(queryRegion, initial = idle, parent = search),
            AtomicState(idle, parent = queryRegion),
            AtomicState(debouncing, parent = queryRegion),
            AtomicState(searching, parent = queryRegion),
            AtomicState(results, parent = queryRegion),
            AtomicState(failed, parent = queryRegion),
            CompoundState(groupsRegion, initial = loadingGroups, parent = search),
            AtomicState(loadingGroups, parent = groupsRegion),
            AtomicState(groupsReady, parent = groupsRegion),
        ),
        transitions = listOf(
            Transition(idle, debouncing, queryChanged, guard = actron.statechart.GuardKey("longEnough"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(debouncing, debouncing, queryChanged, guard = actron.statechart.GuardKey("longEnough"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(debouncing, searching, Trigger.After(debounce)),
            Transition(searching, results, contactsFound, effect = actron.statechart.EffectKey("storeContacts")),
            Transition(searching, failed, searchFailed, effect = actron.statechart.EffectKey("storeError")),
            Transition(searching, debouncing, queryChanged, guard = actron.statechart.GuardKey("longEnough"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(searching, searching, groupChanged, effect = actron.statechart.EffectKey("rememberGroup")),
            Transition(results, debouncing, queryChanged, guard = actron.statechart.GuardKey("longEnough"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(results, searching, groupChanged, effect = actron.statechart.EffectKey("rememberGroup")),
            Transition(failed, debouncing, queryChanged, guard = actron.statechart.GuardKey("longEnough"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(failed, searching, retry),
            Transition(failed, searching, groupChanged, effect = actron.statechart.EffectKey("rememberGroup")),
            // Declared on the region, so it applies from every query state; the guarded ones above
            // are inner and take priority when the query is long enough.
            Transition(queryRegion, idle, queryChanged, guard = actron.statechart.GuardKey("tooShort"), effect = actron.statechart.EffectKey("forgetResults")),
            Transition(queryRegion, idle, toggleGuest, effect = actron.statechart.EffectKey("toggleGuest")),
            Transition(loadingGroups, groupsReady, groupsLoaded, effect = actron.statechart.EffectKey("storeGroups")),
            Transition(loadingGroups, groupsReady, commandFailure),
        ),
    )

    /**
     * Builds the machine. The validators are pure functions the app supplies (its
     * `ValidateEmailUseCase` and `ValidateDialerInputUseCase`), so the machine stays pure.
     */
    fun machine(
        looksLikeEmail: (String) -> Boolean = { "@" in it && "." in it.substringAfter("@") },
        looksLikePhone: (String) -> Boolean = { it.length >= 5 && it.all { c -> c.isDigit() || c == '+' } },
    ): Machine<SearchContext, SearchAction, SearchCommand, SearchEvent> =
        Machine(DefinitionId("address-book-picker.search"), DefinitionVersion("1"), chart) {
            guard("longEnough") { snapshot, action -> (action as SearchAction.QueryChanged).query.trim().length >= snapshot.context.minQueryLength }
            guard("tooShort") { snapshot, action -> (action as SearchAction.QueryChanged).query.trim().length < snapshot.context.minQueryLength }

            effect("rememberQuery") { context, action -> context.copy(query = (action as SearchAction.QueryChanged).query, error = null, canAddGuestEmail = false, canAddGuestPhone = false) }
            effect("forgetResults") { context, action ->
                context.copy(query = (action as SearchAction.QueryChanged).query, contacts = emptyList(), isSearchDone = false, error = null, canAddGuestEmail = false, canAddGuestPhone = false)
            }
            effect("rememberGroup") { context, action -> context.copy(groupId = (action as SearchAction.GroupChanged).groupId) }
            effect("storeContacts") { context, action ->
                val found = (action as SearchAction.ContactsFound).contacts
                val query = context.trimmedQuery
                context.copy(
                    contacts = found,
                    isSearchDone = true,
                    error = null,
                    // The app suggests a guest only when nothing was found and the query is an address.
                    canAddGuestEmail = context.allowGuests && found.isEmpty() && looksLikeEmail(query),
                    canAddGuestPhone = context.allowGuests && found.isEmpty() && looksLikePhone(query),
                    // A selected contact found again is refreshed, as the app's putContact does.
                    selectedById = context.selectedById + found.filter { it.id in context.selectedIds }.associateBy { it.id },
                )
            }
            effect("storeError") { context, action -> context.copy(contacts = emptyList(), isSearchDone = true, error = (action as SearchAction.SearchFailed).message) }
            effect("storeGroups") { context, action -> context.copy(groups = listOf(Group(null, "")) + (action as SearchAction.GroupsLoaded).groups) }
            effect("toggleGuest") { context, action -> context.toggleGuest((action as SearchAction.ToggleGuest).guest).copy(query = "", contacts = emptyList(), isSearchDone = false, canAddGuestEmail = false, canAddGuestPhone = false) }

            onEnter(searching) { command(SearchCommand.Search(context.trimmedQuery, context.groupId), searchLane, ConcurrencyPolicy.Latest) }
            onEnter(failed) { event(SearchEvent.ShowError(context.error ?: "search failed")) }
            onEnter(loadingGroups) { command(SearchCommand.LoadGroups) }

            // Context-only updates: no node is left, no search is cancelled, no timer restarts.
            onAction(search, queryChanged) { context = context.copy(query = (action as SearchAction.QueryChanged).query) }
            onAction(search, groupChanged) { context = context.copy(groupId = (action as SearchAction.GroupChanged).groupId) }
            onAction(search, ActionMatcher.of<SearchAction.ToggleSelect>("ToggleSelect")) { context = context.toggleSelect((action as SearchAction.ToggleSelect).contactId) }
            onAction(search, ActionMatcher.of<SearchAction.RoleSelected>("RoleSelected")) {
                val selected = action as SearchAction.RoleSelected
                if (selected.contactId in context.selectedIds) context = context.copy(roles = context.roles + (selected.contactId to selected.role))
            }
            onAction(search, ActionMatcher.of<SearchAction.Back>("Back")) { event(SearchEvent.BackClicked) }
            onAction(search, ActionMatcher.of<SearchAction.Done>("Done")) { event(SearchEvent.DoneClicked(context.result())) }
        }

    private fun SearchContext.toggleSelect(contactId: String): SearchContext {
        val contact = contacts.firstOrNull { it.id == contactId } ?: selectedById[contactId] ?: return this
        if (!contact.isEnabled) return this
        return if (contactId in selectedIds) {
            copy(selectedIds = selectedIds - contactId, selectedById = selectedById - contactId, roles = roles - contactId)
        } else {
            copy(selectedIds = selectedIds + contactId, selectedById = selectedById + (contactId to contact), roles = roles + (contactId to Role.Participant))
        }
    }

    private fun SearchContext.toggleGuest(guest: Guest): SearchContext {
        val normalized = when (guest) {
            is Guest.Email -> guest.copy(value = guest.value.trim())
            is Guest.Phone -> guest.copy(value = guest.value.trim())
        }
        if (normalized.value.isBlank()) return this
        return if (guests.any { it == normalized }) copy(guests = guests - normalized) else copy(guests = guests + normalized)
    }

    private fun SearchContext.result(): PickerResult = PickerResult(
        contacts = selectedIds.mapNotNull { selectedById[it] },
        roles = roles.filterKeys { it in selectedIds },
        guests = guests,
    )

    /**
     * What the screen renders: the counterpart of the app's `AddressBookPickerSearchState`.
     */
    data class SearchUiModel(
        val query: String,
        val groups: List<Group>,
        val selectedGroupId: String?,
        val contacts: List<Contact>,
        val selectedContactIds: Set<String>,
        val selectedRoles: Map<String, Role>,
        val guests: List<Guest>,
        val canAddGuestEmail: Boolean,
        val canAddGuestPhone: Boolean,
        val isLoading: Boolean,
        val isSearchDone: Boolean,
        val errorMessage: String?,
    ) {
        val isDoneEnabled: Boolean = selectedContactIds.isNotEmpty() || guests.isNotEmpty()
    }

    /**
     * The `UiMapper`: a pure projection of the snapshot. `isLoading` is where the chart is, not a
     * flag the handlers have to keep in sync.
     */
    fun MachineSnapshot<SearchContext>.toUiModel(): SearchUiModel = SearchUiModel(
        query = context.query,
        groups = context.groups,
        selectedGroupId = context.groupId,
        contacts = context.contacts,
        selectedContactIds = context.selectedIds.toSet(),
        selectedRoles = context.roles,
        guests = context.guests,
        canAddGuestEmail = context.canAddGuestEmail,
        canAddGuestPhone = context.canAddGuestPhone,
        isLoading = isActive(debouncing) || isActive(searching),
        isSearchDone = context.isSearchDone,
        errorMessage = context.error,
    )
}
