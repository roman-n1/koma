# koma-observability

A structured journal for [Koma](../README.md) Stores: what each Store accepted, dropped,
processed, committed, emitted and failed, as records with stable identity and order, kept in a
bounded in-memory buffer and handed to sinks by one writer. It is the diagnostic layer the
[Time Travel and structured logging handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md)
builds the inspector and the replay on; this module is the journal only.

- **Source.** The `StoreProbe` of `koma-core`: every input (startup, dispatch, transaction,
  recovery of a launch failure), each processing with its outcome, each commit with its revision,
  events and reported failures, correlated to their input. A `Plugin` cannot see most of this.
- **Identity and order.** `RuntimeSessionId` (a run), `MachineGroupId` (Stores ordered together),
  `StoreInstanceId` (one instance, never a class or chat id), `GroupSeq` (dense, the order) and
  `StoreSeq` (dense per Store). Sequence numbers are assigned under one lock together with
  retention, so the order is the publication order whatever thread published.
- **Payload policy before retention.** A `PayloadPolicy` decides on the Store's thread what the
  journal keeps of a state, action, event or failure. The production default keeps metadata only;
  a failure becomes a `FailureDescriptor` (class names, causality, no message). Nothing is
  sanitized later in a sink or a UI: what the policy did not keep does not exist.
- **Session.** `RecordingSession` keeps the last N records for inspection, queues every record for
  the sinks and writes them in order from one coroutine. Loss is explicit: an overflow of the
  writer's queue is counted and the next record that fits is preceded by a `JournalGap`.
- **Sinks.** `JournalSink` receives records in order. `koma-logging` ships `LoggerJournalSink`,
  which writes `JournalFormat.line` to a `Logger`.

Status: **experimental.** Every declaration is `@ExperimentalKomaApi`, and the module lives in the
fork [roman-n1/koma](https://github.com/roman-n1/koma). The record model has `JOURNAL_FORMAT_VERSION`
1 and will grow with commands, timers, effects and checkpoints; there is no file format, export or
replay yet, and every recording is `Capability.InspectOnly`.

## Dependency

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:koma-observability:4.0.0-sc.1")
            implementation("io.github.roman-n1:koma-logging:4.0.0-sc.1") // LoggerJournalSink
        }
    }
    compilerOptions {
        optIn.add("koma.core.ExperimentalKomaApi")
    }
}
```

The module brings the fork's `koma-core` with it; see the [statechart README](../koma-statechart/README.md)
for the composite build setup until a release is published.

## Quick start

One session per process run, one group per screen, one `StoreInstanceId` per Store instance.

```kotlin
val session = RecordingSession(
    scope = appScope, // owns the writer coroutine; outlives every Store
    group = MachineGroupId("chat-screen"),
    config = JournalConfig(
        retainedRecords = 2_000,
        writerQueueCapacity = 4_096,
        onFailure = { failure -> crashReporter.breadcrumb(failure.toString()) },
    ),
    sinks = listOf(LoggerJournalSink(tag = "Journal")),
)

val chatPolicy = PayloadPolicy<ChatState, ChatAction, ChatEvent>(
    state = { state -> Payload.Projected(state.kindLabel(), mapOf("messages" to state.messageCount.toString())) },
    action = { action -> Payload.Projected(action.kindLabel()) },
    // events and failures keep the metadata-only defaults
)

val store = Store<ChatState, ChatAction, ChatEvent>(ChatState.Loading) {
    recordTo(session, StoreInstanceId("chat-$tabInstanceId"), chatPolicy)
    // ...
}
```

`session.records()` returns the retained records, oldest first; `session.stats` the counters
(published, retained, evicted, dropped for sinks, sink and policy failures). `session.close()`
publishes `RecordingStopped`, drains the queue and stops the writer.

What one dispatch looks like in the log, with the production policy:

```
[3f2a… chat-screen chat-7 #12/9 +1.204s] InputAccepted #4 Dispatch -
[3f2a… chat-screen chat-7 #13/10 +1.204s] ProcessingStarted #4 ordinal=4
[3f2a… chat-screen chat-7 #14/11 +1.205s] StateCommitted #4 revision=3 - <- -
[3f2a… chat-screen chat-7 #15/12 +1.205s] ProcessingFinished #4 ordinal=4 Handled commits=1 in 812us
```

`-` is an omitted payload. With `PayloadPolicy.retainAll()` (tests and local debugging only) the
objects print their `toString()`.

## Rules

- A policy runs under the Store lock for every trace: keep it cheap, never let it throw. If it
  does, the payload is `Payload.Unavailable`, the error goes to `JournalConfig.onFailure`, and the
  record is still published.
- Sinks run on the session's writer, never on a Store's thread. A sink that throws is counted and
  reported; nothing is written about it, so a broken sink cannot feed on its own reports. A slow
  sink delays the sinks after it and the queue, not a Store.
- Two tabs of the same chat are two `StoreInstanceId`s. Registering one id twice fails.
- Budgets in `JournalConfig` are placeholders until they are measured on the target devices.
