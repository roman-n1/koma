# Messenger readiness checklist

- Updated: 2026-09-29
- Follows: [stability review](2026-09-29-stability-review.md) (eight rounds, PRs #17–#25)

## Verdict

- `koma-core`, `koma-test`, `koma-message`, `koma-logging`: production-ready for screen and
  session state machines. Eight review rounds with independent reviewers, soak tests on
  `Dispatchers.Default` and on every target, CI on JVM, Android host, iOS Simulator, JS and Wasm.
- `koma-statechart`: usable, but `@ExperimentalKomaApi`; rounds 6–8 still found substantive
  defects (history restore, lost failure reports). Expect a few more findings in the first weeks
  of real use and keep the regression-test habit.
- `koma-compose`: works; the documented traps are `eventEffect` inside `stateContent` (misses
  that state's `enter {}` events) and `rememberStateSaver` in repeated content without `key()`.

## Before the first release

1. **Set `exceptionHandler` in every Store.** The default is `Rethrow`: a handler report becomes an
   uncaught exception, which crashes an Android process.
2. **Do not use Koma as the message transport.** Delivery, ordering and retries live in the
   database and an outbox; Koma drives the screens and the session.
3. **Decide the activity-dispatch semantics** (stability review, open questions): an action an
   activity dispatched is tied to its activation, so a heartbeat self-loop timer on the same node
   discards it. Either keep heartbeats in their own region (current rule) or change the gate to
   "node still active".
4. **Publish a first pre-release** (`publish.yml`, coordinates `io.github.roman-n1:*:5.0.0-alpha.1`)
   before the messenger depends on artifacts; until then use the composite build.
5. **Fix the duplicate send in the messenger example before copying it.** `MessengerChart`
   cancels the pending `send` when Settings opens; the request may already be on the wire. On
   return, deep history restores `Sending`, whose `onEnter` sends `context.outgoing` again, so
   the recipient gets the message twice (`services.sent == [hi, hi]` in a scratch probe).
   Correct designs, in order of preference:
   - give every outgoing message a client id and make `send` idempotent on the server (the
     outbox row's id); then re-sending on re-entry is harmless and also covers process death;
   - or move `Sending`/`RetryWait`/`Failed` into a parallel region beside the UI region so that
     Settings does not exit them (a UI screen must not cancel a network write);
   - not: "do not restore `Sending` from history", which loses the message instead.
6. **Model must-not-lose signals as state**, never as events: Store events are unbuffered and
   a collector that is not there yet misses them.

## Known gaps to watch

- CI runs browser JS/Wasm tests only, never `jsNodeTest`/`wasmJsNodeTest`, never compiles
  `iosArm64` (first built by `publish.yml`), and has no binary-compatibility check.
- Collectors and `currentState` see a new state before `StateSaver.save` runs; a process death
  in that window persists the previous state after the UI reacted to the new one.
- Tracked lanes (`CancelPrevious`, `DropIfRunning`) cost one coroutine and one lock acquisition
  per launch completion (about 73k launches/s on the JVM against 173k untracked): fine for UI,
  measure before using them per keystroke on low-end devices.
