# Releasing

The generator and the runtime have versions of their own: the generator (`v<version>` tags, Maven Central and the
image) and `timvw-rust-runtime` (`timvw-rust-runtime-v<version>` tags, crates.io). Release the runtime first when the
generator's `DEFAULT_RUNTIME_VERSION` (`TimvwRustCodegen`, the default of `runtimeVersion`) needs a runtime that is
not on crates.io yet.

## The generator

1. Make sure `main` is green.
2. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. The `Release` workflow (`.github/workflows/release.yml`) does the rest:
   - It sets the pom version from the tag and runs `./mvnw -Prelease deploy`. That signs the jar, sources and javadoc
     with GPG and publishes them through the Sonatype Central Portal.
   - It builds one image per supported openapi-generator version and pushes
     `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator>` (`X.Y.Z-ogA.B.C`). The default
     openapi-generator version also gets `:<version>` and `:latest`.

To rebuild the images of an existing tag (after a workflow fix), run the `Release` workflow by hand with its
`version` (without the `v`); `publish-maven` also publishes to Maven Central when that version is not there yet.

Secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. The image push uses the
workflow's `GITHUB_TOKEN`. The pom's version on `main` stays a `-SNAPSHOT`, and the workflow never commits.

## The runtime

`timvw-rust-runtime` (`runtime/`) is released with [release-plz](https://release-plz.dev) by
`.github/workflows/release-plz.yml` (`Release runtime`), which calls the
[release workflow the genai-rs crates share](https://github.com/genai-rs/.github/blob/main/docs/release.md),
configured in `runtime/release-plz.toml`:

1. A push to `main` that changes `runtime/` opens or updates a release PR (`chore(runtime): release
   timvw-rust-runtime <version>`): the version bump and `runtime/CHANGELOG.md`.
2. Review the PR (the changelog comes from the conventional commits; edit it there) and merge it once CI is green.
3. The merge publishes to crates.io, tags `timvw-rust-runtime-v<version>` (which the generator's `Release` workflow,
   `v*`, ignores) and creates a GitHub release.

It publishes through crates.io [Trusted Publishing](https://github.com/genai-rs/.github/blob/main/docs/trusted-publishing.md):
the crate trusts `release-plz.yml` in this repo, so no crates.io token is stored. Secrets: `BOT_APP_ID` and
`BOT_PRIVATE_KEY`, a GitHub App's, so the release PR triggers CI (a PR opened with the workflow's `GITHUB_TOKEN` does
not).

## After a release

openai-client-base and langfuse-client-base pin the image tag (`<version>-og<openapi-generator>`) and regenerate with
it; openai-client-base also passes an `apiFeaturesFile`. Add a section to [history.md](history.md) and, for a change a
client has to make, to the [migration guide](guides/migration.md).
