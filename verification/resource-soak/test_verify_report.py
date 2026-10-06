"""Exercise CI's reader against misleading/insufficient measurements, not just valid output."""
import copy
import csv
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


class ReportValidationTest(unittest.TestCase):
    def report(self, seconds=300):
        short = seconds < 30
        samples = []
        for batch in range(1, 3 if short else 19):
            end = batch * 1500 if short else 30000 + batch * 15000
            samples.append({"startedMillis": end - 500, "elapsedMillis": end,
                            "result": {"batch": batch, "recording": batch % 2 == 0,
                                       "accepted": 100, "p95Micros": 700000 if short else 20,
                                       "p99Micros": 1200000 if short else 30,
                                       **{f: 0 for f in ("jobsAfterClose", "outputsAfterClose", "pendingInputsAfterClose",
                                                        "commandsAfterClose", "mailboxAfterClose", "ownedWorkersAfterClose")}},
                            "memory": {"heapBytes": (40 if batch % 2 == 0 else 8) * 1024 * 1024,
                                       "nativeBytes": 0, "residentBytes": 0, "threads": 20}})
        warmup = copy.deepcopy(samples[:1])
        warmup[0].update(startedMillis=0, elapsedMillis=500)
        warmup[0]["result"].update(batch=0, p95Micros=794279, p99Micros=811660)
        return {"metadata": {"platform": "iOS", "durationSeconds": str(seconds), "warmupSeconds": "0" if short else "30",
                             "assessment": "harness-smoke" if short else "steady-soak",
                             "storeWorkers": "4", "writerWorkers": "1",
                             "dispatcher": "owned fixed pool: stores=4, writer=1; terminated before GC",
                             **{f: "actual-test-device" for f in ("runtime", "os", "device", "architecture")}},
                "budgets": {"p95Micros": 500000, "p99Micros": 1000000, "heapPlateauGrowthBytes": 8 * 1024 * 1024,
                            "nativePlateauGrowthBytes": 8 * 1024 * 1024, "residentPlateauGrowthBytes": 64 * 1024 * 1024,
                            "threadPlateauGrowth": 16}, "samples": samples, "warmupSamples": warmup,
                "plateauEvaluated": not short, "memoryGrowthByMode": None if short else {
                    mode: {"heapBytes": 0, "nativeBytes": 0, "residentBytes": 0, "threads": 0}
                    for mode in ("recording-on", "recording-off")},
                **{f: None if short else 0 for f in ("heapGrowthBytes", "nativeGrowthBytes", "residentGrowthBytes", "threadGrowth")},
                "failures": []}

    def validate(self, report, succeeds):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "report.json").write_text(json.dumps(report))
            with (root / "samples.csv").open("w") as stream:
                writer = csv.DictWriter(stream, fieldnames=["phase"])
                writer.writeheader()
                writer.writerows({"phase": "warmup"} for _ in report["warmupSamples"])
                writer.writerows({"phase": "steady"} for _ in report["samples"])
            result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("verify-report.py")),
                                     directory, "iOS", report["metadata"]["durationSeconds"]], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)

    def test_stable_different_modes_and_cold_latency_are_valid(self):
        self.validate(self.report(), True)

    def test_actual_growth_cannot_be_hidden_by_reported_zero(self):
        report = self.report()
        for sample in report["samples"][-6:]:
            if sample["result"]["recording"]:
                sample["memory"]["heapBytes"] += 12 * 1024 * 1024
        self.validate(report, False)

    def test_short_smoke_has_no_performance_verdict(self):
        self.validate(self.report(3), True)
        report = self.report(3)
        report["heapGrowthBytes"] = 0
        self.validate(report, False)

    def test_long_requires_nine_snapshots_in_each_mode(self):
        report = self.report()
        report["samples"][0]["result"]["recording"] = True
        self.validate(report, False)
        report = self.report()
        report["plateauEvaluated"] = False
        self.validate(report, False)

    def test_post_warmup_latency_and_invalid_measurements_fail(self):
        report = self.report()
        report["samples"][-1]["result"]["p95Micros"] = 794279
        self.validate(report, False)
        report = self.report()
        report["samples"][0]["memory"]["heapBytes"] = float("nan")
        self.validate(report, False)


if __name__ == "__main__":
    unittest.main()
