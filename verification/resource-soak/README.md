# Device resource budgets

An unpublished KMP verification harness for resource growth, queues and commit latency across
many `MachineStore` and `RecordingSession` lifetimes. It uses the production Store, mailbox,
timer/command executor, journal writer and file sink. It does not add library API or Time Travel
dependencies.

Every PR runs **300 seconds on both an Android 35 emulator and an iOS simulator**, through
[Device resource budgets](../../.github/workflows/device-resource-budgets.yml). These are required
test jobs in the workflow, not an optional or disabled nightly run. Ordinary JVM/native tests
use a short 3-second smoke workload. A manual workflow run can request 3..3600 seconds per
platform; the maximum is one hour. Requested seconds are minimum elapsed soak time, including
repeated teardown and memory sampling; build/setup and the final bounded cleanup can add time.
Long-run reports are the useful stability evidence; a short smoke checks the harness.

```bash
# Short local harness checks:
./gradlew :resource-soak:jvmTest
./gradlew :resource-soak:iosSimulatorArm64Test
# Five minutes on the local iOS simulator:
./gradlew :resource-soak:iosSimulatorArm64Test -PsoakSeconds=300
# A connected physical Android device or running emulator:
bash verification/resource-soak/run-android.sh 300
# Compile the same workload for a physical iOS target:
./gradlew :resource-soak:compileKotlinIosArm64 -PsoakSeconds=300
```

The Android script works with an authorized USB device (`adb devices`). Select
one device with `ANDROID_SERIAL` if necessary. Running the native workload on a physical iPhone
requires an application/test host that calls `runResourceSoak`; the iOS compile command alone
does not run it. Run this blocking test owner outside the UI thread, and set
`SoakPlatform.reportDirectory` to an app-writable directory. CI simulator/emulator measurements
do not establish performance, thermal behaviour or memory budgets on production phones.

## Workload

Each batch creates 16 Stores and destroys them after the workload. The long benchmark runs
these Stores and their UI consumers on an **owned fixed pool of four workers**, with one
separate owned writer worker for blocking journal I/O. Both pools are closed and their workers
are terminated before each GC/memory snapshot; the report states this dispatcher configuration
and requires zero owned workers after close. The common implementation is
[ResourceSoak.kt](src/commonMain/kotlin/koma/soak/ResourceSoak.kt).

- Synthetic ASCII message bodies are approximately 4 KiB. Each Store's domain history retains
  at most 64 messages; there is no unbounded model history. Bursts deliberately exceed the
  admission capacity of 32 pending actions per Store.
- A timer transitions every 100 ms. Entry starts a real cancellable command that waits 40 ms,
  and emits a retained render effect. State collectors and mailbox subscribers represent screen
  consumers; one subscriber repeatedly leaves and reenters while effects are pending. This
  measures subscriptions and Store lifetime, not rendered Compose frames or frame jank.
- Recording alternates on/off between batches. With recording on, a production
  `RecordingSession` retains at most 4000 records and has a writer queue of 256 records.
  Payload policies project a real message body into journal records; this is not a metadata-only
  tiny-integer benchmark.
- A production `JournalFileSink` writes actual platform files, with 2 ms of deliberately
  injected blocking latency **per write** before the real I/O. It rotates a ring of two
  128 KiB segments and flushes every eight records. Journal queue pressure must produce visible
  `droppedForSinks`, while Store latency remains bounded. This controlled slow-I/O model does
  not claim that an emulator's virtual disk matches a phone's flash storage.
- Each batch stops producers, waits for every accepted message to commit, closes Stores,
  awaits all owned Store/subscriber/command jobs, drains the recording, closes the sink,
  joins the writer, and terminates both owned dispatcher pools. Pending Store inputs, active commands, mailbox effects, child jobs and
  file outputs must all be zero after teardown. Batch directories are deleted. External sources
  are outside this workload (`sourceCount=0`); their lifecycle has separate contract tests.

Diagnostic state is bounded too: 4096 rolling latency samples, at most 20 failure descriptions,
and one small result per batch. The harness never retains completed Stores or message objects
in its report.

## Measurements and fixed budgets

Reports contain the actual runtime/OS/device/architecture/processor and OS-thread metadata,
workload configuration, sample counts, rejection/drop counts and lifecycle counters. Latency is
**accepted offer to committed decision**, in microseconds. Each batch requires at least 100
samples; p95/p99 use the most recent 4096 values, and `accepted` includes all commits in that
batch. Rejected offers do not enter latency percentiles.

| Measurement | Budget |
| --- | --- |
| p95 commit latency | 500,000 µs (500 ms) |
| p99 commit latency | 1,000,000 µs (1 s) |
| Accepted in-flight high water | At most 33 per Store: 32 waiting plus one processing |
| Sampled core pending inputs | At most 40: admission allowance plus bounded timer/command traffic |
| Sampled pending mailbox | At most 16 per Store |
| Sampled retained journal | At most 4000 |
| Configured writer queue | 256; exact occupancy is not exposed by `RecordingSession` |
| Active sink calls | At most one |
| Managed/native heap plateau growth | At most 8 MiB each |
| Process resident-memory plateau growth | At most 64 MiB |
| OS-thread plateau growth | At most 16 threads |
| After-close resource counters | Zero |

The in-flight, pending-input, mailbox and retained-record peaks are **sampled** and may miss a
brief peak. `StoreInternalApi.awaitIdle(Duration.ZERO)` samples actual core pending inputs;
in-flight uses offer/commit bookkeeping. Writer concurrency is measured; the writer queue's
configured bound and its real drop counts are reported. No derived rate is presented as an
exact queue high water.

For long runs, memory samples exclude the first 30 seconds (or one fifth of runs shorter than
150 seconds). They follow batch teardown and two requested GCs, with delays for reclamation.
Only batches that **start after warmup** receive the p95/p99 budget check. Cold-start and
warmup latency measurements remain in `warmupSamples` and CSV rows marked `warmup`.
Memory is assessed separately for recording ON and OFF: each mode requires at least nine
post-warmup samples, and its first-third and last-third medians use at least three snapshots.
Runs continue until both modes have nine snapshots, so a manual 30-second run can exceed
its requested minimum while collecting coverage. The report includes both mode deltas; the worst mode delta must satisfy each unchanged budget.
This avoids treating a stable ON/OFF allocation-level difference as cumulative growth while
still rejecting growth within either mode.

Runs shorter than 30 seconds are `harness-smoke`: they require two measured batches after the
first batch and still enforce queue bounds, at least 100 latency samples per batch, every
accepted message committing, and complete teardown. They **do not assess latency budgets or
memory plateau**; `plateauEvaluated` is false and all growth fields are null. Long runs set
`plateauEvaluated` only when both recording modes have sufficient coverage. Android's `System.gc` is a request; the report is an empirical plateau check, not a
proof that every unreachable object was collected.

Android reports used Java heap, `Debug.getNativeHeapAllocatedSize`, process PSS from
`Debug.MemoryInfo` (converted from KiB to bytes), and `/proc/self/task` thread count. iOS reports
[Kotlin/Native GCInfo allocator heap bytes](https://github.com/JetBrains/kotlin/blob/v2.3.20/kotlin-native/runtime/src/alloc/custom/cpp/GCApi.cpp#L138),
Mach process resident size and Mach thread count, releasing the returned Mach thread rights.
On iOS `heapBytes` and `nativeBytes` are the same `GCInfo.memoryUsageAfter["heap"].totalObjectsSizeBytes` metric. With the pinned
Kotlin 2.3.20 custom allocator, this tracks allocator-backed system allocations/pages; it is
not an exact count of live object bytes or all native malloc allocations. Resident memory also
includes runtime/allocator/system memory. The JVM target is only a
harness smoke check and reports zero for unavailable native/PSS metrics.

The fixed topology controls runtime worker allocation while preserving parallel Store
execution. Darwin `Dispatchers.Default` uses an elastic global GCD queue; its thread population
and allocator-backed pages can vary after application jobs finish. The plateau budgets above
apply to the reported four-plus-one owned-worker workload, not to arbitrary Default dispatcher
thread capacity or physical-device performance. An additional test execution launches a fresh Native process (and a separate JVM test worker)
from the same test compilation/binary. The ordinary and long test tasks depend on this execution,
so Default diagnostics cannot pollute the controlled soak process baseline. Its real
`Dispatchers.Default` regression
runs repeated recording ON/OFF batches and checks weak-reference witnesses for all Stores,
current domain contexts and payloads, owner jobs, and the RecordingSession after the isolated
batch helper returns and GC runs. A deliberately retained payload is a positive control for
that detector. Default allocator/thread snapshots are diagnostic; the reachability test makes
no allocator-plateau claim. Weak checks sample these object graphs; the controlled long test
still gates all measured allocator/resident growth, including allocations outside those graphs.

## Reports and interpreting a failure

Android runs the assembled test APK with `am instrument`, copies its private reports with
`adb exec-out run-as` while the APK is still
installed, checks the instrumentation's positive test count and success, then uninstalls its
test package. This avoids AGP/UTP's cleanup deleting app-owned reports before collection.
CI validates the requested duration, actual device metadata, successful measurements, both
recording modes and at least nine steady samples per mode. The validator independently
recomputes the mode medians and worst-mode deltas from the raw samples. It fails if instrumentation/native tests ran zero
cases or left no report. Measurement execution is never reused from Gradle's task cache.
CI uploads `report.json`, `samples.csv` and test reports even on failure. Native/JVM reports
are under `verification/resource-soak/build/reports/resource-soak/ios` and `.../jvm`. Android
writes app-private files under `files/resource-soak-android` in the test package; CI copies them
into its artifact directory before uninstall. The test clears its previous benchmark directory
from inside the application UID, avoiding scoped external-storage permission assumptions.
Files from a failed batch are retained for diagnosis. A negative-path test uses a real journal
file and a stalled sink to verify that a recording-drain timeout still closes its writer and file.

A failed budget includes observed values and units. Diagnose the report before changing a
threshold: an overloaded CI runner, a platform/runtime change and an actual library regression
are different possible causes. Thresholds are deliberately conservative and fixed in source;
the report keeps their values with every run. This harness covers the stated workload and
bounded projections; larger payloads, UI rendering and application-specific policies need
additional measurements on the devices that will ship.
