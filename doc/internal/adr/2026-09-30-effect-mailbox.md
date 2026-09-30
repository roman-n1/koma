# Effects that must reach the UI wait in a retained mailbox with an id and an explicit delivery policy

- Updated: 2026-09-30

## Background

The [handoff](../design/2026-09-29-time-travel-logging-handoff.md) §10: UI effects that must
wait for a subscriber live in a retained mailbox with an `EffectId` and an explicit delivery
and acknowledgement policy; `Store.event` with `replay = 0` is not a mailbox; an acknowledgement
does not prove an exactly-once external effect across a crash, so each category fixes its
retry or drop behaviour; dialogs and persistent UI stay in the state, a toast is its own
effect. §6 lists the journal entries `EffectQueued`, `EffectHandlingStarted`,
`EffectAcknowledged`, `EffectDiscarded`; §8 puts the unhandled effects and their delivery state
into the checkpoint. Until now every effect of a `MachineStore` went to `Store.event` and was
lost when no one collected, which is what a recreated screen finds after a navigation effect
fired while it was gone.

## Decision

The mailbox is part of `MachineStore` (`koma-statechart`), fed by the scheduler actor,
journaled by a listener, and in the checkpoint.

- **The policy is per effect and explicit.** `MailboxConfig.policy` maps each effect to an
  `EffectPolicy`: `Transient` (delivered to whoever collects `Store.event` now, or lost; a
  toast), `Retained` (kept until a subscriber acknowledges it; a navigation), `Latest(key)`
  (kept like `Retained`, but only the newest of its key waits; a badge). The default is
  `Transient` for everything, which is what `Store.event` always was, so nothing changes for a
  Store that does not opt in. A policy that throws is reported and the effect is transient.
- **Retained effects wait with the Store.** `MachineStore.mailbox` lives with the Store, which
  is the retained instance; a subscriber that collects `subscribe()` after the UI was recreated
  gets what the previous one never acknowledged. Each pending effect goes to one subscriber
  at a time, oldest first; when that subscriber's collection is cancelled, the effects it was
  handling and had not acknowledged wait for the next one, with their attempt count going up.
  `Delivery.acknowledge()` removes the effect and returns `false` the second time. The mailbox
  is bounded (`maxRetained`): the oldest waiting effect makes room, and when every held effect
  is being handled the new one is dropped. Closing the Store discards what is pending and ends
  the subscribers. A `Latest` effect supersedes the ones of its key that still wait, not the
  one a subscriber is handling.
- **The scheduler feeds the mailbox.** Effects of a decision are queued by the actor when it
  carries the decision out, in decision order, before the decision's commands start; the
  transient ones are pumped to `Store.event` from there. So the mailbox's state at
  `checkpoint()` is the state after the last decision the executor carried out, and
  `ExecutorCheckpoint.effects` holds the pending effects with their attempts and whether one is
  being handled. A group cut gets them for every member.
- **The journal tells the story.** `session.effectsOf(store, describe)` is an `EffectListener`
  that publishes `EffectQueued` (with the policy's name and what the describer kept),
  `EffectHandlingStarted` with the attempt, `EffectAcknowledged` and `EffectDiscarded` with the
  reason (`Superseded`, `Overflow`, `StoreClosed`). `JOURNAL_FORMAT_VERSION` is 3; the file
  format writes them under new tags; the inspector shows them as `Effect` items.
- **Replay and branches never deliver.** A recording's decisions carry the effects; a replay
  compares them and a branch accumulates them (§9.1: the content is visible, the UI command is
  not executed). `RecordingCodec` format 4 writes the checkpoint's pending effects.

Not adopted:

- Acknowledging on return from the collector's body: a body that only starts a navigation
  would acknowledge before the navigation happened; the explicit call is the policy §10 asks for.
- A mailbox per subscriber (every subscriber gets every effect): a navigation shown twice is
  the double delivery the mailbox exists to prevent; the effects are shared, each to one
  subscriber at a time.
- Keeping the effects in the snapshot: they are not the machine's business data, and a replay
  must not depend on what the UI acknowledged.

## Notes

- Tests: [`EffectMailboxTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/machine/EffectMailboxTest.kt)
  (transient effects on the event flow only; a retained effect waiting, delivered, acknowledged
  once, journaled; a subscriber going away handing its effect to the next with the attempt
  counting up; `Latest` superseding while waiting; the bound; closing; the checkpoint; a
  throwing policy) and
  [`EffectMailboxStormTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/machine/EffectMailboxStormTest.kt)
  (four hundred retained effects from four threads while three subscribers live a few
  milliseconds each, acknowledging some and dying with the rest in hand: every effect is
  acknowledged exactly once, none is lost or discarded, the journal has one queue and one
  acknowledgement per effect with attempts counting up, the mailbox ends empty).
- Left for later: a retry budget per policy (after N attempts, discard and journal it), the
  messenger's categories of effects and their describers, and delivering a `Latest` effect's
  supersession to a subscriber that is handling the older one.

## Related

- [MachineStore commit protocol](./2026-09-30-machine-store-commit-protocol.md)
- [Group replay](./2026-09-30-group-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §6, §8, §10
