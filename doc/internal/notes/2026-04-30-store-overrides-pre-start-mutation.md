# Proposal for pre-start mutation of Store overrides

- Updated: 2026-04-30

## Background

Currently the `Store(...)` factory has 4 overloads that include an `overrides` argument.

```kt
fun Store(overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(initialState: S, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(coroutineContext: CoroutineContext, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(initialState: S, coroutineContext: CoroutineContext, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
```

Here, the purpose of `overrides` is one of the following.

- Overriding environment settings in tests without rewriting the shared *Store* configuration
- Consolidating project-wide defaults via an AppStore wrapper while allowing them to be swapped in debug builds or tests

By design principle, `overrides` may only touch environment settings, and must not touch the identity of state/action handlers ([design-principles.md:21,30](../design/2026-04-23-design-principles.md:21)).

On the other hand, the following 3 points are friction in the API.

- `overrides` is not an argument typical users touch, yet it permanently sits in the middle of the signature of all 4 overloads
- The AppStore wrapper side also has to grow an `overrides: Overrides<...> = {}` argument, which has become boilerplate
- Production code that writes `Store {...}` inline has no way to be overridden from the test side (it always has to be turned into a function)

## Current thinking

The environment setting fields of `StoreImpl` (`coroutineContext` / `stateSaver` / `exceptionHandler` / `middlewares` / `pendingActionPolicy` / `middlewareExecutionPolicy`) are all held as `protected abstract val`, and `coroutineScope` and `_state` are `by lazy` and read these only on first reference.
In other words, the state where "swapping values is safely effective until the Store starts" already holds.

Building on this, add to `StoreInternalApi` an **API for rewriting environment settings that is allowed only before start**, in the same style as `attachObserver()`.

```kt
@InternalActronApi
interface StoreInternalApi<S,A,E> {
    suspend fun dispatchAndWait(action: A)
    fun attachObserver(observer: StoreObserver<S,E>, notifyCurrentState: Boolean = true)
    fun applyOverrides(block: Overrides<S,A,E>)        // added
}
```

The public extension is placed on the `actron-core` side.

```kt
@OptIn(InternalActronApi::class)
fun <S,A,E> Store<S,A,E>.applyOverrides(block: Overrides<S,A,E>): Store<S,A,E> {
    requireStoreInternalApi().applyOverrides(block)
    return this
}
```

On the implementation side, it is enough to change the 6 fields in question in `StoreImpl` to `internal var`. `initialState` and `onEnter` / `onAction` / `onExit` / `onError` are the identity of the state machine, so they stay as `val`, protecting the design principle at the type level.

The advantages of this direction are as follows.

- The `Overrides<>` argument disappears completely from user code
  - The `overrides` argument can be removed from the `Store(...)` factory, and the 4 overloads can be consolidated into 1
  - The `overrides` argument can also be removed from the AppStore wrapper side
- Inline production Stores can also be overridden after the fact from the test side
  - Unlike the approach of rebuilding an already built Store, there is no retention of the setup closure, no idempotence contract, no new conceptual model
- It aligns completely with the existing "before start" gating pattern of `attachObserver`
- The implementation increment is minimal (1 method on `StoreInternalApi`, `val → var` in `StoreImpl`, 1 overload of `StoreOverridesBuilder.applyTo(StoreImpl)`, 1 public extension)

Expected user code:

```kt
// Ordinary Store definition (inline is fine)
val store = Store {
    initialState(CounterState(count = 0))
    middleware(AppLoggingMiddleware())
    state<CounterState> { ... }
}

// AppStore wrapper (the overrides argument disappears)
fun <S,A,E> AppStore(
    initialState: S,
    setup: Setup<S,A,E>,
): Store<S,A,E> = Store {
    initialState(initialState)
    middleware(AppLoggingMiddleware())
    exceptionHandler(AppExceptionHandler)
    setup()
}

// Test
val testStore = CounterStore().applyOverrides {
    clearMiddlewares()
    exceptionHandler(ExceptionHandler.Log)
}

// Switch for debug builds
val store = createMyStore().also {
    if (BuildConfig.DEBUG) it.applyOverrides { middleware(DebugMiddleware()) }
}
```

## Notes

- The start check is expected to use the same criteria as the existing `attachObserver`: the `lazy` initialization of `coroutineScope` / `_state` or the `initializeIfNeeded()` call. No new mechanism is needed.
- If `applyOverrides` is called after start, fail with an `IllegalStateException` rather than applying implicitly or silently ignoring. Align with the behavior of `attachObserver`.
- The public extension is expected to be placed in `actron-core`. There is real demand for production use such as debug build / staging switching, so there is little necessity to confine it to `actron-test` as test-only. It is also consistent with the `Overrides<>` typealias and `StoreOverridesBuilder` being in `actron-core`.
- The extension name `applyOverrides` is a placeholder. `withOverrides` strongly connotes an immutable copy, which does not fit the meaning of mutate-in-place. `overrides` collides with the existing typealias, but is short and readable. The final decision is made separately.
- Even if the `overrides` argument is removed from the AppStore wrapper side, swapping from the test side remains possible in the form `AppStore(...) { ... }.applyOverrides { ... }`, so the intent of the current usage is not lost.

## Open questions

- The extension name (one of `applyOverrides` / `withOverrides` / `overrides`) is undecided.
- Consolidating the `Store(...)` factory overloads is a separate topic, but proceeding with it at the same time as adopting this proposal lets callers pay the migration cost only once.
- The specifics of the scope of the `val → var` change on the `StoreImpl` side are not fixed. Those read only once via `by lazy`, such as `coroutineContext`, can be made `var` as-is, but those referenced from multiple places, such as `middlewares`, need a concurrency check (although before start it is basically fine to assume access from a single thread).
- Whether the return value of `applyOverrides` should be `Store<S,A,E>` (returning self) or `Unit` is undecided. A trade-off between chaining convenience and visibility of the side effect.

## Related

- [#182](https://github.com/koma-kt/koma/pull/182)
