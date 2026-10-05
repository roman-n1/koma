"""An expected compile failure must diagnose every forbidden published API operation."""
import re
import sys
from pathlib import Path
fixture, output = Path(sys.argv[1]), Path(sys.argv[2]).read_text()
expected = [(line, text.split("// reject: ")[1].strip()) for line, text in enumerate(fixture.read_text().splitlines(), 1) if "// reject: " in text]
for line, symbol in expected:
    lines = output.splitlines()
    position = next((index for index, text in enumerate(lines) if re.search(r"NegativeContracts\.kt:" + str(line) + r":\d+", text)), None)
    diagnostic = "\n".join(lines[position:position + 5]) if position is not None else None
    assert diagnostic and symbol in diagnostic, ("Missing expected diagnostic", line, symbol, diagnostic)
assert "Unresolved reference" in output or "receiver type mismatch" in output, "No API rejection diagnostics"
print("Published adapter contracts reject all", len(expected), "forbidden operations")
