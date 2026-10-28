#!/usr/bin/env python3
"""Rebuild the template overrides under src/main/resources/timvw-rust/ from the stock templates.

The generator keeps one override set per supported openapi-generator minor version
(``og7.15.0``, ``og7.25.0``): every file in a set starts as a verbatim copy of the stock
``rust`` template of that version and then receives the same, small edits. Anything the
set does not contain falls back to the stock template at run time, so only edited files
are checked in.

Usage: tools/sync-templates.py <openapi-generator jar> ...

For every jar given, its version is read from ``META-INF/MANIFEST.MF`` and the matching
set is rewritten. Each edit must match its anchor exactly once (or as often as stated),
otherwise the script fails: that is the signal that a stock template changed and the
edit must be reviewed.
"""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/main/resources/timvw-rust"

CRATE_DOCS = '#![doc = include_str!("../README.md")]\n\n'

BON_BUILDER_FN = "{{#bonBuilders}}\n#[bon::builder]\n{{/bonBuilders}}\n"

DEEP_OBJECT_PANIC = '    unimplemented!("Only objects are supported with style=deepObject")\n'
DEEP_OBJECT_FIX = (
    "    // Non-object values (e.g. array items) are emitted as a plain key/value pair.\n"
    "    let value = match value {\n"
    "        serde_json::Value::String(s) => s.clone(),\n"
    "        _ => value.to_string(),\n"
    "    };\n"
    "    vec![(prefix.to_string(), value)]\n"
)

STRUCT_DERIVE = "#[derive(Clone, Default, Debug, PartialEq, Serialize, Deserialize)]\npub struct {{{classname}}} {"
STRUCT_DERIVE_BON = (
    "#[derive(Clone, Default, Debug, PartialEq, Serialize, Deserialize"
    "{{#bonBuilders}}, bon::Builder{{/bonBuilders}})]\npub struct {{{classname}}} {"
)

# A `///` line whose whole content is mustache output: the rustdoc lambda wraps bare URLs in <...>.
DOC_LINE = re.compile(r"^(?P<indent>\s*)/// (?P<body>\{\{\{.*)$", re.MULTILINE)


def replace_exactly(text: str, old: str, new: str, count: int, what: str) -> str:
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{what}: expected {count} occurrence(s) of anchor, found {found}")
    return text.replace(old, new)


def wrap_doc_lines(text: str, what: str) -> str:
    text, n = DOC_LINE.subn(r"\g<indent>/// {{#rustdoc}}\g<body>{{/rustdoc}}", text)
    if n == 0:
        raise SystemExit(f"{what}: no doc lines found to wrap")
    return text


EDITS = {
    "lib.mustache": lambda t, w: CRATE_DOCS + t,
    "model.mustache": lambda t, w: wrap_doc_lines(
        replace_exactly(t, STRUCT_DERIVE, STRUCT_DERIVE_BON, 1, w), w
    ),
    "reqwest/api.mustache": lambda t, w: wrap_doc_lines(
        re.sub(
            r"^(?=pub \{\{#supportAsync\}\}async \{\{/supportAsync\}\}fn \{\{\{operationId\}\}\}\()",
            BON_BUILDER_FN,
            replace_exactly(t, "pub {{#supportAsync}}async {{/supportAsync}}fn {{{operationId}}}(",
                            "pub {{#supportAsync}}async {{/supportAsync}}fn {{{operationId}}}(", 2, w),
            flags=re.MULTILINE,
        ),
        w,
    ),
    "reqwest/api_mod.mustache": lambda t, w: replace_exactly(t, DEEP_OBJECT_PANIC, DEEP_OBJECT_FIX, 1, w),
    "Cargo.mustache": lambda t, w: replace_exactly(
        t, "[dependencies]\n", '[dependencies]\n{{#bonBuilders}}\nbon = "^3"\n{{/bonBuilders}}\n', 1, w
    ),
}


def version_of(jar: zipfile.ZipFile) -> str:
    manifest = jar.read("META-INF/MANIFEST.MF").decode()
    match = re.search(r"^implementation-version: (\S+)", manifest, re.MULTILINE | re.IGNORECASE)
    if not match:
        raise SystemExit(f"{jar.filename}: no Implementation-Version in the manifest")
    return match.group(1)


def sync(jar_path: Path) -> None:
    with zipfile.ZipFile(jar_path) as jar:
        version = version_of(jar)
        major_minor = ".".join(version.split(".")[:2])
        out = TARGET / f"og{major_minor}.0"
        for name, edit in EDITS.items():
            what = f"{version} {name}"
            stock = jar.read(f"rust/{name}").decode()
            edited = edit(stock, what)
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(edited)
            print(f"wrote {path.relative_to(ROOT)} (from openapi-generator {version})")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for arg in argv[1:]:
        sync(Path(arg))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
