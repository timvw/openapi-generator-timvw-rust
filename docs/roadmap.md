# Roadmap: document the mapping, then improve it

`timvw-rust` started as a way to replace the post-processing scripts of
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base). That work is done. The next goal is a
documented mapping: a written definition of what Rust each OpenAPI construct becomes. The generator is then checked
against it, and improved through it.

The generator stays a Java openapi-generator plugin. Both clients stay supported throughout. Its Rust layer and
templates are its own (ported from `-g rust`, see [history](history.md#from-a-subclass-of--g-rust-to-its-own-rust-layer)),
so a change the mapping decides on is made in the generator's code, not worked around a stock template.

Out of scope: other implementations of the mapping, such as a rewrite in Rust. That would be a separate project that
could reuse the mapping.

## 1. Document the mapping of the current generator

**Status: done.** Every object of the OpenAPI Specification has its rules in [docs/mapping/](mapping/README.md#objects).

Write down what `timvw-rust` renders today, as it is, without changing anything. The mapping grows out of
[type-mapping.md](type-mapping.md) and lives in [docs/mapping/](mapping/README.md), one file per object of the
OpenAPI Specification. `MappingDocsTest` checks that every rule names tests that exist.

- Each construct is a rule with a stable ID, taken from the specification's objects and fields, for example
  `discriminator.propertyName`. A rule names:
  - the OpenAPI input;
  - the Rust it renders;
  - how it behaves on the wire (what decodes and what is written);
  - the options that change it, and what it renders without them.
- The baseline is the configuration openai-client-base and langfuse-client-base use. Other options are described as
  variations of that baseline.
- Each rule points to a test that shows the rendering. A rule without one gets a test. This keeps the document true
  as the code changes.
- Known gaps (cookie parameters, the error variant chosen by body shape instead of status code,
  `readOnly`/`writeOnly`, `uuid` parameters, ...) are rules too: they describe today's behaviour and are marked as
  gaps.

**Done when:** every construct in `type-mapping.md` and every option in the README is a rule with a test. The README
links the mapping instead of describing renderings itself.

## 2. Decide what to change

Proposals so far: [decoding only what is needed, and ownership](design/partial-decoding-and-ownership.md), and the
[proposed changes to the mapping](decisions.md), each with a recommendation and a decision to record.

Compare the mapping rule by rule with stock `-g rust`, progenitor (and typify, which it uses for types), and other Rust
generators. The [generator comparison](comparison/README.md) already does this for one spec. The result is a list of
proposed changes, each with:

- the rule;
- what the others do;
- the impact on openai-client-base, langfuse-client-base and their ergonomic crates.

Each change is then decided separately: adopt, adopt as an option, or keep.

**Done when:** each proposed change is decided, and the mapping holds the adopted ones as the next mapping version.

## 3. Make the generator conform

Change the generator so it renders the updated mapping, and update the tests that rules point to.

Changes that alter the public API of the two clients follow the current practice:

- measure the impact on both clients and their ergonomic crates;
- either make the change an option the clients turn on when ready, or release it as a breaking version together with
  the client changes.

**Done when:** every rule of the updated mapping has a passing test, and both clients generate, build and pass clippy
`-D warnings` on the new version.
