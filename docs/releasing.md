# Releasing

The generator and the runtime have versions of their own: the generator (`v<version>` tags, Maven Central and the
image) and `timvw-rust-runtime` (`timvw-rust-runtime-v<version>` tags, crates.io). Release the runtime first when the
generator's `DEFAULT_RUNTIME_VERSION` (`TimvwRustCodegen`, the default of `runtimeVersion`) needs a runtime that is
not on crates.io yet: generator 0.28 renders for runtime `0.3.1`.

## The generator

1. Make sure `main` is green.
2. Tag and push: `git tag v0.28.0 && git push origin v0.28.0`.
3. The `Release` workflow (`.github/workflows/release.yml`) does the rest:
   - It sets the pom version from the tag and runs `./mvnw -Prelease deploy`. That signs the jar, sources and javadoc
     with GPG and publishes them through the Sonatype Central Portal.
   - It builds one image per supported openapi-generator version and pushes
     `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator>` (`0.28.0-og7.27.0`). The default
     openapi-generator version also gets `:<version>` and `:latest`.

To rebuild the images of an existing tag (after a workflow fix), run the `Release` workflow by hand with its
`version` (without the `v`); `publish-maven` also publishes to Maven Central when that version is not there yet.

Secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. The image push uses the
workflow's `GITHUB_TOKEN`. The pom's version on `main` stays a `-SNAPSHOT`, and the workflow never commits.

## The runtime

`timvw-rust-runtime` (`runtime/`) is released with [release-plz](https://release-plz.dev) by
`.github/workflows/runtime-release.yml` (`Release runtime`), configured in `runtime/release-plz.toml`:

1. A push to `main` that changes `runtime/` opens or updates a release PR (`chore(runtime): release
   timvw-rust-runtime <version>`): the version bump and `runtime/CHANGELOG.md`. Running the workflow by hand with
   `release_action=prepare` does the same.
2. Review the PR (the changelog comes from the conventional commits; edit it there) and merge it once CI is green.
3. Run the `Release runtime` workflow on `main` with `release_action=publish`. It publishes to crates.io, tags
   `timvw-rust-runtime-v<version>` (which the generator's `Release` workflow, `v*`, ignores) and creates a GitHub
   release.

Secrets: `CARGO_REGISTRY_TOKEN` (crates.io, scoped to the crate). `BOT_APP_ID` and `BOT_PRIVATE_KEY`, a GitHub App's,
are optional: with them release-plz acts as the app, so its release PR triggers CI (a PR opened with the workflow's
`GITHUB_TOKEN` does not); without them it uses `GITHUB_TOKEN`.

## After a release

openai-client-base and langfuse-client-base pin the image tag (`<version>-og<openapi-generator>`) and regenerate with
it; openai-client-base also passes an `apiFeaturesFile`. Add a section to [history.md](history.md) and, for a change a
client has to make, to the [migration guide](guides/migration.md).
