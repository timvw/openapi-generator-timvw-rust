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

With unionDispatch the plan also says what selects each variant of a tagged union, and this checks more:

  per tag    a minimal and a full sample for every value of every variant, tag set to that value. They must decode to
             that variant ("pass"), or to an earlier variant the value also selects ("shared"), and serialize back with
             no key twice in one object. Anything else ("wrong", "rejected", "lossy", "repeated") is listed, and all but
             lossy make the exit status 1.
  errors     an unknown tag, a tag of the wrong kind, a missing tag where every variant requires it, and input that is
             no object must each be an error that names the union and the tag. A sample without one of its required
             fields must fail with serde's message for that field, not "data did not match any variant".

--bytes <file> writes every sample and what it serializes to, so two crates can be compared: run it with the plan of the
dispatching crate on both (--no-error-checks on the one that does not dispatch), then diff the files.
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


    def resolve(self, schema):
        seen = set()
        while isinstance(schema, dict) and schema.get("$ref"):
            name = schema["$ref"].rsplit("/", 1)[-1]
            if name in seen:
                return None
            seen.add(name)
            schema = self.schemas.get(name)
        return schema

    def arms(self, schema):
        for keyword in ("oneOf", "anyOf"):
            if schema.get(keyword):
                return [b for b in schema[keyword] if b.get("type") != "null"]
        if len(schema.get("allOf") or []) == 1:
            only = self.resolve(schema["allOf"][0])
            return self.arms(only) if only else None
        return None

    def field(self, schema, name):
        """The schema of a property, through allOf."""
        schema = self.resolve(schema)
        if not isinstance(schema, dict):
            return None
        found = (schema.get("properties") or {}).get(name)
        for part in schema.get("allOf") or []:
            found = found or self.field(part, name)
        return found

    def allows(self, schema, tag, value, depth=0):
        """Whether this branch, through its arms if it is a union, has that value for the tag."""
        schema = self.resolve(schema)
        if not isinstance(schema, dict) or depth > MAX_DEPTH:
            return False
        arms = self.arms(schema)
        if arms is not None:
            return any(self.allows(arm, tag, value, depth + 1) for arm in arms)
        field = self.resolve(self.field(schema, tag))
        while isinstance(field, dict) and self.arms(field) and not field.get("enum"):
            arms = self.arms(field)
            field = self.resolve(arms[0]) if len(arms) == 1 else None
        if not isinstance(field, dict):
            return False
        return value == field.get("const") or value in (field.get("enum") or [])

    def tagged(self, schema, tag, value, depth=0):
        """A sample of this branch with the tag set to that value, taking the arm that has it."""
        resolved = self.resolve(schema)
        if not isinstance(resolved, dict) or depth > MAX_DEPTH:
            return NO_SAMPLE
        arms = self.arms(resolved)
        if arms is not None:
            for arm in arms:
                if self.allows(arm, tag, value):
                    return self.tagged(arm, tag, value, depth + 1)
        sample = self.sample(schema)
        if isinstance(sample, dict):
            sample[tag] = value
        return sample


def raw(text: str) -> str:
    hashes = "#" * 5
    return f'r{hashes}"{text}"{hashes}'


def tag_samples(union: dict, samplers: dict) -> list:
    """A sample per value, per variant and per mode, for a union that dispatches."""
    dispatch = union["dispatch"]
    branches = {v["name"]: v for v in union["variants"]}
    samples = []
    for value, variants in dispatch["values"].items():
        selected = ", ".join(json.dumps(v) for v in variants)
        for name in variants:
            for mode, sampler in samplers.items():
                sample = sampler.tagged(branches[name]["branch"], dispatch["tag"], value)
                if sample is not NO_SAMPLE:
                    samples.append(
                        f"TagSample {{ variant: {json.dumps(name)}, value: {raw(value)}, mode: {json.dumps(mode)}, "
                        f"json: {raw(json.dumps(sample))}, selected: &[{selected}] }}"
                    )
    return samples


def error_checks(union: dict, samplers: dict) -> list:
    """Calls of expect_error for a union that dispatches, as (case, json, needles)."""
    name = union["union"]
    dispatch = union["dispatch"]
    tag = dispatch["tag"]
    checks = [
        ("unknown", json.dumps({tag: "no_such_value"}), [name, tag, "no_such_value"]),
        ("wrong-kind", json.dumps({tag: 5}), [name, tag, "must be a string"]),
    ]
    if not dispatch["missing"]:
        checks.append(("missing", "{}", [name, tag, "missing or null"]))
    if not dispatch["nonObject"]:
        checks.append(("not-object", "5", [name, tag, "expected an object"]))
    branches = {v["name"]: v for v in union["variants"]}
    for value, variants in dispatch["values"].items():
        if len(variants) == 1:
            sample = samplers["minimal"].tagged(branches[variants[0]]["branch"], tag, value)
            field = next((k for k in sample if k != tag), None) if isinstance(sample, dict) else None
            if field is not None:
                del sample[field]
                checks.append(("variant-error", json.dumps(sample), [variants[0], "missing", field]))
    return checks


def test_source(package: str, plan: dict, error_tests: bool = True) -> tuple[str, list]:
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
        '                bytes(union, s.variant, s.mode, s.json, &value);',
        '                format!("decoded\\t{}\\t{}\\t{}\\t{}", got, same, (s.accepts)(s.json).is_ok(), !others.is_empty())',
        "            }",
        "        };",
        '        writeln!(out, "{}\\t{}\\t{}\\t{}", union, s.variant, s.mode, line).unwrap();',
        "    }",
        "}",
        "",
        "/// Counts the keys that appear twice in one object, anywhere in a document.",
        "struct Repeated(usize);",
        "impl<'de> serde::Deserialize<'de> for Repeated {",
        "    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {",
        "        struct V;",
        "        impl<'de> serde::de::Visitor<'de> for V {",
        "            type Value = Repeated;",
        '            fn expecting(&self, f: &mut std::fmt::Formatter<\'_>) -> std::fmt::Result { f.write_str("JSON") }',
        "            fn visit_map<A: serde::de::MapAccess<'de>>(self, mut m: A) -> Result<Repeated, A::Error> {",
        "                let (mut keys, mut n) = (std::collections::HashSet::new(), 0);",
        "                while let Some(k) = m.next_key::<String>()? { n += usize::from(!keys.insert(k)) + m.next_value::<Repeated>()?.0; }",
        "                Ok(Repeated(n))",
        "            }",
        "            fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut s: A) -> Result<Repeated, A::Error> {",
        "                let mut n = 0;",
        "                while let Some(i) = s.next_element::<Repeated>()? { n += i.0; }",
        "                Ok(Repeated(n))",
        "            }",
        "            fn visit_bool<E>(self, _: bool) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "            fn visit_u64<E>(self, _: u64) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "            fn visit_i64<E>(self, _: i64) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "            fn visit_f64<E>(self, _: f64) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "            fn visit_str<E>(self, _: &str) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "            fn visit_unit<E>(self) -> Result<Repeated, E> { Ok(Repeated(0)) }",
        "        }",
        "        d.deserialize_any(V)",
        "    }",
        "}",
        "",
        "struct TagSample { variant: &'static str, value: &'static str, mode: &'static str, json: &'static str, selected: &'static [&'static str] }",
        "",
        "fn check_tags<U: DeserializeOwned + Serialize>(out: &mut String, union: &str, pick: fn(&U) -> &'static str, samples: &[TagSample]) {",
        "    for s in samples {",
        "        let line = match serde_json::from_str::<U>(s.json) {",
        '            Err(e) => format!("rejected\\t\\t{}", e.to_string().replace(\'\\t\', " ")),',
        "            Ok(value) => {",
        "                let got = pick(&value);",
        "                let text = serde_json::to_string(&value).unwrap();",
        "                let repeated = serde_json::from_str::<Repeated>(&text).unwrap().0;",
        "                let same = serde_json::from_str::<serde_json::Value>(&text).unwrap() == serde_json::from_str::<serde_json::Value>(s.json).unwrap();",
        "                bytes(union, s.variant, s.value, s.json, &value);",
        '                let class = if repeated > 0 { "repeated" } else if !s.selected.contains(&got) { "wrong" } else if got != s.variant { "shared" } else if !same { "lossy" } else { "pass" };',
        '                format!("{}\\t{}\\t", class, got)',
        "            }",
        "        };",
        '        writeln!(out, "{}\\t{}\\t{}\\t{}\\t{}", union, s.variant, s.value, s.mode, line).unwrap();',
        "    }",
        "}",
        "",
        "fn expect_error<U: DeserializeOwned>(out: &mut String, union: &str, case: &str, json: &str, needles: &[&str]) {",
        "    let (class, message) = match serde_json::from_str::<U>(json) {",
        '        Ok(_) => ("accepted", String::new()),',
        '        Err(e) => { let m = e.to_string(); (if needles.iter().all(|n| m.contains(n)) && !m.contains("did not match any variant") { "pass" } else { "unclear" }, m) }',
        "    };",
        '    writeln!(out, "{}\\t{}\\t{}\\t{}\\t{}", union, case, class, json.replace(\'\\t\', " "), message.replace(\'\\t\', " ")).unwrap();',
        "}",
        "",
        "/// One line per sample with what it serializes to, for comparing two crates.",
        "fn bytes<U: Serialize>(union: &str, variant: &str, mode: &str, json: &str, value: &U) {",
        "    use std::io::Write as _;",
        '    if let Ok(path) = std::env::var("UNION_SAMPLES_BYTES") {',
        "        let mut file = std::fs::OpenOptions::new().create(true).append(true).open(path).unwrap();",
        "        // One write per line: the tests run in parallel.",
        '        let line = format!("{}\\t{}\\t{}\\t{}\\t{}\\n", union, variant, mode, json, serde_json::to_string(value).unwrap());',
        "        file.write_all(line.as_bytes()).unwrap();",
        "    }",
        "}",
        "",
    ]
    calls = []
    tag_calls = []
    error_calls = []
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
        if union.get("dispatch"):
            tagged = tag_samples(union, samplers)
            tag_calls.append(f'    check_tags::<models::{name}>(&mut out, "{name}", pick{index}, &[{", ".join(tagged)}]);')
            if error_tests:
                for case, sample, needles in error_checks(union, samplers):
                    wanted = ", ".join(raw(n) for n in needles)
                    error_calls.append(
                        f'    expect_error::<models::{name}>(&mut out, "{name}", "{case}", {raw(sample)}, &[{wanted}]);'
                    )
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
        "#[test]",
        "fn union_tags() {",
        "    let mut out = String::new();",
        *tag_calls,
        '    std::fs::write(std::env::var("UNION_SAMPLES_TAGS").unwrap(), out).unwrap();',
        "}",
        "",
        "#[test]",
        "fn union_errors() {",
        "    let mut out = String::new();",
        *error_calls,
        '    std::fs::write(std::env::var("UNION_SAMPLES_ERRORS").unwrap(), out).unwrap();',
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
    parser.add_argument("--bytes", type=Path, default=None)
    parser.add_argument("--no-error-checks", action="store_true")
    args = parser.parse_args()
    plan_path = args.plan or args.crate / "target/reports/union_plan.json"
    plan = json.loads(plan_path.read_text())
    package = re.search(r'^name\s*=\s*"([^"]+)"', (args.crate / "Cargo.toml").read_text(), re.MULTILINE).group(1)
    source, no_samples = test_source(package.replace("-", "_"), plan, not args.no_error_checks)
    test = args.crate / "tests/union_samples.rs"
    test.parent.mkdir(exist_ok=True)
    test.write_text(source)
    results = args.crate / "target/reports/union_samples.tsv"
    results.parent.mkdir(parents=True, exist_ok=True)
    tag_results = args.crate / "target/reports/union_tags.tsv"
    error_results = args.crate / "target/reports/union_errors.tsv"
    env = dict(os.environ, UNION_SAMPLES_OUT=str(results.resolve()), UNION_SAMPLES_TAGS=str(tag_results.resolve()),
               UNION_SAMPLES_ERRORS=str(error_results.resolve()), RUST_MIN_STACK=str(64 * 1024 * 1024))
    if args.bytes:
        args.bytes.unlink(missing_ok=True)
        env["UNION_SAMPLES_BYTES"] = str(args.bytes.resolve())
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
    tag_counts: Counter = Counter()
    tag_listed = []
    for line in tag_results.read_text().splitlines():
        union, variant, value, mode, kind, got, message = line.split("\t")
        tag_counts[f"{mode} {kind}"] += 1
        if kind not in ("pass", "shared"):
            tag_listed.append({"union": union, "variant": variant, "value": value, "mode": mode, "class": kind,
                               "landed_in": got, "message": message})
    error_counts: Counter = Counter()
    error_listed = []
    for line in error_results.read_text().splitlines():
        union, case, kind, sample, message = line.split("\t")
        error_counts[f"{case} {kind}"] += 1
        if kind != "pass":
            error_listed.append({"union": union, "case": case, "class": kind, "json": sample, "message": message})
    dispatching = [u for u in plan["unions"] if u.get("dispatch")]
    report = {
        "unions": len(plan["unions"]),
        "variants": sum(len(u["variants"]) for u in plan["unions"]),
        "minimal": dict(by_mode["minimal"]),
        "full": dict(by_mode["full"]),
        "tagged": len(dispatching),
        "tagged_by_discriminator": sum(u["dispatch"]["source"] == "discriminator" for u in dispatching),
        "tagged_implicitly": sum(u["dispatch"]["source"] == "implicit" for u in dispatching),
        "value_on_two_variants": sum(any(len(v) > 1 for v in u["dispatch"]["values"].values()) for u in dispatching),
        "tag_may_be_missing": sum(bool(u["dispatch"]["missing"]) for u in dispatching),
        "tag_samples": dict(tag_counts),
        "error_checks": dict(error_counts),
        "no_sample": [{"union": u, "variant": v} for u, v in no_samples],
        "failures": listed,
        "tag_failures": tag_listed,
        "error_failures": error_listed,
    }
    if args.report:
        args.report.write_text(json.dumps(report, indent=1))
    print(json.dumps({k: report[k] for k in report if k not in ("no_sample", "failures", "tag_failures", "error_failures")},
                     indent=1))
    print("no sample:", len(no_samples))
    for mode in ("minimal", "full"):
        for failure in listed[mode]:
            if mode == "minimal" or failure["class"] != "lossy":
                print(f'{mode}\t{failure["class"]}\t{failure["union"]}::{failure["variant"]}\t{failure["landed_in"]}')
    for failure in tag_listed:
        print(f'tag\t{failure["class"]}\t{failure["union"]}::{failure["variant"]}\t{failure["value"]}\t{failure["mode"]}'
              f'\t{failure["landed_in"]}\t{failure["message"]}')
    for failure in error_listed:
        print(f'error\t{failure["class"]}\t{failure["union"]}\t{failure["case"]}\t{failure["json"]}\t{failure["message"]}')
    bad = [f for f in listed["minimal"] if f["class"] in ("order", "rejected")]
    bad += [f for f in tag_listed if f["class"] != "lossy"]
    bad += error_listed
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
