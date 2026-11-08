# Roadmap: a Rust mapping and its implementations

`timvw-rust` started as a way to replace the post-processing scripts of
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base). That work is done. The goal now is a general
OpenAPI-to-Rust client generator, an alternative to stock `-g rust` and progenitor for any spec. It keeps what the two
real-world specs taught us, and both stay supported.

## Decisions

- **Stay on openapi-generator, in Java.** The openapi-generator plugin remains the implementation. A Rust-native
  rewrite is not planned.
- **Change the mapping.** Matching stock `-g rust` is no longer the goal. The default output becomes the mapping this
  project defines; stock rendering stays available as a compatibility preset for as long as it is cheap to keep.
- **Split the mapping from its implementation.** What Rust an OpenAPI construct becomes is defined once, apart from
  how a generator produces it. The openapi-generator plugin is the first implementation; others (a Rust or Go
  program, another generator framework) can follow and be checked against the same definition.
- **openai and langfuse stay supported.** Their specs are part of the conformance suite and must pass. Their
  clients keep rendering byte-identically until they choose to move to a new mapping version.

## The split

```
mapping/        what an OpenAPI construct becomes in Rust (normative, versioned)
conformance/    cases that check an implementation against the mapping
src/, pom.xml   the openapi-generator implementation (Java, templates)
```

The Java build stays at the repository root until a second implementation exists. Moving it into
`implementations/openapi-generator/` earlier only churns the build and release pipeline.

### Mapping specification (`mapping/`)

The definition grows out of [type-mapping.md](type-mapping.md). Each construct becomes a rule with a stable ID:

| Field | Example |
| --- | --- |
| ID | `UNION-DISCRIMINATED-01` |
| OpenAPI input | `oneOf` with `discriminator.propertyName` and a `mapping` |
| Rust output | `#[serde(tag = "...")]` enum, one variant per mapping entry; the tag field is not a struct field |
| Wire behaviour | decodes by the tag; encodes the tag once |
| Rationale | stock `-g rust` keeps the tag as a field, so the enum never decodes and encodes the tag twice |
| Since | mapping 1.0 |

The mapping has its own version, separate from any implementation's version. A rule that changes the public Rust API
of an existing spec is a major mapping change. Each implementation states which mapping version it implements and
lists the rules it does not support yet.

Implementation knobs (`library`, `supportMiddleware`, `packageName`, the Docker image) stay out of the mapping. Today's
options split into two groups. Mapping rules (`modelUnions`, `unionDispatch`, `openEnums`, `typedEnumParameters`, ...)
become part of a mapping version. Implementation settings stay options.

### Conformance suite (`conformance/`)

The suite checks the Rust that comes out, not how it is produced, so any implementation can run it. Each case is a
directory:

| File | Checks |
| --- | --- |
| `spec.yaml` | the input: a small spec focused on one or a few rules |
| `public-api.txt` | the expected public API (normalized `cargo public-api` output) |
| `wire/*.json` | bodies that must decode into the generated types and encode back to the same JSON |
| `case.toml` | rule IDs covered, mapping version, options |

Every case must also build and pass `cargo clippy -- -D warnings`. The wire fixtures matter most: they show behaviour,
which the comparison's `decode.rs` showed text alone does not. Stock `-g rust` output looks plausible but does not
decode.

A **corpus** of pinned real-world specs runs on top of the focused cases. It records each spec's public-API snapshot
and checks that it builds and passes clippy:

- must pass: OpenAI, Langfuse
- added as they pass: Petstore (3.0 and 3.1), GitHub REST, Stripe, Kubernetes, plus a spec that uses cookie
  parameters, OAuth and deep `allOf` heavily

A corpus spec that fails is listed with the rules it breaks. That list sets the order of the mapping work.

### Shared mapping logic (later, optional)

A shared intermediate representation (IR) is a further step: the resolved Rust model (types, fields, variants,
operations) as JSON, produced once and rendered by any implementation. The Java plugin could emit it through
`CodegenModel`/`CodegenOperation`. It is not needed for the split above, because conformance on the output already lets
implementations differ inside. Decide on it when a second implementation wants to reuse the Java plugin's mapping
decisions instead of reimplementing them.

## Phases

| Phase | Work | Done when |
| --- | --- | --- |
| 1. Conformance | `conformance/` harness; the 27 existing test specs and `docs/comparison` become cases with wire fixtures; corpus with OpenAI, Langfuse and Petstore | CI runs the suite against the plugin on every PR |
| 2. Mapping 1.0 | `mapping/` with rule IDs, from `type-mapping.md`; every rule linked to cases | each rule has at least one case; the README links the mapping instead of describing it |
| 3. Default mapping | `mapping=1.0` preset as the default; `mapping=stock` keeps today's defaults; generator 1.0.0 | openai and langfuse pin explicit options and render unchanged; a new user gets mapping 1.0 with no options |
| 4. Close the gaps | the error variant follows the status code; cookie parameters are sent; `readOnly`/`writeOnly` per direction; typed `uuid` parameters; OpenAPI 3.1 `type: [x, "null"]` and `const` | the rules are in mapping 1.x, with cases; more corpus specs pass |
| 5. Wider corpus | GitHub, Stripe, Kubernetes and others in the corpus | each builds and passes clippy, or its open rules are listed |
| 6. Clients on mapping 1.x | openai-client-base and langfuse-client-base move from explicit options to the preset (a breaking release, with openai-ergonomic changes) | the client configs name a mapping version only |

The documentation changes alongside. The README becomes a user guide: quick start, mapping and presets,
implementation options. The openai/langfuse migration history (milestones, script replacements) moves to
`docs/history.md`. Releases get a changelog per version.

## Open decisions

- **Repository name.** `openapi-generator-timvw-rust` names one implementation. Once `mapping/` and `conformance/`
  live here, a neutral name fits better (the generator name `timvw-rust` can stay).
- **Stock preset lifetime.** Keep `mapping=stock` for as long as the templates sync cheaply, or drop it at 2.0.
- **Public API format.** `cargo public-api` needs a nightly rustdoc. The alternative is a normalized `syn`-based
  dump that works on stable.
