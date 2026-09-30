# A machine's Compose helpers live in `koma-statechart-compose`; the first is `MailboxEffect`

- Updated: 2026-10-01

## Background

koma-kt/koma#189 (item 3) lists Compose ergonomics: derived state, binding helpers, one-shot
events. The fork's `MachineStore` has a retained mailbox for the effects a screen must not lose
(handoff §10, [ADR](./2026-09-30-effect-mailbox.md)): a subscriber of `mailbox.subscribe()`
takes one `Delivery` at a time and acknowledges it, and a subscriber that goes away hands what
it held to the next one. A screen wrote that subscription by hand in a `LaunchedEffect`, and
the decision of the roadmap plan (2026-10-01) was to keep `koma-compose` core-only, as upstream
keeps it, and put the machine's Compose concerns in a module of their own.

## Decision

A new module `koma-statechart-compose` (`api(koma-compose)`, `api(koma-statechart)`,
`api(compose.runtime)`; the targets of `koma-timetravel-compose`, web browser-only because of
Skiko; not a debug module, so `checkDebugGraph` does not watch it). Its first declaration:

```kotlin
@Composable
fun <E : Event> MailboxEffect(mailbox: EffectMailbox<E>, block: suspend (Delivery<E>) -> Unit)
```

`rememberUpdatedState(block)` and `LaunchedEffect(mailbox) { mailbox.subscribe().collect { block(it) } }`.
Nothing else: the at-least-once delivery, the release on cancellation with the attempt counted,
the budget, the order and the bound are the mailbox's (Information Expert); the composable ties
one subscription to the composition's lifetime, as `eventEffect` does for `Store.event`. A
throwing block fails the composition, as any `LaunchedEffect` does, and the effect returns to
the mailbox for the next subscriber, which is what `EffectPolicy.Retained(maxAttempts)` is for.

The parameter is the `EffectMailbox`, not the `MachineStore` or the `ViewStore`: the composable
depends on the port it uses (Low Coupling), and a test or a fake can hand it one. Top-level,
PascalCase, like `LaunchedEffect`: the naming ADR of `ViewStore` (`2026-04-30`) argued for
lower camel case on *member* composables of a DSL receiver; a top-level effect with the mailbox
as its argument has no such receiver, and `E` is inferred from the mailbox.

Not adopted:

- A filter by type (`MailboxEffect<Ev.Navigate>(mailbox) { }`): the mailbox hands each effect
  to one subscriber, whichever asks first, and a subscriber that filtered after taking would
  hold or lose what it does not handle. One `MailboxEffect` per mailbox at a time, with a
  `when`.
- Acknowledging for the block (acknowledge when the block returns): a block that navigates
  and then suspends on a dialog would acknowledge too late or too early; the UI says when it
  took responsibility.
- A `ViewStore`-level member (`viewStore.mailboxEffect { }`): `ViewStore` is `koma-compose`'s
  and knows a `Store`, not a `MachineStore`; a member would couple core to the machine.
- Two-way binding helpers and a `rememberUiModel`: `viewStore.select { it.toUiModel() }`
  ([ADR](./2026-10-01-viewstore-select.md)) is the UI model, and a binding hides the action.

## Notes

- Tests: `MailboxEffectTest` (common, every target): a delivery reaches the block and leaves
  on acknowledgement; what was queued before the composition entered is delivered on entering;
  a composition leaving mid-handling hands the effect to the next one as attempt 2; the budget
  ends the redeliveries; the latest block is used; a throwing block fails the composition and
  releases the effect with its attempt counted; a storm of 200 recreations while 400 effects
  flow, the block waiting at a gate the test opens so what is mid-handling at each recreation
  is known, acknowledges each exactly once, the 200 interrupted ones on their second attempt.
  `MailboxEffectRaceJvmTest`: the storm with the store on `Dispatchers.Default`.
- The test composition is a `Recomposer` on the test dispatcher with a `BroadcastFrameClock`
  and an applier that draws nothing (`ComposeHarness`), as `koma-compose`'s JVM tests do; it is
  common code, so the tests run in the browser and on iOS too.
- Upstream: nothing; the module is additive and depends on the fork's machine (roadmap rules).

## Related

- [The effect mailbox](./2026-09-30-effect-mailbox.md)
- [`ViewStore.select`](./2026-10-01-viewstore-select.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §10
