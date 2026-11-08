# OpenAPI to Rust mapping

This is what Rust `timvw-rust` renders for each OpenAPI construct, written as rules. It describes the generator as it
is, including the known gaps. Changing a rendering means changing its rule here first; see the
[roadmap](../roadmap.md).

## Organization

The mapping follows the [OpenAPI Specification](https://spec.openapis.org/oas/v3.0.3): one file per specification
object, with a rule per field of that object. Cases that need their own rule (a field combined with another, a shape of
value) sit under that field.

A rule ID is `<object>.<field>[.<case>]`:

- `<object>` is the specification object's name in lower camel case: `schema` for the Schema Object,
  `discriminator`, `parameter`, `requestBody`, `mediaType`, `response`, ...
- `<field>` is the field as the specification spells it: `oneOf`, `propertyName`, `in`, ...
- `<case>` is a kebab-case name for one situation of that field: `schema.oneOf.variant-order`,
  `schema.oneOf.null-branch`.

IDs stay stable: a rule that changes keeps its ID, and a rule that goes away is marked removed instead of reused.

OpenAPI 3.1 differences are noted in the rule they affect (for example `type: [T, "null"]` under
`schema.nullable`).

## Rule format

Each rule has:

| Entry | What it says |
| --- | --- |
| OpenAPI | the input |
| Rust | what the generator renders |
| Wire | what decodes and what is written, where that is not obvious from the Rust |
| Options | the options that change it, and what it renders without the baseline |
| Tests | the tests that show the rendering |
| Gap | only for a known gap: what is missing |

## Baseline

The **Rust** entry of every rule is the rendering with the options openai-client-base and langfuse-client-base both set:

```
boxOnlyCycles defaultOnlyWhereValid enumFromStr noStructDisplay integersAsI64 qualifiedInlineNames
modelUnions unionDispatch openEnums schemaDefaultDocs nullableUnionFields
```

with `library=reqwest`, `supportAsync=true`, `supportMiddleware=true`, `preferUnsignedInt=false` and
`useSingleRequestParameter=false`. openai-client-base also sets `multipartHelper`, `typedEventStreams`,
`contentTypeMethods` and `boxResponseError`; langfuse-client-base also sets `typedEnumParameters` and
`deprecatedAttributes`. The **Options** entry describes those and every other option as a variation of the baseline.

## Objects

| Specification object | File | Status |
| --- | --- | --- |
| OpenAPI Object (`paths`, `components`, `webhooks`, `security`, ...) | | in [type-mapping.md](../type-mapping.md) |
| Info, Contact, License | | in [type-mapping.md](../type-mapping.md) |
| Server, Server Variable | | in [type-mapping.md](../type-mapping.md) |
| Components Object | | in [type-mapping.md](../type-mapping.md) |
| Paths, Path Item | | in [type-mapping.md](../type-mapping.md) |
| Operation Object | | in [type-mapping.md](../type-mapping.md) |
| Parameter Object | | in [type-mapping.md](../type-mapping.md) |
| Request Body Object | | in [type-mapping.md](../type-mapping.md) |
| Media Type Object, Encoding Object | | in [type-mapping.md](../type-mapping.md) |
| Responses, Response Object | | in [type-mapping.md](../type-mapping.md) |
| Header Object | | in [type-mapping.md](../type-mapping.md) |
| Callback, Link, Example, Tag, External Documentation, XML | | to do |
| Reference Object | | in [type-mapping.md](../type-mapping.md) |
| Schema Object | [schema.md](schema.md) | composition (`oneOf`, `anyOf`, `allOf`) done; the other fields in [type-mapping.md](../type-mapping.md) |
| Discriminator Object | [discriminator.md](discriminator.md) | done |
| Security Scheme, OAuth Flows, Security Requirement | | in [type-mapping.md](../type-mapping.md) |
