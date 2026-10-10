# actron-diagnostics

Common KMP diagnostics over actual machine decisions. CausalTraceHub correlates inputs,
committed transitions, commands, timers and cross-Store bridge effects with bounded metadata.
Attach its observer before Store startup and share the hub/unique StoreInstanceIds across a group.
Unknown/evicted provenance is explicitly incomplete; payloads and original exceptions are not retained.

```kotlin
val hub = CausalTraceHub(capacity = 256, sink = applicationDiagnosticSink)
val observer = hub.observer(machine, runtimeStoreId)
val store = MachineStore(machine, context, handler, appScope, observers = listOf(observer))
```

For a durable workflow, call recordPersisted only after a successful storage commit and supply
the application's input id and any real outbox command provenance. It records committed metadata
with selection=null, without re-evaluating guards. Full replay/audit data uses application codecs
and the optional Time Travel module.

SDK bridges are separate optional artifacts. See the
[contract/design guide](../doc/internal/design/2026-10-06-competitive-roadmap.md).
