# Guide: split crates (`splitCrates`)

A client of a very large spec is a very large crate: openai-client-base, 3,150 models and 460k generated lines, takes
11 GB to compile and 10.6 GB to document, more than [docs.rs](https://docs.rs/about/builds) (6.4 GB) and many
laptops and CI runners have. rustc and rustdoc work on one crate at a time; `splitCrates` writes several crates
instead, each a fraction of the whole, behind a facade crate with the paths and features of the single crate.

```bash
--additional-properties=splitCrates=true,apiFeaturesFile=/local/api-features.yaml
```

It implies [`apiFeatures`](api-features.md): the crates follow the feature groups. It splits a client (`targets`
`["client"]`, the default); a server, a crate of models or `modelsCrate` fail generation.

## The crates

```text
Cargo.toml              the facade <packageName>, and the workspace
src/lib.rs              pub mod apis; pub mod models;
src/models/mod.rs       pub use <packageName>_common::models::*; #[cfg(..)] pub use <packageName>_<crate>::models::*; ...
src/apis/mod.rs         the same for the API modules
crates/common/          <packageName>-common
crates/<crate>/         <packageName>-<crate>, per crate of the groups
crates/untagged/        <packageName>-untagged
```

| Crate | Holds |
| --- | --- |
| `<packageName>` | Nothing of its own: `models` and `apis` re-export the crates below, under the paths of the single crate (`my_client::models::Pet`, `my_client::apis::pets_api::list_pets`, `my_client::apis::configuration::Configuration`). Its features are those of the single crate. |
| `<packageName>-common` | The models whose groups are in more than one crate, the models every group uses, `configuration`, `Error`, `ResponseContent`, `ApiResponse`, `sse`, `validation`, `unix_time` and the helpers. |
| `<packageName>-<crate>` | The models only the groups of the crate use, and the API modules of their operations. |
| `<packageName>-untagged` | The models no operation reaches (feature `types` alone). They may use the models of any crate, so this crate depends on those it uses. |

The crates of the groups depend on `common` only, `untagged` on `common` and the crates it uses, and the facade on
all of them. A model is defined once, in one crate; every other crate re-exports it.

Each group goes into the crate its `crate` names in `apiFeaturesFile`, or into a crate named after the group. An entry
with only `crate` places a feature the generator names after a tag:

```yaml
administration: { paths: [/organization], crate: administration }
chat-completion: { tags: [Chat], crate: chat }
embedding: { tags: [Embeddings], crate: chat }
moderation: { tags: [Moderations], crate: chat }
# a tag's feature, not a group of the file
store: { crate: shop }
```

Putting groups that share models into one crate keeps those models out of `common`, and fewer crates mean fewer
packages to release; a crate that is too large brings back the memory problem. openai-client-base is 11 crates of
14k to 91k generated lines (the largest group, `responses`, is a crate of its own): each compiles (`cargo check`) in
at most 2.6 GB and documents in at most 3 GB, and the facade documents in 3.2 GB, where the single crate took 11.4 GB
and 10.6 GB.

## What changes in the code

Nothing in the generated models and API functions: they are written as for the single crate and moved.

- The `models` module of every crate starts with `pub use <packageName>_common::models::*;` (and `untagged`'s with
  the models of the crates it uses), and `apis` with `pub use <packageName>_common::apis::*;`, so `crate::models::X`,
  `super::Error` and `super::configuration` resolve as before. These re-exports are `#[doc(no_inline)]`: each crate
  documents its own items only.
- An API module whose operations are in several crates is split: each crate gets a module of the same name with its
  operations' functions, and the facade declares the module itself, re-exporting each part
  (`pub mod default_api { pub use <packageName>_shop::apis::default_api::*; ... }`).
- Every crate keeps the `#[cfg(feature = ...)]` of the single crate and has the features it uses. The facade's
  `<group>-types` enables the group's crate and its `<group>-types` (and `common`'s), `<group>` its `<group>`, `types`
  the `untagged` crate, and `rustls`, `native-tls`, `retry` and `tracing` those of `common`, which owns the runtime
  dependency.
- The helpers the API functions and models of other crates call are `pub` and `#[doc(hidden)]` in `common`
  (`models::union_dispatch`, `ParseEnumError::new`, `apis::multipart_helper`, `apis::ContentType`,
  `api_response::header_value`). They are not part of the API.
- The example of `ApiResponse` is a doctest of `common`, which defines it.

Generation fails when an operation's groups are in different crates (its functions go into one crate: give the groups
the same `crate`), when a group's crate would be `common` or `untagged`, and when a crate would use a model of a crate
it does not depend on: the generator checks every `models::` path of every crate.

## Versions and releases

The facade's `Cargo.toml` holds the workspace: `[workspace.package]` has the version every crate inherits
(`version.workspace = true`), and `[workspace.dependencies]` requires each crate at exactly that version
(`version = "=1.2.3"`), so one release is one set of crates. Publish them together, the dependencies first
(`common`, the groups' crates, `untagged`, the facade). With release-plz, one `version_group` for all of them does
that; it updates the `=` requirements too.

The crates other than the facade are an implementation detail: their README says so, and which crate holds a model
follows from the spec (a model a second group starts to use moves to `common`). Users depend on the facade. Its
paths, features and documentation are those of the single crate; what differs:

- `std::any::type_name` and the docs' source links name the crate that defines the item
  (`my_client_shop::models::order::Order`);
- `Cargo.lock` and `cargo tree` list every crate;
- a dependency on a crate of the workspace next to the facade of another version would hold other copies of the
  types.

Regenerating does not delete crates: remove `crates/` before generating, as a crate a group no longer has stays
otherwise.
