#!/usr/bin/env python3
"""Decode one sample per branch of every union of a generated crate, and say which variant each one lands in.

Usage: tools/check-union-samples.py <crate dir> [--plan <file>] [--report <file>] [--keep-test]

The crate must have been generated with modelUnions and unionPlanFile (--plan, default
target/reports/union_plan.json under the crate): the plan names the variants of every union and holds the schemas
as the generator saw them, after the spec rules and the inline-model resolver.

For every variant this builds two samples from its branch: "minimal" has only the required fields, "full" has every
optional field as well. It writes them into a throwaway test of the crate (tests/union_samples.rs), runs it with
cargo, and sorts the outcome:

  pass       the sample decodes to the variant of its branch and serializes back to the same JSON
  lossy      it decodes to the right variant, but serializing it does not give the sample back
  ambiguous  it decodes to an earlier variant, and this variant accepts that variant's sample too: the two accept the
             same values, so no order helps, and only tag dispatch can tell them apart
  order      it decodes to an earlier variant that this variant is narrower than: the order is wrong
  rejected   no variant decodes it, or the variant of its branch does not: the type does not match its schema
  no-sample  no finite sample exists (a required field that holds its own type)

The two modes are counted apart. The exit status is 1 when a minimal sample lands in order or rejected. A nullable
union also has to decode null as an Option. Nothing here looks at the wire.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

NO_SAMPLE = object()
MAX_DEPTH = 12


class Sampler:
    def __init__(self, schemas: dict, full: bool):
        self.schemas = schemas
        self.full = full

    def sample(self, schema, stack=(), depth=0):
        if not isinstance(schema, dict):
            return NO_SAMPLE
        if depth > MAX_DEPTH:
            return NO_SAMPLE
        ref = schema.get("$ref")
        if ref:
            name = ref.rsplit("/", 1)[-1]
            if name in stack or name not in self.schemas:
                return NO_SAMPLE
            return self.sample(self.schemas[name], stack + (name,), depth + 1)
        if "const" in schema:
            return schema["const"]
        if schema.get("enum"):
            values = [v for v in schema["enum"] if v is not None]
            return values[0] if values else NO_SAMPLE
        if schema.get("allOf"):
            merged = {}
            for part in schema["allOf"]:
                value = self.sample(part, stack, depth + 1)
                if value is NO_SAMPLE:
                    return NO_SAMPLE
                if not isinstance(value, dict):
                    return value
                merged.update(value)
            return self.object_of(schema, stack, depth, merged) if schema.get("properties") else merged
        for keyword in ("oneOf", "anyOf"):
            if schema.get(keyword):
                for branch in schema[keyword]:
                    if branch.get("type") == "null":
                        continue
                    value = self.sample(branch, stack, depth + 1)
                    if value is not NO_SAMPLE:
                        return value
                return NO_SAMPLE
        return self.by_type(schema, stack, depth)

    def by_type(self, schema, stack, depth):
        kind = schema.get("type")
        if isinstance(kind, list):
            kinds = [k for k in kind if k != "null"]
            kind = kinds[0] if kinds else "null"
        if kind is None and schema.get("types"):
            kinds = [k for k in schema["types"] if k != "null"]
            kind = kinds[0] if kinds else "null"
        if kind is None and (schema.get("properties") or schema.get("additionalProperties") is not None):
            kind = "object"
        if kind == "string":
            return {"date-time": "2024-01-01T00:00:00Z", "byte": "aGk=", "uuid": "00000000-0000-0000-0000-000000000000"}.get(
                schema.get("format"), "x")
        if kind == "integer":
            return schema.get("minimum", 1) if isinstance(schema.get("minimum"), int) else 1
        if kind == "number":
            return 0.5
        if kind == "boolean":
            return True
        if kind == "null":
            return None
        if kind == "array":
            item = self.sample(schema.get("items", {}), stack, depth + 1) if schema.get("items") else "x"
            if item is NO_SAMPLE:
                return [] if not schema.get("minItems") else NO_SAMPLE
            return [item]
        if kind == "object":
            return self.object_of(schema, stack, depth, {})
        return "x"

    def object_of(self, schema, stack, depth, into):
        properties = schema.get("properties") or {}
        required = schema.get("required") or []
        if not properties and not required and not into and schema.get("additionalProperties") in (None, True, {}):
            return {"key": "value"}
        for name in required:
            value = self.sample(properties.get(name, {"type": "string"}), stack, depth + 1)
            if value is NO_SAMPLE:
                return NO_SAMPLE
            into[name] = value
        if self.full:
            for name, prop in properties.items():
                if name not in into:
                    value = self.sample(prop, stack, depth + 1)
                    if value is not NO_SAMPLE and value is not None:
                        into[name] = value
        return into


def raw(text: str) -> str:
    hashes = "#" * 5
    return f'r{hashes}"{text}"{hashes}'


def test_source(package: str, plan: dict) -> tuple[str, list]:
    no_samples = []
    samplers = {"minimal": Sampler(plan["schemas"], False), "full": Sampler(plan["schemas"], True)}
    out = [
        f"use {package}::models;",
        "use serde::{de::DeserializeOwned, Serialize};",
        "use std::fmt::Write as _;",
        "",
        "struct Sample { variant: &'static str, mode: &'static str, json: &'static str, accepts: fn(&str) -> Result<(), String> }",
        "",
        "fn accepts<T: DeserializeOwned>(json: &str) -> Result<(), String> { serde_json::from_str::<T>(json).map(|_| ()).map_err(|e| e.to_string()) }",
        "",
        "fn check<U: DeserializeOwned + Serialize>(out: &mut String, union: &str, pick: fn(&U) -> &'static str, samples: &[Sample]) {",
        "    for s in samples {",
        "        let line = match serde_json::from_str::<U>(s.json) {",
        '            Err(_) => format!("rejected\\t\\t{}", (s.accepts)(s.json).err().unwrap_or_default().replace(\'\\t\', " ")),',
        "            Ok(value) => {",
        "                let back = serde_json::to_value(&value).unwrap();",
        "                let same = back == serde_json::from_str::<serde_json::Value>(s.json).unwrap();",
        "                let got = pick(&value);",
        "                let others: Vec<&str> = samples.iter().filter(|o| o.variant == got && o.mode == s.mode)",
        "                    .filter(|o| (s.accepts)(o.json).is_ok()).map(|o| o.variant).collect();",
        '                format!("decoded\\t{}\\t{}\\t{}\\t{}", got, same, (s.accepts)(s.json).is_ok(), !others.is_empty())',
        "            }",
        "        };",
        '        writeln!(out, "{}\\t{}\\t{}\\t{}", union, s.variant, s.mode, line).unwrap();',
        "    }",
        "}",
        "",
    ]
    calls = []
    for index, union in enumerate(plan["unions"]):
        name = union["union"]
        arms = "".join(f'models::{name}::{v["name"]}(_) => "{v["name"]}", ' for v in union["variants"])
        out.append(f"fn pick{index}(v: &models::{name}) -> &'static str {{ match v {{ {arms}}} }}")
        samples = []
        for variant in union["variants"]:
            for mode, sampler in samplers.items():
                value = sampler.sample(variant["branch"]) if variant.get("branch") else NO_SAMPLE
                if value is NO_SAMPLE:
                    if mode == "minimal":
                        no_samples.append((name, variant["name"]))
                    continue
                samples.append(
                    f'Sample {{ variant: "{variant["name"]}", mode: "{mode}", json: {raw(json.dumps(value))}, '
                    f'accepts: accepts::<{variant["payload"]}> }}'
                )
        calls.append(f'    check::<models::{name}>(&mut out, "{name}", pick{index}, &[{", ".join(samples)}]);')
        if union.get("nullable"):
            calls.append(
                f'    writeln!(out, "{name}\\tnull\\tminimal\\tdecoded\\tnull\\t{{}}\\ttrue\\ttrue", '
                f'serde_json::from_str::<Option<models::{name}>>("null").is_ok()).unwrap();'
            )
    out += [
        "",
        "#[test]",
        "fn union_samples() {",
        "    let mut out = String::new();",
        *calls,
        '    std::fs::write(std::env::var("UNION_SAMPLES_OUT").unwrap(), out).unwrap();',
        "}",
        "",
    ]
    return "\n".join(out), no_samples


def classify(fields: list[str]) -> tuple[str, str]:
    union, variant, mode, status, *rest = fields
    if status == "rejected":
        return "rejected", rest[1] if len(rest) > 1 else ""
    got, same, accepts, mutual = rest
    if got == "null":
        return ("pass" if same == "true" else "rejected"), ""
    if got == variant:
        return ("pass" if same == "true" else "lossy"), ""
    if accepts != "true":
        return "rejected", got
    return ("ambiguous" if mutual == "true" else "order"), got


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("crate", type=Path)
    parser.add_argument("--plan", type=Path, default=None)
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--keep-test", action="store_true")
    args = parser.parse_args()
    plan_path = args.plan or args.crate / "target/reports/union_plan.json"
    plan = json.loads(plan_path.read_text())
    package = re.search(r'^name\s*=\s*"([^"]+)"', (args.crate / "Cargo.toml").read_text(), re.MULTILINE).group(1)
    source, no_samples = test_source(package.replace("-", "_"), plan)
    test = args.crate / "tests/union_samples.rs"
    test.parent.mkdir(exist_ok=True)
    test.write_text(source)
    results = args.crate / "target/reports/union_samples.tsv"
    results.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, UNION_SAMPLES_OUT=str(results.resolve()), RUST_MIN_STACK=str(64 * 1024 * 1024))
    try:
        subprocess.run(["cargo", "test", "--test", "union_samples"], cwd=args.crate, env=env, check=True)
    finally:
        if not args.keep_test:
            test.unlink()

    by_mode: dict[str, Counter] = {"minimal": Counter(), "full": Counter()}
    listed: dict[str, list] = {"minimal": [], "full": []}
    for line in results.read_text().splitlines():
        fields = line.split("\t")
        kind, got = classify(fields)
        by_mode[fields[2]][kind] += 1
        if kind != "pass":
            listed[fields[2]].append({"union": fields[0], "variant": fields[1], "class": kind, "landed_in": got})
    report = {
        "unions": len(plan["unions"]),
        "variants": sum(len(u["variants"]) for u in plan["unions"]),
        "minimal": dict(by_mode["minimal"]),
        "full": dict(by_mode["full"]),
        "no_sample": [{"union": u, "variant": v} for u, v in no_samples],
        "failures": listed,
    }
    if args.report:
        args.report.write_text(json.dumps(report, indent=1))
    print(json.dumps({k: report[k] for k in ("unions", "variants", "minimal", "full")}, indent=1))
    print("no sample:", len(no_samples))
    for mode in ("minimal", "full"):
        for failure in listed[mode]:
            if mode == "minimal" or failure["class"] != "lossy":
                print(f'{mode}\t{failure["class"]}\t{failure["union"]}::{failure["variant"]}\t{failure["landed_in"]}')
    bad = [f for f in listed["minimal"] if f["class"] in ("order", "rejected")]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
