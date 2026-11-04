#!/usr/bin/env python3
"""Checks every Default in a generated crate against the defaultOnlyWhereValid rule.

Usage: tools/check-default-rule.py <crate root> [--spec <spec.json>]

Reads the rendered models as text, independently of the generator's decision:

- a struct derives Default exactly when it has no required field. A field is required when its serde attribute has no
  skip_serializing_if, which the template writes on every optional field;
- no enum derives or implements Default.

With --spec, structs named after a component schema are also held against that schema's `required` list (its own, its
allOf members' and a single oneOf/anyOf branch's, followed through $ref), so a wrong rendering of required cannot hide
a wrong decision. A struct flattened from a union with several branches cannot be compared and is only counted.

Prints the counts and every violation; exits 1 when there is any.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ATTRS = r"(?P<attrs>(?:[ \t]*#\[[^\n]*\]\n|[ \t]*///[^\n]*\n)*)"
STRUCT = re.compile(ATTRS + r"pub struct (?P<name>\w+)\s*\{", re.MULTILINE)
ENUM = re.compile(ATTRS + r"pub enum (?P<name>\w+)\s*\{", re.MULTILINE)
FIELD = re.compile(r"^\s*pub (?:r#)?(?P<name>\w+):", re.MULTILINE)
IMPL_DEFAULT = re.compile(r"impl\s+(?:std::default::)?Default\s+for\s+(\w+)")


def derives_default(attrs: str) -> bool:
    return any(re.search(r"\bDefault\b", d) for d in re.findall(r"#\[derive\(([^)]*)\)\]", attrs, re.DOTALL))


def closing_brace(text: str, opening: int) -> int:
    depth = 0
    for i in range(opening, len(text)):
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        if depth == 0:
            return i
    raise ValueError("unbalanced braces")


def required_fields(body: str) -> list[str]:
    """Fields without skip_serializing_if in the attributes between the previous field and their own line."""
    required, start = [], 0
    for m in FIELD.finditer(body):
        if "skip_serializing_if" not in body[start : m.start()]:
            required.append(m.group("name"))
        start = m.end()
    return required


def spec_required(schemas: dict, schema: dict, seen: set[str]) -> set[str] | None:
    """The required names of a schema: its own, its allOf members' and a single oneOf/anyOf branch's, through $ref.

    None when a branch of a real union is involved: the generator flattens those, so the spec does not say which of
    the fields are required.
    """
    ref = schema.get("$ref", "")
    if ref:
        name = ref.rsplit("/", 1)[-1]
        if name in seen or name not in schemas:
            return set()
        return spec_required(schemas, schemas[name], seen | {name})
    required = set(schema.get("required") or [])
    members = list(schema.get("allOf") or [])
    for key in ("oneOf", "anyOf"):
        branches = schema.get(key) or []
        if len(branches) > 1:
            return None
        members += branches
    for member in members:
        inner = spec_required(schemas, member, seen)
        if inner is None:
            return None
        required |= inner
    return required


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("crate", type=Path)
    parser.add_argument("--spec", type=Path)
    args = parser.parse_args()

    schemas = {}
    if args.spec:
        schemas = json.loads(args.spec.read_text()).get("components", {}).get("schemas", {})

    violations = []
    with_default, without_default, enums, spec_checked, spec_skipped = 0, 0, 0, 0, 0
    for path in sorted((args.crate / "src/models").glob("*.rs")):
        if path.name == "parse_enum_error.rs":
            continue  # enumFromStr's error type: not a model of the spec
        text = path.read_text()
        for m in STRUCT.finditer(text):
            body = text[m.end() : closing_brace(text, m.end() - 1)]
            required = required_fields(body)
            has_default = derives_default(m.group("attrs"))
            with_default += has_default
            without_default += not has_default
            if has_default == bool(required):
                violations.append(f"{path.name}: struct {m.group('name')}: Default={has_default}, required={required}")
            if m.group("name") in schemas:
                expected = spec_required(schemas, schemas[m.group("name")], {m.group("name")})
                if expected is None:
                    spec_skipped += 1
                    continue
                spec_checked += 1
                if bool(expected) == has_default:
                    violations.append(
                        f"{path.name}: struct {m.group('name')}: Default={has_default}, spec required={sorted(expected)}"
                    )
        for m in ENUM.finditer(text):
            enums += 1
            if derives_default(m.group("attrs")):
                violations.append(f"{path.name}: enum {m.group('name')} derives Default")
        for name in IMPL_DEFAULT.findall(text):
            violations.append(f"{path.name}: impl Default for {name}")

    print(f"structs with Default:    {with_default}")
    print(f"structs without Default: {without_default}")
    print(f"enums:                   {enums}")
    if args.spec:
        print(f"structs held against the spec: {spec_checked} ({spec_skipped} flattened unions not comparable)")
    print(f"violations:              {len(violations)}")
    for violation in violations:
        print("  " + violation)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
