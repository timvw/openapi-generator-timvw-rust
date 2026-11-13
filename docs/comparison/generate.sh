#!/usr/bin/env bash
# Regenerates the three crates compared in docs/comparison/README.md from docs/comparison/spec.yaml.
#
#   docs/comparison/generate.sh
#
# Needs Java 17+, the timvw-rust jar (./mvnw -DskipTests package), the openapi-generator CLI jar in target/
# (see docs/development.md), cargo-progenitor (cargo install cargo-progenitor), a nightly rustfmt
# (rustup component add rustfmt --toolchain nightly) and python3 with PyYAML.
# Output goes to target/comparison/{stock,timvw,progenitor}; each crate is then checked with cargo.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OG_VERSION="${OPENAPI_GENERATOR_VERSION:-7.27.0}"
SPEC="$ROOT/docs/comparison/spec.yaml"
OUT="$ROOT/target/comparison"
CLI="$ROOT/target/openapi-generator-cli-$OG_VERSION.jar"
JAR=
for f in "$ROOT"/target/openapi-generator-timvw-rust-*.jar; do
  case "$f" in *-sources.jar|*-javadoc.jar) ;; *) JAR="$f" ;; esac
done
test -n "$JAR" || { echo "build the generator first: ./mvnw -DskipTests package" >&2; exit 1; }
test -f "$CLI" || { echo "missing $CLI" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

# Stock -g rust, with the same fixed settings timvw-rust pins (reqwest, async, middleware).
java -cp "$CLI" org.openapitools.codegen.OpenAPIGenerator generate -g rust -i "$SPEC" -o "$OUT/stock" \
  --additional-properties=packageName=comparison-stock,library=reqwest,supportAsync=true,supportMiddleware=true,useChrono=false \
  >"$OUT/stock.log" 2>&1

# timvw-rust with the options openai-client-base runs (minus its OpenAI-specific lists).
java -cp "$JAR:$CLI" org.openapitools.codegen.OpenAPIGenerator generate -g timvw-rust -i "$SPEC" -o "$OUT/timvw" \
  -c "$ROOT/docs/comparison/timvw-rust-config.json" >"$OUT/timvw.log" 2>&1

# progenitor (Oxide), builder interface. progenitor 0.16 rejects multipart bodies ("unexpected content type"),
# and panics on an operation with two response types (a JSON and an event-stream success, or a success and a
# `default` of another type), so it gets a copy of the spec without /files, without the event stream, and with
# `default` read as `4XX`. cargo-progenitor formats with rustfmt options that need a nightly rustfmt.
python3 -I - "$SPEC" "$OUT/spec-progenitor.yaml" <<'PY'
import sys, yaml
spec = yaml.safe_load(open(sys.argv[1]))
del spec["paths"]["/files"]
del spec["paths"]["/chat/completions"]["post"]["responses"]["200"]["content"]["text/event-stream"]
for path in spec["paths"].values():
    for op in path.values():
        op["responses"]["4XX"] = op["responses"].pop("default")
yaml.safe_dump(spec, open(sys.argv[2], "w"), sort_keys=False)
PY
RUSTFMT="$(rustup which --toolchain nightly rustfmt)" cargo progenitor -i "$OUT/spec-progenitor.yaml" \
  -o "$OUT/progenitor" -n comparison-progenitor -v 0.1.0 --interface builder >"$OUT/progenitor.log" 2>&1 \
  || echo "progenitor failed, see $OUT/progenitor.log"

for crate in stock timvw progenitor; do
  if [ -f "$OUT/$crate/Cargo.toml" ]; then
    echo "== cargo clippy $crate"
    (cd "$OUT/$crate" && cargo clippy --quiet 2>&1 | grep -E '^(warning|error)' | sort | uniq -c | sort -rn || true)
  fi
done

# The same payloads through each crate (docs/comparison/decode.rs).
for pair in stock:comparison_stock::models timvw:comparison_timvw::models progenitor:comparison_progenitor::types; do
  crate="${pair%%:*}"; models="${pair#*:}"
  [ -f "$OUT/$crate/Cargo.toml" ] || continue
  mkdir -p "$OUT/$crate/examples"
  sed "s/MODELS/$models/" "$ROOT/docs/comparison/decode.rs" >"$OUT/$crate/examples/decode.rs"
  echo "== decode $crate"
  (cd "$OUT/$crate" && cargo run --quiet --example decode)
done
