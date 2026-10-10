# Time Travel: a complete recorded checkout

A runnable Compose Desktop example of two machines: a cart looks up a synthetic price and
routes its calculated total to the order summary. The live reducer deliberately forgets a
discount: price **100**, discount **20**, charged total **100** instead of **80**.

The example uses real `MachineStore`s only while recording. It writes the diagnostic journal,
both member recordings and the group's order to disk, closes the Stores, drains the writers,
then opens the files through the normal codecs and readers. Every subsequent step is offline.
No network, credentials or messenger setup is required.

## Run

From the repository root, with the same JDK/Gradle setup as the library:

```sh
./gradlew :time-travel-example:run
```

1. Click **Record checkout**. A fresh child directory is created under the path shown, normally
   `examples/time-travel/build/time-travel-demo`. The fake price handler runs once, the live
   Stores close, and the inspector opens the saved recording with the original reducer.
2. **Open original** reads those files again. Verify reports no divergence: replay reproduces
   the buggy run, including the summary's total of 100. Back/forward/seek move both machines.
3. **Compare changed reducer** reads the same files using the corrected calculation. The
   inspector verifies the group and positions the cursor at the first divergence, before the
   price result is decided. The error names `cart-1` and the changed context. The recorded
   before/after detail explains the original calculation; the current checkpoint still has
   its price command waiting and its timeout timer registered.
4. Click **Branch Here**, then answer **Price 100** on the awaiting cart command. The corrected
   cart computes 80 and the local bridge updates the summary to 80. Alternatively, answer
   **Price 60** to calculate 40, fail the command, or advance the virtual clock until the
   Loading timeout fires. None of these operations calls the live price handler.
5. **Return to Replay** discards the experiment and keeps the original cursor and divergence.
   **Open original** still verifies the saved run. Files and recorded checkpoints are unchanged.

Keep the displayed recording directory to reopen it after restarting the application: paste
the path and click Open original or Compare changed reducer. Existing runs are never
overwritten. The path field controls both where Record creates a new child and which saved
directory Open reads. Disk I/O runs off the UI thread, and cancellation still drains writers.

## Check without a window

```sh
./gradlew :time-travel-example:jvmTest
./gradlew :time-travel-example:run --args='--check'
# optional parent directory (use an absolute path):
./gradlew :time-travel-example:run --args='--check /tmp/actron-demo'
```

The CLI exercises the same live-to-disk-to-replay path and checks the changed reducer,
experiment, bridge delivery, unchanged replay and exactly one live command call. The tests
also check reopening the original after an experiment, preservation of previous runs,
refusal of an unrelated directory, and the complete UI button workflow on real files.
The root `jvmTest` CI job includes this module's tests.

## How it is wired

- [CheckoutDemo.kt](src/jvmMain/kotlin/actron/example/timetravel/CheckoutDemo.kt): serializable
  contexts/actions/commands/events, machine definitions, live handler, group routing,
  `RecordingSession`/`JournalFileSink`, `GroupRecordingFileSink`, close ordering and file loading.
- [Main.kt](src/jvmMain/kotlin/actron/example/timetravel/Main.kt): file controls plus
  `InspectorState`, `GroupReplayControls`, `GroupTimeTravelControls` and `InspectorScreen`;
  the CLI follows the same path without composing a window.

Original and changed reducers share the same declared chart/schema id and version in this
example so replay can detect a calculation regression; the chart and serialized payload
schema are identical. An incompatible definition/schema change needs its own version and
migration policy. The example requires complete files and explains missing/damaged input
instead of silently comparing a partial run.

This is an unpublished JVM debug application, excluded from library API dumps and publishing.
`checkDebugGraph` treats it as a debug consumer and still rejects production-library
dependencies on the Time Travel modules or this application. Android/iOS adoption reuses
the KMP machines, file readers and inspector APIs with the application's own lifecycle and
file controls; this example does not integrate the messenger.
