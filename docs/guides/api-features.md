# Guide: API features (`apiFeatures`)

A large spec makes a large crate: openai-client-base has some 250 operations and 3,100 models, and every user compiles
all of them. With `apiFeatures=true` the generated crate has a Cargo feature per group of operations, so a user
compiles only the groups they call, as with [async-openai](https://docs.rs/async-openai)'s features. The option is off
by default; off, the output is what it was.

## The features

Every group `g` has two features:

| Feature | What it compiles |
| --- | --- |
| `g` | the group's API functions, its models and the HTTP client (`apis`, `configuration`) |
| `g-types` | the group's models only: no `apis` module, no `timvw-rust-runtime` (unless one of its models holds a file, an `Upload`) |
| `types` | every model, also those no operation uses |
| `full` | every group and `types`; a default feature, so a crate that does not choose compiles what it compiled before |

A model belongs to a group when one of the group's operations reaches it: its parameters, request body, responses
(errors included) and, transitively through `$ref`, properties, items, map values, `allOf`/`oneOf`/`anyOf` branches
and discriminator mappings. A model every group needs is not gated; a model no operation reaches is only in `types`.

```toml
[dependencies]
my-client = { version = "1", default-features = false, features = ["rustls", "responses"] }
# only the types, to (de)serialize them without the client:
my-client-types = { package = "my-client", version = "1", default-features = false, features = ["responses-types"] }
```

`native-tls`, `rustls`, `retry` and `tracing` configure the HTTP client when a group brings it in, and do nothing
otherwise.

## Groups: one per tag

Without a groups file every tag is a group. Its feature is the name of the tag's API module without `_api`, in
kebab-case: `vector_stores_api` (tag `Vector stores`) is `vector-stores`. Untagged operations, in `default_api`, are
`default-api`, as `default` is taken (so are `full` and `types`, which get `-api` too). A tag whose feature would be
another name the crate has (`rustls`), or that of another tag's `-types` twin (tags `Foo` and `Foo types`), fails
generation; give it a group of its own in a groups file.

## Groups from a file (`apiFeaturesFile`)

`apiFeaturesFile=path/to/apiFeatures.yaml` (implies `apiFeatures`) names groups of its own. A group selects operations
by `tags` (the spec's tag names) and/or `paths` (path prefixes, which end at a `/`: `/organization` selects
`/organization` and `/organization/projects`, not `/organizations`). The path is relative to the directory the
generator runs in (`/local/...` in the Docker image).

```yaml
# feature name: what it selects
administration:
  tags: [Audit Logs, Projects, Users]
  paths: [/organization]
chat-completion: { tags: [Chat] }
responses: { tags: [Responses, Conversations] }
container: { paths: [/containers] }
```

- An operation no group selects keeps the feature of its tag, so a file only lists what it changes. A group may take
  the name of the tag it covers (`audio: { tags: [Audio] }`).
- An operation several groups select is in each of them; so is an operation with several tags, once per tag module.
- Untagged operations are tag `default`: `tags: [default]` selects them.
- Webhooks have no path: a group selects them by tag (`webhookModule` gives them one).

Generation fails, rather than writing a feature that compiles nothing or means two things, when:

- a group selects a tag no operation has, or a path prefix with no operation under it (a typo);
- two features would have the same name, with their `-types` twins: a group named like the feature of a tag whose
  operations it does not all take (the tag's other operations keep that name), a group `x` next to a group `x-types`,
  or a group named `default`, `full`, `types`, `native-tls`, `rustls`, `retry` or `tracing`;
- a name is not a lower-case Cargo feature name, or a group has a key other than `tags` and `paths`.

## What is gated

- `lib.rs`: `pub mod apis` needs `_api`, the feature every group enables. `models` is always there.
- `apis/mod.rs`: an API module whose operations are all in the same groups gets their features,
  `#[cfg(feature = "audio")]`. A group that takes only part of a module, by path prefix, gates the module with the
  features of all its operations and each function (with its error enum, `_response` twin and typed header structs)
  with the features of its own operation. The modules stay as they are, so the paths of the functions do not depend
  on the features.
- `models/mod.rs`: each model's `pub mod` and `pub use` get the `-types` features that need it,
  `#[cfg(any(feature = "evals-types", feature = "responses-types"))]`.
- `Cargo.toml`: `timvw-rust-runtime` is optional, enabled by `_api`, and by a `-types` feature whose models hold a
  file (an `Upload`: a multipart union's file variant, or a model's file field); it is not optional when a model every
  feature set compiles holds one. `native-tls`, `rustls`, `retry` and `tracing` forward with `?` so they do not enable
  the client.
- An API module whose functions are gated one by one carries `#![allow(unused_imports)]`, as a feature set may compile
  none of the functions that use an import: a crate that keeps a `lib.rs` of its own needs no allow for it.

The generated `Cargo.toml` ends with the features, one comment per group saying what it selects; the README lists them
too. A crate that keeps its own `Cargo.toml` copies that section and the `optional = true` of those dependencies.

```toml
[features]
default = ["native-tls", "retry", "full"]
native-tls = ["timvw-rust-runtime?/native-tls"]
rustls = ["timvw-rust-runtime?/rustls"]
retry = ["timvw-rust-runtime?/retry"]
tracing = ["timvw-rust-runtime?/tracing"]
full = [
    "administration",
    "audio",
    "types",
]
types = [
    "administration-types",
    "audio-types",
]
_api = ["dep:timvw-rust-runtime"]
# tags Audit Logs, Projects, Users; paths under `/organization`
administration = ["administration-types", "_api"]
administration-types = []
# tag Audio
audio = ["audio-types", "_api"]
audio-types = []
```

## What it saves

openai-client-base (the OpenAI spec, the 29 groups of async-openai 0.43), `cargo build` (dev profile) with `rustls` on an
Apple M3 Pro, in a fresh target directory and then for the crate alone:

| Features | Models compiled | Clean build | The crate alone |
| --- | --- | --- | --- |
| `full` (default) | 3,131 | 89 s | 77 s |
| `responses` | 851 | 31 s | 19 s |
| `responses-types` | 851 | 24 s | 19 s |
