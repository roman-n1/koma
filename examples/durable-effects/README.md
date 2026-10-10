# Durable effects across process death

This runnable JVM example uses the production `actron-statechart` API. It credits a local
balance once for a stable business intent, even if the process dies after the credit and
before the mailbox acknowledgement. It has no Time Travel dependency and is not published.

```bash
./gradlew :durable-effects-example:run
# Run again: the same intent still produces balance=25, with one receipt.
./gradlew :durable-effects-example:run
# Choose a different, existing or newly created local directory:
./gradlew :durable-effects-example:run -PdemoDir=/tmp/actron-credit-demo
./gradlew :durable-effects-example:jvmTest
```

The CLI creates its directory and always uses `order-2026-0001` for a 25-unit credit. Keep
the files to simulate an application restart. The example targets a POSIX local filesystem
with atomic replacement and directory fsync, tested on macOS and Linux. It fails if those
operations are unsupported. Network filesystems and Windows require their own storage adapter.

## What survives

A retained mailbox survives screen recreation while its `MachineStore` lives. It does not
survive process death through `StateSaver`. A new Store has a new mailbox, and its `EffectId`
can be reused. That id is unsuitable as a durable deduplication key.

The application keeps three different pieces of data:

| Data | Owner | Lifetime |
| --- | --- | --- |
| `CreditIntent(id, amount)` in `Outbox.pending` | Application `DiskOutbox` / `StateSaver` | Disk, across processes |
| Retained `ApplyCredit(intent)` delivery | Actron mailbox | This Store only |
| Balance and receipt keyed by `intent.id` | Application `DiskLedger` | Disk, across processes |

The caller supplies a business id, such as an invoice id, before dispatching the request.
Retries must reuse that id and payload. Generating a new id on restart would create a second
business operation. The minimal outbox holds one pending intent; it rejects a different request
while busy. It remembers the last completed intent. Older retries remain safe because the ledger
retains their receipts; receipt retention must cover the application's retry horizon.

```text
Ready -- Request(intent) / persist pending, emit ApplyCredit --> Ready
Ready -- Applied(intent) / persist completion, clear pending --> Ready
startup / emit ApplyCredit when restored context has pending
```

## The handler's order

See [DurableCredit.kt](src/jvmMain/kotlin/actron/example/durable/DurableCredit.kt). A screen owns
one mailbox subscription and calls `session.handle(delivery)`; cancelling that subscription
leaves the Store and its pending deliveries alive for the next screen. Each handler runs as a
child of the session owner; cancelling the screen cancels and joins that handler. A blocking
ledger write can finish despite cancellation, so teardown waits for it before releasing ownership.

1. Check that the business intent was actually persisted. Actron publishes its committed state
   before calling `StateSaver.save`, and reports a saver failure without rolling back the
   transition. `DiskOutbox.durable` changes only after the disk commit succeeds.
2. Apply the credit using the business id. `DiskLedger` writes the new balance **and** its
   receipt in one atomic snapshot. A matching receipt turns redelivery into a no-op; reusing
   an id for another amount is rejected.
3. Register a one-shot persistence barrier, dispatch `Applied(intent)`, and wait for a
   successful `DiskOutbox.save` containing that completion. A later request can set `pending`
   again, and a later completion can replace the latest `completed`; neither erases the
   already-signalled barrier. Observing `store.currentState` alone would not establish durability.
4. Acknowledge the mailbox delivery.

If a screen left after step 3, a new subscriber verifies the receipt and acknowledges the
existing delivery. It need not run the credit again. The CLI closes the Store and waits for
its owned Store and active handler jobs before releasing the OS writer lock. A second session for the same directory
fails rather than racing these files. Call owner teardown outside the Store's own job and
the session's handler jobs, including handler boundary callbacks, since teardown joins them;
mobile applications should use suspending teardown on an appropriate dispatcher instead
of blocking the UI thread. Disk I/O here is intentionally synchronous inside `StateSaver`.

The files use an application schema, independent of Actron's recording formats. Writes use a
temporary file, file fsync, atomic replace, then parent-directory fsync. Corrupt or unknown
schemas fail closed; the application must migrate or recover them without silently dropping
pending business work or receipts. Disk-full and I/O failures propagate. The handler leaves
the delivery unacknowledged if a durable completion cannot be established. Retrying a failed
domain save requires application recovery/restart; this example does not implement a retry UI.
The process-death tests prove recovery from completed writes; they do not simulate power loss
or certify every filesystem's crash behaviour.

## What an acknowledgement can guarantee

| Process dies… | Saved domain | Saved ledger | Fresh Store behaviour |
| --- | --- | --- | --- |
| Before the external effect | Pending intent | No receipt | Startup emits it and applies the credit |
| After the credit, before domain acknowledgement | Pending intent | Balance + receipt | Startup emits it; receipt prevents a second credit |
| After durable domain acknowledgement, before mailbox acknowledgement | Completed intent | Balance + receipt | Startup emits nothing |
| After mailbox acknowledgement | Completed intent | Balance + receipt | Startup emits nothing |

The balance and receipt share a transaction in this example. Writing a separate receipt
before an unrelated side effect could lose the side effect; writing it afterwards could
repeat the side effect. For a remote payment or API call, the remote service must honour the
same business id as an idempotency key and durably commit its result with that key. A local
receipt alone cannot make an arbitrary remote effect execute exactly once. For navigation,
persist the desired destination and reconcile it with the UI rather than promising a single
imperative navigation call across process death.

Neither `StateSaver`, mailbox acknowledgements nor Time Travel provide a distributed
transaction. If the process dies before the initial intent reaches disk, the original caller
must retry its request with the same business id. Domain completion and ledger persistence
are separate transactions whose ordering makes each intermediate state recoverable.

## Regression evidence

[DurableEffectProcessTest](src/jvmTest/kotlin/actron/example/durable/DurableEffectProcessTest.kt)
creates real `MachineStore` instances and mailbox subscribers. Four tests stop a child JVM
with `Runtime.halt` at the boundaries above, skipping `close` and `finally`. Two more fresh
JVMs then use only the saved files and verify a single credit. Additional cases cover repeated
screen reentry, reentry after a durable domain acknowledgement, real filesystem write failures
before the effect and during domain acknowledgement, payload mismatch, corrupted and incompatible
schemas, exclusive ownership while a cancelled handler is still blocked in the ledger commit,
and a durable completion followed by a newer pending/completed intent before its waiter resumes. These tests run in the existing JVM CI job.
