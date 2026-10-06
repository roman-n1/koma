# Durable workflow contract

- Updated: 2026-10-06

`DurableMachine` is an alternative persistence executor around the pure Machine, not a second
writer attached to a running MachineStore. It requires an application storage adapter that
atomically compare-and-swaps one complete snapshot plus outbox and receipt set.

* Initialize and every accepted input persist before any returned intents execute. A storage
  failure/CAS conflict releases no command or effect for execution. The generation is storage
  concurrency, independent of the machine revision. Existing snapshots are validated on load.
* A command classifier returns a stable business `IdempotencyKey` for durable work, or null
  for ordinary activation-scoped work. Durable registrations are moved to the outbox in the
  same commit as the snapshot. Exit cancels ordinary work; durable operations remain pending.
* Retrying a key with unequal command data is rejected. Completed entries retain the original
  command as a receipt, so they are not silently reused. Receipt pruning belongs to the app's
  retry horizon and storage policy, not to automatic garbage collection.
* Before external execution the attempt counter is persisted. The handler receives the same
  key and payload on retry. It must use a transactional local receipt or a remote service's
  idempotency support. This is at-least-once delivery with logical deduplication, not an
  exactly-once guarantee for arbitrary external side effects.
* Returned events are live intents, not a persistent event mailbox. Crash-sensitive effects
  belong in keyed durable commands or in desired domain state that the application reconciles.
* Successful command output is a durable-source External input: it can still update domain
  state after its original activation exited. The machine must model stale business responses
  using its own operation ids/guards. Output, command completion and outbox acknowledgement
  commit atomically. If that commit fails, the operation remains pending and repeats its key.
* Drains are serialized per executor, independently from input commits, so new user inputs
  are accepted while a durable operation awaits IO. Apps own cross-process drain leases if
  they need to suppress concurrent calls in addition to the service's idempotency guarantee.
* Timer ids/deadlines survive in the checkpoint. On recovery `dueTimers(now)` lists overdue
  timers in deterministic order; otherwise the app schedules their stored deadlines. The
  persisted logical time must advance across sessions; apps supply any downtime/wall-clock
  conversion. It must never reset to zero when restoring a pending workflow.
* SnapshotMigration changes semantic model/version/context/work explicitly; target validation
  checks configuration, history, activation ids, commands, timers, counters and invariants.
  Outbox payload/schema migrations remain with the application storage codec and transaction.
