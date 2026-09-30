# A recording is written to segments that each begin with a checkpoint, and a group's order to its own file

- Updated: 2026-09-30

## Background

The [handoff](../design/2026-09-29-time-travel-logging-handoff.md) §7.1 wants replay recordings
on disk like the journal: versioned, framed and checksummed segments, an unfinished tail
dropped with a mark, rotation bounding the size, and one rule of its own: a debug ring deletes
old finished segments together with the checkpoints that depend on them, and the remaining
replay range must begin at a full checkpoint and hold a continuous history; loss inside a range
is a gap that a replay never crosses. Until now a recording lived in memory
(`MachineRecorder`) or as one JSON text (`RecordingCodec`); nothing recorded a run to disk as it
happened, and a group had no container at all.

## Decision

The format lives in `koma.timetravel.file`; the framing is the journal's, made shareable.

- **One framing for every segment file.** `Framing` in `koma-observability` is the
  `[length][crc32][payload]` frame, the end frame and the reader that returns the frames whose
  checksum held with the mark of what stopped it; `JournalFileFormat` now uses it, with its
  goldens unchanged. A recording segment is `KOMARECD`, a header frame, a checkpoint frame,
  step frames and the end frame; a group's order segment is `KOMAGRPO`, a header frame, entry
  frames and the end frame. Checkpoints and steps are the `RecordingCodec`'s JSON, one per
  frame with a tag byte, so the recording's format version travels with the file format's.
- **Every segment begins with a checkpoint.** `RecordingFileSink` is a `DecisionObserver` that
  carries the executor's checkpoint forward on the store's thread (`carriedPast`, pure) and
  hands each step to one writer coroutine with the checkpoint before it, so any step can begin
  a segment. The writer rotates by size and deletes the oldest segments beyond the budget;
  whatever the ring drops, what remains begins with a checkpoint and replays. The header's
  `firstStep` is the step's index in the whole run.
- **A drop makes a hole, not a corruption.** A step the writer's queue has no room for is
  dropped and counted; the next step begins a new segment with the checkpoint that already
  includes the dropped one. The file then has a hole (`StepsMissing`) and a replayable range
  after it, instead of a segment whose steps do not lead to the next. A step dropped with no
  step after it, at the end of a run, is simply absent: the files end before the run did, and
  only the sink's counters tell; a closing checkpoint would document it, but as a segment of
  its own it would be the "last range" a reader returns, an empty one, so it is not written.
- **A hole is never crossed.** `RecordingFiles.read` walks the segments in index order and
  returns the last continuous range: a missing segment, a jump in `firstStep`, a checkpoint
  that does not continue the previous segment's steps, or damage inside a segment ends the
  range, and the next readable segment begins a new one from its own checkpoint. The range
  that remains is a `Recording` that replays; the marks say what was lost before or inside.
- **A group's container is the members' files and one order file.** `GroupRecordingFileSink`
  records each member through its own `RecordingFileSink` and the group's order through one
  more writer: an entry per decision with the member, its step, the messages the step sent
  over the bridge and the one it received. Every order segment's header carries the members,
  the routes, the attached sources and the messages in flight before its first entry, so a
  range that begins there checks causality without the sends before it.
  `GroupRecordingFiles.read` takes the order file's last continuous range and every member's,
  cuts them to the range every file still covers, trims each member to its checkpoint at the
  range's first entry of it, and computes the messages in flight there from the header and the
  entries before it. The result is a `GroupRecording` that `GroupReplaySession` verifies.

Not adopted:

- Sparse checkpoints with a replay between them: every segment carrying its checkpoint costs
  one checkpoint per segment, which the ring's rule needs anyway, and keeps a reader from
  deciding anything to find its start.
- Writing steps under the store's lock: the observer carries the checkpoint (pure) and enqueues;
  the writer does the I/O, as the journal's does (§7.1 forbids I/O under the lock).
- One file for the whole group: the members' files are the single-store format, readable and
  replayable on their own; the order file adds only what the group knows.

## Notes

- Tests: [`RecordingFileFormatTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/file/RecordingFileFormatTest.kt)
  (round trip, a pinned golden, a newer format refused, every cut a prefix with a mark, a
  flipped byte caught, `carriedPast` equal to `checkpointAt`);
  [`RecordingFileSinkTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/file/RecordingFileSinkTest.kt)
  (a live run read back as the recorder saw it and replaying; rotation with a checkpoint per
  segment and a ring that still replays from what remains; a dropped step leaving a marked hole
  and a range after it; a crash mid-write and damage in the middle never crossed);
  [`RecordingFileStormTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/file/RecordingFileStormTest.kt)
  (a six-thread storm with a queue too small and a ring of four small segments: what the files
  hold is the tail of the run from the recorder's checkpoint there, and it replays);
  [`GroupRecordingFilesTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/file/GroupRecordingFilesTest.kt)
  (a group read back as the recorder saw it; a ring that dropped early segments yielding the
  range every file covers with its messages in flight; a dropped order entry starting a new
  range; the order segment's round trip and cuts).
- Left for later: sources' snapshots in the order file (a range since a cut knows them, a file
  does not yet), pruning across stores by total size (the journal's `prune`), and a device
  measurement of the write cost.

## Related

- [The journal file format](./2026-09-30-journal-file-format.md)
- [A run is replayed by deciding its recorded inputs again](./2026-09-30-single-store-replay.md)
- [Group replay](./2026-09-30-group-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §7.1, §8, §9
