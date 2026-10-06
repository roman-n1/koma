"""Hash every frozen client jar/klib; recompilation or artifact replacement cannot pass."""
import hashlib, json, sys
from pathlib import Path
mode, directory, manifest = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
actual = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.rglob("*") if p.is_file() and p.suffix in {".jar", ".klib"}}
assert actual, "No frozen client binaries"
if mode == "snapshot":
    manifest.write_text(json.dumps(actual, sort_keys=True, indent=2))
else:
    assert actual == json.loads(manifest.read_text()), "Frozen client artifact changed during runtime substitution"
print("Frozen client binaries unchanged:", len(actual))
