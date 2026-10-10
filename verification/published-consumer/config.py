"""Read coordinates and versions from the producer; no Gradle source substitution."""
import re
import sys
from pathlib import Path
root, key = Path(sys.argv[1]), sys.argv[2]
if key in {"group", "version"}:
    text = (root / "gradle.properties").read_text()
    match = re.search(r"^actron\.fork\." + key + r"=(.+)$", text, re.MULTILINE)
else:
    text = (root / "gradle/libs.versions.toml").read_text().split("[libraries]")[0]
    match = re.search(r"^" + re.escape(key) + r'\s*=\s*"([^"]+)"', text, re.MULTILINE)
if not match:
    raise SystemExit("Missing producer configuration: " + key)
print(match[1].strip())
