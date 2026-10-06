# Machine admission, cuts and shutdown

Status: accepted for the unreleased 5.0 alpha.

## Contract

`MachineStore.admit` and `feed` return `Accepted` when an open store books an input,
`Rejected(pending, limit)` when its bounded queue is full, and `Closed` when closure
won the admission race. Closed is terminal for that store. Admission is not a durable
acknowledgement: closure after acceptance can discard work before it is decided.

The application clock is read before reserving queue capacity and outside locks. A
throwing clock cannot consume a reservation. Booking, the cut boundary and closure
are serialized by the controlled-queue gate. Processing starts or a pre-processing
discard releases capacity exactly once. Closure clears reservations and both queues.

`dispatchAndAwait` awaits its own input's terminal processing/discard trace, including
the machine's decision observers, rather than waiting for all later traffic. A closed
store or capacity rejection throws before admission. An accepted input subsequently
discarded by close completes normally, matching core Store cancellation semantics.
Closing a frozen store releases held awaiters without requiring a subsequent thaw.
An observer already running still has to return before its input's waiter completes.
The core's same-store await guard is preserved for plugins and handlers.

## Handoff and cuts

The pre-cut `entering` queue is distinct from the post-cut `held` queue. One elected
caller hands `entering` inputs to the core in order, outside the controlled gate.
Immediate dispatchers and observers that dispatch or close cannot reenter a locked
gate. Reentrant offers append to the queue instead of recursively handing off work.
Thaw transfers the held queue ahead of new arrivals under the gate, then drains it
outside the gate. The admission rejection callback also runs outside the gate.

Freeze need not block an in-progress handoff. `awaitIdle` additionally waits for every
pre-cut handoff, so a group checkpoint cannot omit an offer that was accepted before
freeze but had not reached the core. Held inputs remain outside the idle boundary.
Idle-change notifications and per-input completions run outside locks, because an
immediate continuation may call into the store again.

## Compatibility and verification

`Admission.Closed` deliberately adds a sealed outcome to the unreleased 5.0 alpha;
earlier alpha consumers must update exhaustive `when` expressions and recompile.
The machine interface signatures, core API and all five frozen disk formats are
unchanged. Both JVM and KLIB API dumps include the new outcome.

`AdmissionShutdownJvmTest` uses JVM threads and latch barriers, without timing sleeps,
for timestamp/close races, simultaneous bounded offers, processing observers and an
inline-dispatcher handoff during a cut. It also covers clock failure, startup discard,
individual await isolation, cut shutdown and same-store await rejection. The initial
three shutdown regressions failed against the original implementation and passed
after the fix; the existing chart, helper and Time Travel suites remain required.
