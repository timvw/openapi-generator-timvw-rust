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
| Library | only where the Rust depends on the HTTP library: which types come from it (see [HTTP library](#http-library)) |
| Gap | only for a known gap: what is missing |

## Baseline

The **Rust** entry of every rule is the rendering with the options openai-client-base and langfuse-client-base both set:

```
boxOnlyCycles defaultOnlyWhereValid enumFromStr noStructDisplay integersAsI64 qualifiedInlineNames
modelUnions unionDispatch openEnums schemaDefaultDocs nullableUnionFields
```

with `library=reqwest`, `supportAsync=true`, `supportMiddleware=true`, `preferUnsignedInt=false` and
`useSingleRequestParameter=false`. openai-client-base also sets `multipartHelper`, `typedEventStreams`,
`contentTypeMethods`, `boxResponseError` and `useChrono=false`; langfuse-client-base also sets `typedEnumParameters` and
`deprecatedAttributes`. The **Options** entry describes those and every other option as a variation of the baseline.

## HTTP library

`timvw-rust` generates for one HTTP library, [reqwest](https://docs.rs/reqwest) (with
[reqwest-middleware](https://docs.rs/reqwest-middleware)). The stock generator's other libraries (`hyper`, `hyper0x`,
`reqwest-trait`) are rejected. The generated code uses reqwest directly; there is no transport layer in between.

Most of the mapping does not depend on that choice: the models, their names, and how they encode and decode are
serde types. Some types come from the HTTP library, and would differ with another one. A rule that renders such a type
has a **Library** row that names it. These are:

| Where | Type from the HTTP library |
| --- | --- |
| a binary response (`format: binary`, `application/octet-stream`) | `reqwest::Response`, read as bytes or a stream |
| a binary request body or file part | `std::path::PathBuf`; the file is opened with tokio and streamed as a `reqwest::Body` or a `reqwest::multipart::Part` |
| a `multipart/form-data` body | built as a `reqwest::multipart::Form` |
| a `text/event-stream` response (`typedEventStreams`) | a stream over the response's bytes |
| the client | `reqwest_middleware::ClientWithMiddleware` in `Configuration` |
| transport errors | `Error::Reqwest(reqwest::Error)`, `Error::ReqwestMiddleware(reqwest_middleware::Error)` |

## Objects

| Specification object | File | Status |
| --- | --- | --- |
| OpenAPI Object (`webhooks`), Info Object (`version`) | [openapi.md](openapi.md) | done |
| Server Object | [server.md](server.md) | done |
| Components Object | [components.md](components.md) | done (`schemas`; `securitySchemes` in [security-scheme.md](security-scheme.md)) |
| Paths, Path Item, Operation Object | [operation.md](operation.md) | done |
| Parameter Object | [parameter.md](parameter.md) | done |
| Request Body Object | [request-body.md](request-body.md) | done |
| Encoding Object | [encoding.md](encoding.md) | done |
| Responses Object | [responses.md](responses.md) | done |
| Response Object | [response.md](response.md) | done |
| Reference Object | [reference.md](reference.md) | done |
| Schema Object | [schema.md](schema.md) | done |
| Discriminator Object | [discriminator.md](discriminator.md) | done |
| Security Scheme Object, Security Requirement | [security-scheme.md](security-scheme.md), [`operation.security`](operation.md#operationsecurity) | done |
| Header Object | [header.md](header.md) | done |
| Callback Object | [callback.md](callback.md) | done |
| Link Object | [link.md](link.md) | done |
| Example Object | [example.md](example.md) | done |
| Tag Object | [tag.md](tag.md) | done |
| External Documentation Object | [external-documentation.md](external-documentation.md) | done |
| XML Object | [xml.md](xml.md) | done |
| OAuth Flows, OAuth Flow | [security-scheme.md](security-scheme.md#oauthflowsscopes) | done |