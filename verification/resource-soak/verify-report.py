"""Fail CI if device tests did not produce fresh measurements for the requested workload."""
import csv
import json
import math
import pathlib
import re
import sys

directory, expected_platform, requested_seconds = sys.argv[1:4]
if len(sys.argv) == 5:
    instrumentation = pathlib.Path(sys.argv[4]).read_text()
    result = re.search(r"OK \((\d+) tests?\)", instrumentation)
    assert result and int(result[1]) > 0, instrumentation
    assert "FAILURES!!!" not in instrumentation and "INSTRUMENTATION_FAILED" not in instrumentation, instrumentation
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
expected_warmup = min(30, seconds // 5)
assert int(metadata["warmupSeconds"]) == expected_warmup, metadata
fixed_budgets = {"p95Micros": 500000, "p99Micros": 1000000, "heapPlateauGrowthBytes": 8 * 1024 * 1024,
                 "nativePlateauGrowthBytes": 8 * 1024 * 1024, "residentPlateauGrowthBytes": 64 * 1024 * 1024,
                 "threadPlateauGrowth": 16}
assert all(report["budgets"][key] == value for key, value in fixed_budgets.items()), report["budgets"]
samples = report["samples"]
warmup = report["warmupSamples"]
all_samples = sorted(warmup + samples, key=lambda s: s["result"]["batch"])
assert len({s["result"]["batch"] for s in all_samples}) == len(all_samples), all_samples
assert [s["result"]["batch"] for s in samples] == sorted(s["result"]["batch"] for s in samples)
previous_end = -1
for sample in all_samples:
    assert previous_end <= sample["startedMillis"] <= sample["elapsedMillis"], sample
    previous_end = sample["elapsedMillis"]
    for field in ("heapBytes", "nativeBytes", "residentBytes", "threads"):
        value = sample["memory"][field]
        assert isinstance(value, int) and not isinstance(value, bool) and math.isfinite(value) and value >= 0, (field, value)
    for field in ("p95Micros", "p99Micros"):
        value = sample["result"][field]
        assert isinstance(value, int) and not isinstance(value, bool) and value >= 0, (field, value)
for sample in warmup:
    assert sample["result"]["batch"] == 0 or sample["startedMillis"] < expected_warmup * 1000, sample
for sample in samples:
    assert sample["result"]["batch"] > 0 and sample["startedMillis"] >= expected_warmup * 1000, sample
assert len(samples) >= (18 if seconds >= 30 else 2), samples
assert samples[-1]["elapsedMillis"] >= seconds * 1000, samples[-1]
assert any(s["result"]["recording"] for s in samples)
assert any(not s["result"]["recording"] for s in samples)
if seconds >= 30:
    assert metadata["assessment"] == "steady-soak", metadata
    assert report["plateauEvaluated"] is True, report
    growth_by_mode = report["memoryGrowthByMode"]
    assert set(growth_by_mode) == {"recording-on", "recording-off"}, growth_by_mode
    for mode in (False, True):
        group = [s for s in samples if s["result"]["recording"] == mode]
        assert len(group) >= 9, (mode, len(group))
        window = len(group) // 3
        calculated = {}
        for metric in ("heapBytes", "nativeBytes", "residentBytes", "threads"):
            def median(rows):
                return sorted(s["memory"][metric] for s in rows)[len(rows) // 2]
            calculated[metric] = median(group[-window:]) - median(group[:window])
        assert growth_by_mode["recording-on" if mode else "recording-off"] == calculated, (mode, calculated, growth_by_mode)
    for sample in samples:
        assert sample["startedMillis"] >= int(metadata["warmupSeconds"]) * 1000, sample
        assert sample["result"]["p95Micros"] <= report["budgets"]["p95Micros"], sample
        assert sample["result"]["p99Micros"] <= report["budgets"]["p99Micros"], sample
    for field, budget in (("heapGrowthBytes", "heapPlateauGrowthBytes"), ("nativeGrowthBytes", "nativePlateauGrowthBytes"),
                          ("residentGrowthBytes", "residentPlateauGrowthBytes"), ("threadGrowth", "threadPlateauGrowth")):
        metric = {"heapGrowthBytes": "heapBytes", "nativeGrowthBytes": "nativeBytes",
                  "residentGrowthBytes": "residentBytes", "threadGrowth": "threads"}[field]
        assert report[field] == max(mode[metric] for mode in growth_by_mode.values()), (field, report)
        assert report[field] <= report["budgets"][budget], report
else:
    assert metadata["assessment"] == "harness-smoke", metadata
    assert report["plateauEvaluated"] is False, report
    for field in ("memoryGrowthByMode", "heapGrowthBytes", "nativeGrowthBytes", "residentGrowthBytes", "threadGrowth"):
        assert report[field] is None, (field, report[field])
for sample in warmup + samples:
    result = sample["result"]
    assert result["accepted"] >= 100, result
    for field in ("jobsAfterClose", "outputsAfterClose", "pendingInputsAfterClose", "commandsAfterClose", "mailboxAfterClose"):
        assert result[field] == 0, result
with (root / "samples.csv").open() as stream:
    rows = list(csv.DictReader(stream))
assert len(rows) == len(samples) + len(warmup), (len(rows), len(samples), len(warmup))
assert sum(row["phase"] == "warmup" for row in rows) == len(warmup)
print(f"Validated {expected_platform}: {seconds}s, {len(samples)} {metadata['assessment']} samples, {len(warmup)} warmup")
