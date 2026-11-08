#!/usr/bin/env python3
"""Links the test names in the Tests rows of docs/mapping/*.md to the test's line.

A test is written `Class#method`, or `#method` for the class named before it in the same row. Run after moving or
renaming tests; MappingDocsTest fails while a link is missing or points at the wrong line.

    python3 tools/link-mapping-tests.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TESTS = ROOT / "src/test/java/be/timvw/openapi/rust"
LINK_BASE = "../../src/test/java/be/timvw/openapi/rust/"
# a test name, linked or not
REF = re.compile(r"\[`([A-Za-z0-9]*)#(\w+)`\]\([^)]*\)|`([A-Za-z0-9]*)#(\w+)`")


def line_of(cls: str, method: str) -> int:
    for number, line in enumerate((TESTS / f"{cls}.java").read_text().splitlines(), 1):
        if re.search(rf"\bvoid {re.escape(method)}\(", line):
            return number
    raise SystemExit(f"no test {cls}#{method}")


def link_row(row: str) -> str:
    cls = None

    def link(m: re.Match) -> str:
        nonlocal cls
        name = m.group(1) if m.group(1) is not None else m.group(3)
        method = m.group(2) or m.group(4)
        cls = name or cls
        if cls is None:
            raise SystemExit(f"#{method} names no class: {row}")
        return f"[`{name}#{method}`]({LINK_BASE}{cls}.java#L{line_of(cls, method)})"

    return REF.sub(link, row)


for doc in sorted((ROOT / "docs/mapping").glob("*.md")):
    text = doc.read_text()
    lines = [link_row(l) if l.startswith("| Tests |") and "#" in l else l for l in text.split("\n")]
    new = "\n".join(lines)
    if new != text:
        doc.write_text(new)
        print(f"updated {doc.relative_to(ROOT)}")
