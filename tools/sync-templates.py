#!/usr/bin/env python3
"""Rebuild the template overrides under src/main/resources/timvw-rust/ from the stock templates.

The generator keeps one override set per supported openapi-generator minor version
(``og7.15.0``, ``og7.25.0``, ``og7.27.0``): every file in a set starts as a verbatim copy of the stock
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
# Milestone 3.2. A struct derives Default per the x-rust-derive-default flag (DefaultRules: always, or with
# defaultOnlyWhereValid only without required fields).
STRUCT_DERIVE_BON = (
    "#[derive(Clone, {{#vendorExtensions.x-rust-derive-default}}Default, {{/vendorExtensions.x-rust-derive-default}}"
    "Debug, PartialEq, Serialize, Deserialize"
    "{{#bonBuilders}}, bon::Builder{{/bonBuilders}})]\npub struct {{{classname}}} {"
)

# A `///` line whose whole content is mustache output: the rustdoc lambda wraps bare URLs in <...>.
DOC_LINE = re.compile(r"^(?P<indent>\s*)/// (?P<body>\{\{\{.*)$", re.MULTILINE)


def replace_exactly(text: str, old: str, new: str, count: int, what: str) -> str:
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{what}: expected {count} occurrence(s) of anchor, found {found}")
    return text.replace(old, new)


# openapi-generator 7.25 JSON-encodes every non-primitive optional multipart text part, so a
# referenced string enum arrives as "\"verbose_json\"" instead of verbose_json. Enums have a Display
# impl that yields the wire value; use it. 7.15 renders every text part through Display and has
# no such branch, so the edit is skipped where the anchor is absent.
MULTIPART_JSON_TEXT = (
    "    {{^isPrimitiveType}}\n"
    '        multipart_form = multipart_form.text("{{{baseName}}}", serde_json::to_string(&param_value)?);\n'
    "    {{/isPrimitiveType}}\n"
)
MULTIPART_ENUM_TEXT = (
    "    {{^isPrimitiveType}}\n"
    "    {{#isEnumRef}}\n"
    '        multipart_form = multipart_form.text("{{{baseName}}}", param_value.to_string());\n'
    "    {{/isEnumRef}}\n"
    "    {{^isEnumRef}}\n"
    # modelUnions: a union's Display prints a string variant as the string, and anything else as JSON.
    "    {{#vendorExtensions.x-rust-union-text}}\n"
    '        multipart_form = multipart_form.text("{{{baseName}}}", param_value.to_string());\n'
    "    {{/vendorExtensions.x-rust-union-text}}\n"
    "    {{^vendorExtensions.x-rust-union-text}}\n"
    '        multipart_form = multipart_form.text("{{{baseName}}}", serde_json::to_string(&param_value)?);\n'
    "    {{/vendorExtensions.x-rust-union-text}}\n"
    "    {{/isEnumRef}}\n"
    "    {{/isPrimitiveType}}\n"
)


# A required nullable model field is built with `if let Some(x) = f {Some(Box::new(x))} else {None}`;
# `f.map(Box::new)` is the same value and is what clippy's manual_map asks for.
CTOR_IF_LET = "if let Some(x) = {{{name}}} {Some(Box::new(x))} else {None}"
CTOR_MAP = "{{{name}}}.map(Box::new)"


# modelUnions: a multipart parameter whose union has file variants matches on the union (x-multipart-file-union, set by
# MultipartRules.fileUnion). A file goes through the helper as an ordinary file parameter does, an array of files sends one
# part per file, and any other variant is a schema value: a string stays plain text, an object is one JSON part.
MULTIPART_FILE_UNION_ARMS = """    {{#vendorExtensions.x-multipart-file-union}}
        {{{pattern}}}(value) => {
            {{#isFilePart}}
            multipart_form = multipart_helper::add_file_to_form(multipart_form, value, "{{{baseName}}}")?;
            {{/isFilePart}}
            {{#isFileParts}}
            for path in value {
                multipart_form = multipart_helper::add_file_to_form(multipart_form, path, "{{{partName}}}")?;
            }
            {{/isFileParts}}
            {{#isValuePart}}
            multipart_form = multipart_helper::add_schema_value_to_form(
                multipart_form,
                "{{{baseName}}}",
                serde_json::to_value(value)?,
                {{#vendorExtensions.x-multipart-content-type}}Some("{{{.}}}"){{/vendorExtensions.x-multipart-content-type}}{{^vendorExtensions.x-multipart-content-type}}None{{/vendorExtensions.x-multipart-content-type}},
            )?;
            {{/isValuePart}}
        }
    {{/vendorExtensions.x-multipart-file-union}}
"""
MULTIPART_FILE_UNION = f"""    {{{{#vendorExtensions.x-multipart-file-union-required}}}}
    match &{{{{{{vendorExtensions.x-rust-param-identifier}}}}}} {{
{MULTIPART_FILE_UNION_ARMS}    }}
    {{{{/vendorExtensions.x-multipart-file-union-required}}}}
    {{{{#vendorExtensions.x-multipart-file-union-nullable}}}}
    match &{{{{{{vendorExtensions.x-rust-param-identifier}}}}}} {{
        None => {{ multipart_form = multipart_form.text("{{{{{{baseName}}}}}}", ""); }},
        Some(param_value) => match param_value {{
{MULTIPART_FILE_UNION_ARMS}        }},
    }}
    {{{{/vendorExtensions.x-multipart-file-union-nullable}}}}
    {{{{#vendorExtensions.x-multipart-file-union-optional}}}}
    if let Some(ref param_value) = {{{{{{vendorExtensions.x-rust-param-identifier}}}}}} {{
        match param_value {{
{MULTIPART_FILE_UNION_ARMS}        }}
    }}
    {{{{/vendorExtensions.x-multipart-file-union-optional}}}}
"""


# Milestone 2.5. multipartHelper: multipart bodies go through src/apis/multipart_helper.rs. Files are read into a
# part named after the file; an optional exploded array sends one part per item and an optional object one JSON part
# (both as application/json), and a scalar with an explicit encoding contentType is sent with that media type. The
# generator sets the x-multipart-* vendor extensions per form parameter (TimvwRustCodegen.classifyMultipart).
MULTIPART_OPEN = "    {{#isMultipart}}\n    {{#hasFormParams}}\n"
MULTIPART_HELPER_BLOCK = """{{#multipartHelper}}
    {{#isMultipart}}
    {{#hasFormParams}}
    let mut multipart_form = reqwest::multipart::Form::new();
    {{#formParams}}
    {{#isFile}}
    {{#required}}
    {{#isArray}}
    for value in &{{{vendorExtensions.x-rust-param-identifier}}} {
        multipart_form = multipart_helper::add_file_to_form(multipart_form, value, "{{{baseName}}}")?;
    }
    {{/isArray}}
    {{^isArray}}
    // Add file to multipart form
    multipart_form = multipart_helper::add_file_to_form(multipart_form, &{{{vendorExtensions.x-rust-param-identifier}}}, "{{{baseName}}}")?;
    {{/isArray}}
    {{/required}}
    {{^required}}
    if let Some(ref param_value) = {{{vendorExtensions.x-rust-param-identifier}}} {
        {{#isArray}}
        for value in param_value {
            multipart_form = multipart_helper::add_file_to_form(multipart_form, value, "{{{baseName}}}")?;
        }
        {{/isArray}}
        {{^isArray}}
        // Add file to multipart form
        multipart_form = multipart_helper::add_file_to_form(multipart_form, param_value, "{{{baseName}}}")?;
        {{/isArray}}
    }
    {{/required}}
    {{/isFile}}
    {{^isFile}}
    {{#vendorExtensions.x-multipart-json-items}}
    if let Some(param_value) = {{{vendorExtensions.x-rust-param-identifier}}} {
        for item in param_value {
            multipart_form = multipart_helper::add_schema_value_to_form(
                multipart_form,
                "{{{baseName}}}",
                serde_json::to_value(item)?,
                {{#vendorExtensions.x-multipart-content-type}}Some("{{{.}}}"){{/vendorExtensions.x-multipart-content-type}}{{^vendorExtensions.x-multipart-content-type}}None{{/vendorExtensions.x-multipart-content-type}},
            )?;
        }
    }
    {{/vendorExtensions.x-multipart-json-items}}
    {{#vendorExtensions.x-multipart-json-value}}
    if let Some(param_value) = {{{vendorExtensions.x-rust-param-identifier}}} {
        multipart_form = multipart_helper::add_schema_value_to_form(
            multipart_form,
            "{{{baseName}}}",
            serde_json::to_value(param_value)?,
            {{#vendorExtensions.x-multipart-content-type}}Some("{{{.}}}"){{/vendorExtensions.x-multipart-content-type}}{{^vendorExtensions.x-multipart-content-type}}None{{/vendorExtensions.x-multipart-content-type}},
        )?;
    }
    {{/vendorExtensions.x-multipart-json-value}}
    {{#vendorExtensions.x-multipart-text-media-type}}
    {{#required}}
    multipart_form = multipart_helper::add_schema_value_to_form(
        multipart_form,
        "{{{baseName}}}",
        serde_json::Value::String({{{vendorExtensions.x-rust-param-identifier}}}.to_string()),
        Some("{{{vendorExtensions.x-multipart-content-type}}}"),
    )?;
    {{/required}}
    {{^required}}
    if let Some(param_value) = {{{vendorExtensions.x-rust-param-identifier}}} {
        multipart_form = multipart_helper::add_schema_value_to_form(
            multipart_form,
            "{{{baseName}}}",
            serde_json::Value::String(param_value.to_string()),
            Some("{{{vendorExtensions.x-multipart-content-type}}}"),
        )?;
    }
    {{/required}}
    {{/vendorExtensions.x-multipart-text-media-type}}
{{FILE_UNION}}    {{#vendorExtensions.x-multipart-stock}}
{{STOCK_NON_FILE}}
    {{/vendorExtensions.x-multipart-stock}}
    {{/isFile}}
    {{/formParams}}
    req_builder = req_builder.multipart(multipart_form);
    {{/hasFormParams}}
    {{/isMultipart}}
{{/multipartHelper}}
{{^multipartHelper}}
"""
MULTIPART_CLOSE = "    {{/isMultipart}}\n"
NON_FILE_OPEN = "    {{^isFile}}\n"
NON_FILE_CLOSE = "    {{/isFile}}\n"

# The stock API template prefixes models:: to a non-container model type, but a multipart form parameter's dataType
# already carries it (models::models::X). The generator flags such parameters.
MODELS_PREFIX = "{{^isContainer}}models::{{/isContainer}}"
MODELS_PREFIX_ONCE = (
    "{{^isContainer}}{{^vendorExtensions.x-rust-type-qualified}}models::{{/vendorExtensions.x-rust-type-qualified}}"
    "{{/isContainer}}"
)

# The API module imports the helper where an operation uses it.
API_SUPER_IMPORT = "use super::{Error, configuration, ContentType};\n"
API_SUPER_IMPORT_HELPER = (
    "use super::{Error, configuration, ContentType{{#multipartHelperImport}}, multipart_helper{{/multipartHelperImport}}};\n"
)

# mod.rs declares the helper right after the configuration module.
API_MOD_HELPER = "{{#multipartHelper}}\n\n// Internal helper module for multipart file handling\npub(crate) mod multipart_helper;\n\n{{/multipartHelper}}"


def multipart_block(text: str, what: str) -> str:
    """Puts the helper-based multipart block in front of the stock one, which renders when multipartHelper is off."""
    start = text.find(MULTIPART_OPEN)
    if start < 0 or text.count(MULTIPART_OPEN) != 1:
        raise SystemExit(f"{what}: expected one multipart form block")
    end = text.find(MULTIPART_CLOSE, start)
    if end < 0:
        raise SystemExit(f"{what}: multipart form block not closed")
    end += len(MULTIPART_CLOSE)
    stock = text[start:end]
    nf_start = stock.find(NON_FILE_OPEN)
    nf_end = stock.find(NON_FILE_CLOSE, nf_start)
    if nf_start < 0 or nf_end < 0:
        raise SystemExit(f"{what}: no non-file branch in the multipart block")
    stock_non_file = stock[nf_start + len(NON_FILE_OPEN):nf_end].rstrip("\n")
    helper = MULTIPART_HELPER_BLOCK.replace("{{STOCK_NON_FILE}}", stock_non_file).replace(
        "{{FILE_UNION}}", MULTIPART_FILE_UNION
    )
    return text[:start] + helper + stock + "{{/multipartHelper}}\n" + text[end:]


# Milestone 2.6. boxResponseError: Error::ResponseError holds Box<ResponseContent<T>> (clippy result_large_err:
# a response entity can be large, and every Result<_, Error<T>> carries the variant).
RESPONSE_ERROR_VARIANT = "    ResponseError(ResponseContent<T>),\n"
RESPONSE_ERROR_VARIANT_BOXED = (
    "    ResponseError({{#boxResponseError}}Box<{{/boxResponseError}}ResponseContent<T>"
    "{{#boxResponseError}}>{{/boxResponseError}}),\n"
)
RESPONSE_ERROR_CTOR = "Err(Error::ResponseError(ResponseContent { status, content, entity }))"
RESPONSE_ERROR_CTOR_BOXED = (
    "Err(Error::ResponseError({{#boxResponseError}}Box::new({{/boxResponseError}}"
    "ResponseContent { status, content, entity }{{#boxResponseError}}){{/boxResponseError}}))"
)

# apiLintAllows: API modules and configuration.rs start with #![allow(<lints>)].
PARTIAL_HEADER = "{{>partial_header}}\n"
PARTIAL_HEADER_ALLOWS = "{{>partial_header}}\n{{#apiLintAllows}}\n#![allow({{{.}}})]\n{{/apiLintAllows}}\n"

# userAgentFromPackage: the default user agent is <packageName>/<crate version> (CARGO_PKG_VERSION at build time)
# instead of OpenAPI-Generator/<spec version>/rust.
USER_AGENT_STOCK = (
    'user_agent: {{#httpUserAgent}}Some("{{{.}}}".to_owned()){{/httpUserAgent}}'
    '{{^httpUserAgent}}Some("OpenAPI-Generator/{{{version}}}/rust".to_owned()){{/httpUserAgent}},'
)
USER_AGENT_PACKAGE = (
    'user_agent: {{#httpUserAgent}}Some("{{{.}}}".to_owned()){{/httpUserAgent}}{{^httpUserAgent}}'
    '{{#userAgentFromPackage}}Some(concat!("{{{packageName}}}/", env!("CARGO_PKG_VERSION")).to_owned())'
    '{{/userAgentFromPackage}}{{^userAgentFromPackage}}Some("OpenAPI-Generator/{{{version}}}/rust".to_owned())'
    '{{/userAgentFromPackage}}{{/httpUserAgent}},'
)


# mod.rs declares the webhook API module (webhookModule): the stock template lists only apiInfo.apis.
API_MOD_CONFIGURATION = "pub mod configuration;\n"
API_MOD_WEBHOOKS = "pub mod configuration;\n{{#webhookModules}}\npub mod {{{.}}};\n{{/webhookModules}}\n"

# default_api re-exports the webhook module when the generator sets webhookReexport (webhookModuleReexport).
WEBHOOK_REEXPORT = "{{#webhookReexport}}\npub use super::{{{.}}}::*;\n{{/webhookReexport}}\n"


def replace_at_most_once(text: str, old: str, new: str, what: str) -> str:
    found = text.count(old)
    if found > 1:
        raise SystemExit(f"{what}: expected at most 1 occurrence of anchor, found {found}")
    return text.replace(old, new)


def wrap_doc_lines(text: str, what: str) -> str:
    text, n = DOC_LINE.subn(r"\g<indent>/// {{#rustdoc}}\g<body>{{/rustdoc}}", text)
    if n == 0:
        raise SystemExit(f"{what}: no doc lines found to wrap")
    return text


# Milestone 3.2. noStructDisplay: a struct has no Display, so a parameter of struct type (x-rust-struct-json) or an array
# of structs (x-rust-struct-json-items), which the generator marks, goes through serde_json::to_string where the stock
# template calls to_string(). Struct Display was serde_json::to_string, so the bytes stay the same. The other branches
# of these templates are either not reachable for a struct or already serialize it as JSON.
STRUCT_JSON = "vendorExtensions.x-rust-struct-json"
STRUCT_JSON_ITEMS = "vendorExtensions.x-rust-struct-json-items"
PARAM_ID = "{{{vendorExtensions.x-rust-param-identifier}}}"
PATH_UNWRAP = "{{^required}}.unwrap(){{/required}}{{#required}}{{#isNullable}}.unwrap(){{/isNullable}}{{/required}}"
PATH_REST = (
    '{{#isArray}}.join(",").as_ref(){{/isArray}}'
    "{{^isString}}{{^isUuid}}{{^isPrimitiveType}}{{^isContainer}}.to_string()"
    "{{/isContainer}}{{/isPrimitiveType}}{{/isUuid}}{{/isString}}"
)
JSON_ITEMS = '.into_iter().map(|p| serde_json::to_string(&p)).collect::<Result<Vec<String>, _>>()?.join(",")'


def when(flag: str, json: str, stock: str) -> str:
    return f"{{{{#{flag}}}}}{json}{{{{/{flag}}}}}{{{{^{flag}}}}}{stock}{{{{/{flag}}}}}"


def struct_json_sites(t: str, w: str) -> str:
    # A path parameter.
    stock = PARAM_ID + PATH_UNWRAP + PATH_REST
    t = replace_exactly(t, stock, when(STRUCT_JSON, f"serde_json::to_string(&{PARAM_ID}{PATH_UNWRAP})?", stock), 1, w)
    for value in (PARAM_ID, "param_value"):
        # An array in the query, as repeated parameters and as one joined parameter.
        pair = '("{{{baseName}}}".to_owned(), p.to_string())'
        tuples = "Vec<(std::string::String, std::string::String)>"
        stock = f"req_builder.query(&{value}.into_iter().map(|p| {pair}).collect::<{tuples}>())"
        json = (
            f'req_builder.query(&{value}.into_iter().map(|p| serde_json::to_string(&p)'
            f'.map(|value| ("{{{{{{baseName}}}}}}".to_owned(), value))).collect::<Result<{tuples}, _>>()?)'
        )
        t = replace_exactly(t, stock, when(STRUCT_JSON_ITEMS, json, stock), 1, w)
        stock = f'&{value}.into_iter().map(|p| p.to_string()).collect::<Vec<String>>().join(",").to_string()'
        t = replace_exactly(t, stock, when(STRUCT_JSON_ITEMS, f"&{value}{JSON_ITEMS}", stock), 1, w)
        # A form or multipart text part, single or joined.
        stock = (
            f'{value}{{{{#isArray}}}}.into_iter().map(|p| p.to_string()).collect::<Vec<String>>().join(",")'
            "{{/isArray}}.to_string()"
        )
        if t.count(stock) < 3:
            raise SystemExit(f"{w}: expected at least 3 form text parts for {value}, found {t.count(stock)}")
        t = t.replace(stock, when(STRUCT_JSON_ITEMS, value + JSON_ITEMS,
                                  when(STRUCT_JSON, f"serde_json::to_string(&{value})?", stock)))
    # A required query parameter.
    stock = f'&[("{{{{{{baseName}}}}}}", &{PARAM_ID}.to_string())]'
    t = replace_exactly(
        t, stock, when(STRUCT_JSON, f'&[("{{{{{{baseName}}}}}}", &serde_json::to_string(&{PARAM_ID})?)]', stock), 1, w
    )
    # A header.
    for value, sites in ((PARAM_ID, 1), ("param_value", 2)):
        stock = f'{value}{{{{#isArray}}}}.join(","){{{{/isArray}}}}.to_string()'
        t = replace_exactly(t, stock, when(STRUCT_JSON, f"serde_json::to_string(&{value})?", stock), sites, w)
    return t


def api_template(t: str, w: str) -> str:
    # The enum text-part edit first: the multipart helper block copies the (edited) stock non-file branch.
    t = replace_at_most_once(t, MULTIPART_JSON_TEXT, MULTIPART_ENUM_TEXT, w)
    t = multipart_block(t, w)
    t = replace_exactly(t, "pub {{#supportAsync}}async {{/supportAsync}}fn {{{operationId}}}(",
                        "pub {{#supportAsync}}async {{/supportAsync}}fn {{{operationId}}}(", 2, w)
    t = re.sub(
        r"^(?=pub \{\{#supportAsync\}\}async \{\{/supportAsync\}\}fn \{\{\{operationId\}\}\}\()",
        BON_BUILDER_FN,
        t,
        flags=re.MULTILINE,
    )
    t = wrap_doc_lines(t, w)
    t = t.rstrip("\n") + "\n" + WEBHOOK_REEXPORT
    t = replace_exactly(t, RESPONSE_ERROR_CTOR, RESPONSE_ERROR_CTOR_BOXED, 1, w)
    t = replace_exactly(t, PARTIAL_HEADER, PARTIAL_HEADER_ALLOWS, 1, w)
    t = replace_exactly(t, API_SUPER_IMPORT, API_SUPER_IMPORT_HELPER, 1, w)
    t = replace_exactly(t, MODELS_PREFIX, MODELS_PREFIX_ONCE, 2, w)
    t = struct_json_sites(t, w)
    return t


# Milestone 3.1. Every model reference is boxed unless the stock avoidBoxedModels flag is set (which timvw-rust
# rejects); the generator decides per property instead and sets x-rust-box (TimvwRustCodegen, BoxRules).
BOX_OPEN = "{{^avoidBoxedModels}}"
BOX_CLOSE = "{{/avoidBoxedModels}}"
PER_PROPERTY_BOX_OPEN = "{{#vendorExtensions.x-rust-box}}"
PER_PROPERTY_BOX_CLOSE = "{{/vendorExtensions.x-rust-box}}"


def per_property_box(text: str, what: str) -> str:
    opens, closes = text.count(BOX_OPEN), text.count(BOX_CLOSE)
    if opens == 0 or opens != closes or "{{#avoidBoxedModels}}" in text:
        raise SystemExit(f"{what}: expected matching {BOX_OPEN} sections, found {opens} open / {closes} close")
    return text.replace(BOX_OPEN, PER_PROPERTY_BOX_OPEN).replace(BOX_CLOSE, PER_PROPERTY_BOX_CLOSE)


# Milestone 3.2. defaultOnlyWhereValid: no enum implements Default, since no variant is the API's default. The stock
# template has four such impls: value enums, discriminated unions, untagged unions and property enums.
ENUM_IMPL_DEFAULT = re.compile(r"^impl Default for .*?^\}\n", re.MULTILINE | re.DOTALL)
ENUM_IMPL_DEFAULTS = 4


def gate_enum_defaults(text: str, what: str) -> str:
    text, n = ENUM_IMPL_DEFAULT.subn(
        lambda m: "{{^defaultOnlyWhereValid}}\n" + m.group() + "{{/defaultOnlyWhereValid}}\n", text
    )
    if n != ENUM_IMPL_DEFAULTS:
        raise SystemExit(f"{what}: expected {ENUM_IMPL_DEFAULTS} enum Default impls, found {n}")
    return text


# Milestone 3.3. modelUnions: a union (UnionRules sets x-rust-enum) renders from tools/static/model_union.mustache, in
# place of the stock discriminated and untagged enums, which stay as they are for the other models.
UNION_BLOCK_START = "{{!-- for schemas that have a discriminator --}}\n"
UNION_BLOCK_END = "{{!-- for properties that are of enum type --}}\n"
UNION_GATE_OPEN = "{{^vendorExtensions.x-rust-enum}}\n"
UNION_GATE_CLOSE = "{{/vendorExtensions.x-rust-enum}}\n"


def union_block(text: str, what: str) -> str:
    text = replace_exactly(
        text,
        UNION_BLOCK_START,
        (ROOT / "tools/static/model_union.mustache").read_text() + UNION_GATE_OPEN + UNION_BLOCK_START,
        1,
        what,
    )
    return replace_exactly(text, UNION_BLOCK_END, UNION_GATE_CLOSE + UNION_BLOCK_END, 1, what)


# Milestone 3.5. openEnums: the generator sets x-rust-open-enum on an enum a response can hold (model enums and inline
# property enums alike), and the enum gets a catch-all variant after the listed values. A string enum's serializes as a
# string no value has; an integer enum's as a number no value has. 7.15 renders an integer property enum as a string one.
OPEN_ENUM_OPEN = "{{#vendorExtensions.x-rust-open-enum}}\n"
OPEN_ENUM_CLOSE = "{{/vendorExtensions.x-rust-open-enum}}\n"
OPEN_ENUM_STRING_VARIANT = (
    OPEN_ENUM_OPEN
    + '    /// A value this client does not know. It is not kept: this serializes as `"{{{wire}}}"`, which the API does not list.\n'
    + '    #[serde(rename = "{{{wire}}}", other)]\n'
    + "    {{{name}}},\n"
    + OPEN_ENUM_CLOSE
)
OPEN_ENUM_INTEGER_VARIANT = (
    OPEN_ENUM_OPEN
    + "    /// A value this client does not know. It is not kept: this serializes as `i64::MAX`, which the API does not list.\n"
    + "    #[serde(other)]\n"
    + "    {{{name}}} = i64::MAX,\n"
    + OPEN_ENUM_CLOSE
)
OPEN_ENUM_DISPLAY_OPEN = "            {{#vendorExtensions.x-rust-open-enum}}\n"
OPEN_ENUM_DISPLAY_CLOSE = "            {{/vendorExtensions.x-rust-open-enum}}\n"


def open_enums(text: str, what: str) -> str:
    enum_vars_end = "{{/enumVars}}{{/allowableValues}}\n}\n"
    property_vars_end = "{{/enumVars}}\n{{/allowableValues}}\n}\n"
    string_variant = '    #[serde(rename = "{{{value}}}")]\n    {{{name}}},\n'
    integer_variant = "    {{{name}}} = {{{value}}},\n"
    for variant, vars_end, catch_all, sites in (
        (string_variant, enum_vars_end, OPEN_ENUM_STRING_VARIANT, 1),
        (string_variant, property_vars_end, OPEN_ENUM_STRING_VARIANT, 1),
        (integer_variant, enum_vars_end, OPEN_ENUM_INTEGER_VARIANT, 1),
    ):
        text = replace_exactly(text, variant + vars_end, variant + vars_end[:-2] + catch_all + "}\n", sites, what)
    text = replace_at_most_once(
        text, integer_variant + property_vars_end, integer_variant + property_vars_end[:-2] + OPEN_ENUM_INTEGER_VARIANT + "}\n", what
    )
    # Display: only value enums have one in the template; EnumFromStr writes the one of a property enum from serde's name.
    vars_close = "            {{/enumVars}}\n            {{/allowableValues}}\n"
    string_arm = '            Self::{{{name}}} => write!(f, "{{{value}}}"),\n'
    text = replace_exactly(
        text,
        string_arm + vars_close,
        string_arm + vars_close + OPEN_ENUM_DISPLAY_OPEN + '            Self::{{{name}}} => write!(f, "{{{wire}}}"),\n' + OPEN_ENUM_DISPLAY_CLOSE,
        1,
        what,
    )
    integer_arm = '            Self::{{{name}}} => "{{{value}}}",\n'
    found = text.count(integer_arm + vars_close)
    if found not in (1, 2):
        raise SystemExit(f"{what}: expected 1 or 2 integer enum Display impls, found {found}")
    return text.replace(
        integer_arm + vars_close,
        integer_arm + vars_close + OPEN_ENUM_DISPLAY_OPEN + '            Self::{{{name}}} => "{{{wire}}}",\n' + OPEN_ENUM_DISPLAY_CLOSE,
    )


EDITS = {
    "lib.mustache": lambda t, w: CRATE_DOCS + t,
    "model.mustache": lambda t, w: union_block(open_enums(gate_enum_defaults(per_property_box(wrap_doc_lines(
        replace_exactly(
            replace_exactly(t, STRUCT_DERIVE, STRUCT_DERIVE_BON, 1, w), CTOR_IF_LET, CTOR_MAP, 1, w
        ),
        w,
    ), w), w), w), w),
    "reqwest/api.mustache": lambda t, w: api_template(t, w),
    "reqwest/api_mod.mustache": lambda t, w: replace_exactly(replace_exactly(
        replace_exactly(t, DEEP_OBJECT_PANIC, DEEP_OBJECT_FIX, 1, w), API_MOD_CONFIGURATION,
        API_MOD_CONFIGURATION + API_MOD_HELPER + API_MOD_WEBHOOKS[len(API_MOD_CONFIGURATION):], 1, w
    ), RESPONSE_ERROR_VARIANT, RESPONSE_ERROR_VARIANT_BOXED, 1, w),
    "reqwest/configuration.mustache": lambda t, w: replace_exactly(
        replace_exactly(t, PARTIAL_HEADER, PARTIAL_HEADER_ALLOWS, 1, w), USER_AGENT_STOCK, USER_AGENT_PACKAGE, 1, w
    ),
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


# Files that are not stock templates, copied verbatim into every set (tools/static/<name>).
STATIC_FILES = ["multipart_helper.mustache"]


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
        for name in STATIC_FILES:
            path = out / name
            path.write_text((ROOT / "tools/static" / name).read_text())
            print(f"wrote {path.relative_to(ROOT)} (static)")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for arg in argv[1:]:
        sync(Path(arg))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
