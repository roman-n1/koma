# Address-book picker pilot: what is in koma, what the messenger needs

- Updated: 2026-09-30

## Background

Stage 3 of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md) is a pilot on one
messenger scenario: the address-book picker's search. The koma side is done as a reference
implementation in the statechart tests; the application side is not started, because it depends
on decisions only the messenger can take. This note records both.

## What the pilot shows (koma side)

[`AddressBookSearchMachine`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/example/picker/AddressBookSearchMachine.kt)
mirrors `AddressBookPickerSearchComponentImpl` of `su.ivcs.messenger`:

| Messenger today | Pilot |
|---|---|
| `MutableStateFlow<AddressBookPickerSearchState>` in `InstanceKeeper`, mutated from many places | `MachineSnapshot<SearchContext>`; the machine is the only writer, every change is a decision |
| `searchQueryFlow.debounce(350)` + `submitSearch` in a retained scope | `Debouncing --after 350ms--> Searching`; `onEnter(searching)` registers `Search(query, group)` in lane `search` with `Latest` |
| A late answer of an old query overwrites the state | The old command is cancelled with its activation; a late `ContactsFound` for it is `Ignored(StaleCommand)` |
| `isLoading` flag kept by hand | `isLoading = isActive(Debouncing) || isActive(Searching)` in the `UiMapper` |
| `AddressBookPickerSelectionStore` shared through the root's `InstanceKeeper` | Selection lives in the context and changes through action handlers, without leaving `Results` |
| `endpoint.news.emit(...)` from the component | `SearchEvent.BackClicked` / `DoneClicked(result)` as effects of a decision, delivered after the commit |
| Guest suggestions from `ValidateEmailUseCase` / `ValidateDialerInputUseCase` | Pure predicates injected into the machine; applied in the `storeContacts` effect |
| Groups loaded in `init` with `getOrElse(emptyList())` | `LoadingGroups` region; `CommandFailure` leads to `GroupsReady` with the "all contacts" entry only |

[`AddressBookSearchPilotTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/example/picker/AddressBookSearchPilotTest.kt)
runs the scenarios of the handoff §13 on a `MachineStore` with a test clock and a fake
repository: fast typing searches once with the last query; a slow answer to an old query never
reaches the screen; closing during a request cancels it and commits nothing; selection updates
the context without leaving `Results` or touching the search; a group change re-searches at
once but only remembers the group while idle; a short query clears the results and an address
suggests a guest; a failed search shows an error and `Retry` searches again; groups load on
start; two tabs of the same screen are independent and share one journal in which, under the
production payload policy, no query or contact text appears.

Two things the pilot needed from the machine and now exist: action handlers (`onAction`) for
context-only updates, and the executor's `CommandAbandoned` for superseded commands.

## What the messenger needs (application side)

Not started. Each item is a decision or a piece of wiring in `su.ivcs.messenger`:

1. **Dependency.** The messenger does not include koma. Until the fork publishes a release, the
   options are a composite build (`includeBuild` of a koma checkout or a git submodule) or
   publishing `4.0.0-sc.x` to Maven Central from a pre-release. The modules needed are
   `koma-core`, `koma-statechart`, `koma-observability` and, for the log sink, `koma-logging`;
   all have Android targets.
2. **Where the machine lives.** The chart, the machine and the `UiMapper` are pure Kotlin and
   fit `feature:address-book-picker:impl`; the commands are executed by a `CommandHandler` that
   calls `SearchContactsUseCase` and `GetContactGroupsUseCase`, so the handler lives next to the
   component and stays inside the feature's allowed dependencies (`core:domain` only).
3. **Retained owner.** `MachineStore` belongs to the Decompose component's `InstanceKeeper`
   (`instanceKeeper.getOrCreate { ... }` with `close()` in `onDestroy`), as
   `ComponentRetainedInstance` does today; two tabs of the same chat are two instances with two
   `StoreInstanceId`s. The execution scope is the retained instance's scope, never
   `Dispatchers.Unconfined`.
4. **Bridge.** `store.event` carries `BackClicked` / `DoneClicked(result)`; the component forwards
   them to `endpoint.news`, so `AddressBookPickerRootComponentImpl` and the `ParentBridge`
   contract are untouched.
5. **Selection across Main and Search.** Today both screens mutate one `SelectionStore`. With
   machines, the selection is the context of a root-level machine (or the root's own store)
   and Main/Search receive it as input; the second half of the pilot (root/Main split, the
   handoff §13 stage 3) decides this. The Search pilot keeps the selection in its own context
   for now.
6. **Journal.** One `RecordingSession` per picker session in the root component, `recordTo` per
   child store with a projecting `PayloadPolicy` (state kind and counts, never names or
   queries), `LoggerJournalSink` in debug builds only.
7. **Paging.** `AddressBookPickerMainComponent` exposes `PagingData`; the handoff §10 keeps
   Paging3 outside the machine and records only generations and load states. Not part of the
   Search pilot.

## Open questions

- Composite build or published artifact for the messenger? (blocks 1)
- Does the picker's root become a machine now (selection as its context, Main and Search as
  children), or does the Search pilot ship first with its own selection and the root stays as
  it is?
- Which `PayloadPolicy` fields are acceptable in production logs for the picker: state kinds
  and result counts are safe; group ids are probably not.
