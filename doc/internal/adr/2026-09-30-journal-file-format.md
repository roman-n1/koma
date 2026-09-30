# The journal is written as checksummed frames in a ring of segments, over a storage the platform supplies

- Updated: 2026-09-30

## Background

The [handoff](../design/2026-09-29-time-travel-logging-handoff.md) §7.1 asks for the journal on
disk: a versioned file format with framed, checksummed segments; an unfinished tail after a crash
dropped with a mark; rotation and deletion bounding the total size; a safe tail for the crash
reporter; and, from §12, file sinks checked on the target platforms. Until now the journal of
[`RecordingSession`](./2026-09-29-journal-identity-ordering-and-payload-policy.md) was in memory
and in a `Logger`; after a process death nothing of it remained.

## Decision

The format lives in `koma.observability.file`, in `koma-observability`, with no new dependency.

- **The platform supplies only the storage.** `SegmentStorage` is a handful of operations over
  named segments: list with size and modification time, read whole, delete, open for appending.
  `FileSegmentStorage(directory)` is the platform's file system (`java.io` on JVM and Android
  through a shared source set, POSIX on iOS; JS and Wasm have none and throw at construction),
  `InMemorySegmentStorage` is for tests and for platforms without files, with `truncate` and
  `corrupt` to do what a crash and a bad disk do. Everything else, framing, checksums, rotation,
  recovery, is common code over that interface.
- **A segment is magic, a header frame, record frames and an end frame.** Every frame is
  `[i32 length][i32 crc32][payload]`, the CRC-32 of the payload; the end frame has length 0.
  The header carries `JOURNAL_FILE_FORMAT_VERSION` (the layout), `JOURNAL_FORMAT_VERSION` (the
  record model), the session, the group, the mode and the segment's index; a record frame the
  store, the sequence numbers, the elapsed time and the entry. A reader that meets a newer
  version of either refuses the segment; the header stays readable, so it can say which.
  Integers are big-endian, strings a byte length and UTF-8, durations whole nanoseconds,
  enumerations their names: the wire vocabulary of the record model, never class names.
- **A file holds no objects.** A `Payload.Retained` object is written as its `toString()` and
  reads back as the new `Payload.Described(text)`: the policy decided the object may be shown,
  the file keeps what was shown. Projections keep their label and fields; omitted and
  unavailable stay so; failures keep type, message, cause and suppressed. The journal file is
  what the inspector and the crash reporter read; the replay recording is `RecordingCodec` in
  `koma-timetravel`.
- **The reader never throws on damage; it marks.** `JournalFileFormat.decodeSegment` returns the
  frames whose checksum and encoding held, in order, and a `SegmentMark` for where it stopped:
  `TruncatedTail` for a frame cut short (a crash; the partial bytes are dropped), `Unfinished`
  for a segment that ends at a frame boundary without its end frame (nothing lost), `Corrupt`
  for a checksum or encoding failure (the rest of the segment is skipped), `UnsupportedFormat`,
  `NotASegment`. `JournalFiles.read(session)` joins a session's segments in index order and adds
  `MissingSegments` for indices that are absent (rotated, pruned or lost) and `SequenceHole`
  for records absent between two that were read, unless a `JournalGap` record accounts for
  exactly them (the gap-first rule of the session). `isComplete` is "nothing missing or damaged".
- **The sink is a ring of segments.** `JournalFileSink` writes one session (the first record
  fixes it; another session's record is refused and the session reports it). It rotates when
  the next record would push the segment past `maxSegmentBytes`, ends the finished segment with
  the end frame, and deletes this session's oldest segments beyond `maxSegments`; a reader marks
  the hole. Writing happens on the session's writer coroutine; `flush()` and `close()` may be
  called from a lifecycle or crash hook and never wait for a Store. `flushEveryRecords` bounds
  what the platform's buffer may hold; absolute safety of the last records at a crash is not
  promised (§7.1).
- **The crash reporter gets the tail.** `JournalFiles.tail(session, n)` and `exportLines`
  (`JournalFormat.line` for records, `! ` and the mark for marks) are what may be attached: the
  records as the policy left them, which under the production policy is metadata only.
  `prune(maxTotalBytes)` deletes the oldest sessions whole, never the newest, so a directory of
  runs stays bounded across process deaths.

Not adopted:

- kotlinx-io or Okio for the storage: one interface with a few methods keeps the module free of
  a file-system dependency for every consumer, and the platforms' own APIs are enough.
- JSON lines: the handoff asks for framing and checksums, which are binary; the export to lines
  gives the readable form.
- Compression, encryption, or indexes: nothing here needs them yet; a segment is read whole.
- Checkpoints in the journal file: the journal is `InspectOnly`; a replay range begins at a
  checkpoint of a `koma-timetravel` recording (its own format).

## Notes

- Tests: [`JournalFileFormatTest`](../../../koma-observability/src/commonTest/kotlin/koma/observability/file/JournalFileFormatTest.kt)
  round-trips every entry and payload kind, pins the bytes of a small segment, refuses a newer
  format, and, for what no hand reproduces, decodes every cut of a segment (always a prefix of
  the records with a mark) and every flipped bit of it (caught, never a wrong record);
  [`JournalFileSinkTest`](../../../koma-observability/src/commonTest/kotlin/koma/observability/file/JournalFileSinkTest.kt)
  reads back what a session published, rotates and prunes, loses only the partial frame of a
  crash, skips the rest of a damaged segment and reads the next, explains holes by gap records,
  and covers the tail, the export and pruning across sessions;
  `FileSegmentStorageJvmTest` and `FileSegmentStorageIosTest` do it over real files.
- The default budgets, 512 KiB per segment and 8 segments, are about 4 MB per session and, at
  about 120 bytes per record under the production policy, roughly the last 30 000 records;
  measure on the target devices, as for the in-memory budgets.
- Left for later: a device-side measurement of the write cost; an `iosArm64` device build of
  the storage (compiled, not run); a segment index for reading the tail without the whole file.

## Related

- [Journal identity, ordering and payload policy](./2026-09-29-journal-identity-ordering-and-payload-policy.md)
- [A run is replayed by deciding its recorded inputs again](./2026-09-30-single-store-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §7.1, §12
