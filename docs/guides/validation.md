# Guide: validation (`validate()`)

A spec states more about a value than its type: `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`,
`multipleOf`, `minItems`, `maxItems`, `uniqueItems`, `minProperties`, `maxProperties`. The types do not encode these.
A `String` is a `String`, and a `Vec` is not a set. Every generated model therefore implements a `Validate` trait that
checks them, on demand. The rules are [`schema.pattern`](../mapping/schema.md#schemapattern) and
[`schema.uniqueItems`](../mapping/schema.md#schemauniqueitems), and the decision is
[decision 9](../decisions.md#9-validation-keywords-are-not-checked).

The examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as `my_client`, whose
`NewItem` has:

```yaml
name:  { type: string, minLength: 1, maxLength: 64 }
sku:   { type: string, pattern: '^[A-Z]{3}-[0-9]{4}$' }
price: { type: number, minimum: 0 }
tags:  { type: array, maxItems: 10, uniqueItems: true, items: { type: string } }
```

They are compiled by the tests.

## Using it

```rust
use my_client::models::{self, validation::Validate};

let new_item = models::NewItem::builder()
    .name("Lamp".to_owned())
    .sku("LMP-0001".to_owned())
    .price(19.5)
    .tags(vec!["light".to_owned(), "desk".to_owned()])
    .build();
assert!(new_item.validate().is_ok());

let bad = models::NewItem::builder()
    .name("Lamp".to_owned())
    .tags(vec!["light".to_owned(), "light".to_owned()])
    .build();
let error = bad.validate().unwrap_err();
assert_eq!(error.path, "tags");
assert_eq!(error.rule, models::validation::Rule::UniqueItems);
assert_eq!(error.to_string(), "tags: items 0 and 1 are equal breaks uniqueItems");
```

`validate()` returns the **first** error it finds, as a `ValidationError`:

| Field | Holds |
| --- | --- |
| `path` | where the value is, with the spec's (wire) names: `name`, `tags[1]`, `metadata["key"]`, `messages[2].content` |
| `rule` | the `Rule` it breaks, with its limit: `MinLength(1)`, `Pattern("^[A-Z]{3}-[0-9]{4}$")`, `Maximum(1.0)`, `UniqueItems`, ... (`#[non_exhaustive]`) |
| `actual` | what the value had: `length 0`, `"abc"`, `-1`, `11 items`, `items 0 and 1 are equal` |

It implements `Display` (`<path>: <actual> breaks <rule>`) and `std::error::Error`, so `?` takes it into your error
type. Number limits are reported as `f64`, so an integer limit above 2^53 shows rounded.

The generated functions **never call it**. The server checks the request anyway. A spec that is stricter than its
server would otherwise block requests the server accepts, and a response that breaks a constraint the spec states is
still decoded. Call it where it helps:

- before sending a request you built from user input, to fail early with a precise path:

```rust
async fn create(configuration: &Configuration, new_item: &models::NewItem) -> Result<models::Item, Box<dyn std::error::Error>> {
    new_item.validate()?;
    Ok(items_api::create_item().configuration(configuration).new_item(new_item).call().await?)
}
```

- in tests, to check that fixtures match the spec;
- on a response, to find out whether the server keeps to its own spec.

## What is checked

| Keyword | On | Check |
| --- | --- | --- |
| `minLength`, `maxLength` | `String` | the length in characters (Unicode scalar values), not bytes |
| `pattern` | `String` | the `regex` crate finds a match anywhere in the string, as in JSON Schema (anchor with `^...$`) |
| `minimum`, `maximum` | integers, floats | inclusive bounds |
| `exclusiveMinimum`, `exclusiveMaximum` | integers, floats | exclusive bounds, in the 3.0 boolean and the 3.1 numeric form |
| `multipleOf` | integers, floats | exact for integers; for floats within a relative tolerance (1e-9 for `f64`, 1e-5 for `f32`), so `0.3` is a multiple of `0.1` |
| `minItems`, `maxItems` | `Vec` | the item count |
| `uniqueItems` | `Vec` | no two items are equal (`PartialEq`), whatever the item type; the array stays a `Vec` |
| `minProperties`, `maxProperties` | maps | the entry count |

The check is recursive. A model checks the models it holds, the items of its `Vec`s, the values of its maps, the
`Option`s that hold a value (nullable ones included), boxed values and the active variant of a union. A model without
constraints of its own still implements `Validate`, for the models it holds, or returns `Ok(())`. `Box<T>`,
`Option<T>`, `Vec<T>` and `HashMap<String, T>` implement it for any `T: Validate` and add `[i]` or `["key"]` to the
path.

Each constrained field says so in its documentation, whether or not the option is on:

<!-- not compiled: a generated field -->
```rust
/// Validation: `minLength: 1`, `maxLength: 64`.
#[serde(rename = "name")]
pub name: String,
```

A `pattern` is compiled once, the first time it is needed (`std::sync::LazyLock`, Rust 1.80 or later). The crate
depends on `regex` only when the spec has a pattern that is checked.

## What is not checked

- **Patterns `regex` does not read like JavaScript.** A spec's patterns are ECMA-262 regular expressions. The
  generator checks a pattern only when every construct in it reads the same in both languages. A pattern with
  lookaround (`(?!..)`), backreferences, `\Z`, `\c`, `[]`, `[^]`, `a{,3}`, surrogate escapes (`\uD83D`), set
  operations, inline flags, nested classes, repetition counts that would exceed `regex`'s size limit, and so on, is
  skipped. The generator logs a warning that names the schema, the property and the construct:

```text
WARN o.o.codegen.timvw.TimvwRustCodegen - timvw-rust: validate: Patterns.skip_big_z: pattern ^a\Z is not checked: it holds the escape \Z
```

  A skipped pattern accepts any value. Check the warnings once when you adopt a new spec version. A pattern that is
  accepted but still fails to compile at run time is treated the same way; it never panics.
- **Types with nothing to check:** inline string enums (they are `String` without `typedEnumParameters`),
  `serde_json::Value`, byte arrays, files, dates, uuids and `bool`.
- **A bound every value of the type meets** (an `int64` field with the full `int64` range) is not compared.
- **`minProperties`/`maxProperties` of a struct**, and of a map whose values are an inline enum (a known gap in
  [`schema.pattern`](../mapping/schema.md#schemapattern)).
- **`readOnly`, `writeOnly`, `format`** (beyond the type it picks), `const`, `not`, `if`/`then`, `dependentRequired`:
  not validation keywords here.

## Turning it off

`validateMethods` is on by default. With `--additional-properties=validateMethods=false` there is no
`models/validation.rs`, no `Validate` impl and no `regex` dependency. The field documentation stays. Turn it off for a
crate whose size or compile time matters more than the checks. openai-client-base's spec gives about 1,300 checks, which make its
generated code 9% longer. Nothing else changes, since no generated code calls it.

A schema whose module would be `models::validation` fails generation while the option is on; turn the option off or
rename the schema. A property named `validate` and models named `Validate` or `ValidationError` work.

## A hand-kept `Cargo.toml`

The generated `Cargo.toml` gets `regex = "^1.10"` once a checked pattern appears. A client that keeps its own
`Cargo.toml` (in `.openapi-generator-ignore`) adds it by hand, or the crate does not compile:

```toml
[dependencies]
regex = "1"
```
