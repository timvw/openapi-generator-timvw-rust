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
| Options | the options that change it; `always` where none does (what stock renders instead is noted where it helps) |
| Tests | the tests that show the rendering, linked to their line (`tools/link-mapping-tests.py` writes the links) |
| Library | only where the Rust depends on the HTTP library: which types come from it (see [HTTP library](#http-library)) |
| Gap | only for a known gap: what is missing |

Where an entry says "stock", it compares with openapi-generator's own Rust generator, `-g rust` 7.27.0: without an
option that changes it, `timvw-rust` renders what `-g rust` renders. That rendering is `timvw-rust`'s own code and
templates (ported from `-g rust`), not something it inherits, so a rule can change it like any other.

## What the generator renders

The **Rust** entry of every rule is what `timvw-rust` renders without options, in the one client configuration it
generates (async, sending through a tower service from `timvw-rust-runtime`, `httpStack=tower`; the matching stock
options are rejected, see the README's
[options](../../README.md#options)). What used to be the baseline options (`boxOnlyCycles`,
`defaultOnlyWhereValid`, `enumFromStr`, `noStructDisplay`, `integersAsI64`, `qualifiedInlineNames`, `modelUnions`,
`unionDispatch`, `openEnums`, `schemaDefaultDocs`, `nullableUnionFields`) is always applied, and setting one fails
generation; so is the former `boxResponseError` (`ResponseError` holds a `Box`).

openai-client-base also sets `multipartHelper`, `typedEventStreams`, `contentTypeMethods` and
`useChrono=false`; langfuse-client-base also sets `typedEnumParameters` and `deprecatedAttributes`. The **Options**
entry describes those and every other option as a variation of what the generator renders without options.

## HTTP library

By default (`httpStack=tower`) the generated code sends through
[`timvw-rust-runtime`](../../runtime/README.md): its `Client` is a [tower](https://docs.rs/tower) service, reqwest
unless replaced, and its `RequestBuilder`, `Response` and `multipart` have the methods of reqwest's that the code calls.
With `httpStack=reqwest-middleware` it uses [reqwest](https://docs.rs/reqwest) and
[reqwest-middleware](https://docs.rs/reqwest-middleware) directly, as up to 0.24, and the **Library** rows name the
reqwest type of the same name (`reqwest::Response` for `timvw_rust_runtime::Response`, and so on). `--library` (stock
`-g rust` has `hyper`, `hyper0x` and `reqwest-trait`) is rejected; see the
[HTTP stack guide](../guides/http-stack.md).

Most of the mapping does not depend on that choice: the models, their names, and how they encode and decode are
serde types. Some types come from the HTTP library, and would differ with another one. A rule that renders such a type
has a **Library** row that names it. These are:

| Where | Type from the HTTP library |
| --- | --- |
| a binary response (`format: binary`, `application/octet-stream`) | `timvw_rust_runtime::Response`, read as bytes or a stream |
| a binary request body or file part | `timvw_rust_runtime::Upload` (a `PathBuf` converts); a file is streamed with tokio as a `timvw_rust_runtime::Body` or a `timvw_rust_runtime::multipart::Part`, and opened again by a retry |
| a `multipart/form-data` body | built as a `timvw_rust_runtime::multipart::Form` |
| a `text/event-stream` response (`typedEventStreams`) | a stream over the response's bytes |
| the client | `timvw_rust_runtime::Client` in `Configuration` (`reqwest_middleware::ClientWithMiddleware` with `httpStack=reqwest-middleware`) |
| transport errors | `Error::Http(timvw_rust_runtime::Error)` (`Error::Reqwest(reqwest::Error)` and `Error::ReqwestMiddleware(reqwest_middleware::Error)` with `httpStack=reqwest-middleware`) |

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