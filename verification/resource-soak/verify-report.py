"""Fail CI if device tests did not produce fresh measurements for the requested workload."""
import csv
import json
import pathlib
import sys

directory, expected_platform, requested_seconds = sys.argv[1:]
seconds = int(requested_seconds)
assert 3 <= seconds <= 3600
root = pathlib.Path(directory)
report = json.loads((root / "report.json").read_text())
metadata = report["metadata"]
assert metadata["platform"] == expected_platform, metadata
assert int(metadata["durationSeconds"]) == seconds, metadata
for field in ("runtime", "os", "device", "architecture"):
    assert metadata.get(field) not in (None, "", "null"), (field, metadata)
assert not report["failures"], report["failures"]
samples = report["samples"]
assert len(samples) >= (6 if seconds >= 30 else 2), samples
assert samples[-1]["elapsedMillis"] >= seconds * 1000, samples[-1]
assert any(s["result"]["recording"] for s in samples)
assert any(not s["result"]["recording"] for s in samples)
for sample in samples:
    result = sample["result"]
    assert result["accepted"] >= 100, result
    for field in ("jobsAfterClose", "outputsAfterClose", "pendingInputsAfterClose", "commandsAfterClose", "mailboxAfterClose"):
        assert result[field] == 0, result
with (root / "samples.csv").open() as stream:
    rows = list(csv.DictReader(stream))
assert len(rows) == len(samples), (len(rows), len(samples))
print(f"Validated {expected_platform}: {seconds}s, {len(samples)} steady samples")
