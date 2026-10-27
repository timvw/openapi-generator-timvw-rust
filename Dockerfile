# syntax=docker/dockerfile:1
#
# Image layout:
#   /opt/gen/openapi-generator-cli-<version>.jar   stock CLI (downloaded here, checksum-verified)
#   /opt/gen/openapi-generator-timvw-rust.jar      this generator (built with ./mvnw package)
#
# Usage mirrors openapitools/openapi-generator-cli:
#   docker run --rm -v "$PWD:/local" ghcr.io/timvw/openapi-generator-timvw-rust:<version> \
#       generate -g timvw-rust -i /local/spec.yaml -o /local/out --additional-properties=packageName=foo

# One image per openapi-generator version: the client repos pin the version whose output their
# pipeline is tuned for (tags are <generator version>-og<openapi-generator version>).
ARG OPENAPI_GENERATOR_VERSION=7.25.0
# SHA-1 of the stock CLI jar. Empty = fetch the .sha1 published next to the jar on Maven Central;
# set it explicitly to pin a known checksum.
ARG OPENAPI_GENERATOR_CLI_SHA1=

# ---- build stage: fetch and verify the stock CLI jar --------------------------------------------
FROM eclipse-temurin:17-jdk AS fetch
ARG OPENAPI_GENERATOR_VERSION
ARG OPENAPI_GENERATOR_CLI_SHA1
WORKDIR /fetch
RUN set -eux; \
    url="https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/${OPENAPI_GENERATOR_VERSION}/openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar"; \
    curl -fsSL -o "openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar" "$url"; \
    sha1="${OPENAPI_GENERATOR_CLI_SHA1:-$(curl -fsSL "${url}.sha1")}"; \
    echo "${sha1}  openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar" | sha1sum -c -
# Pick the main jar; ./mvnw package may also have produced -sources and -javadoc jars.
COPY target/openapi-generator-timvw-rust-*.jar /fetch/jars/
RUN set -eux; \
    jar="$(ls /fetch/jars/openapi-generator-timvw-rust-*.jar | grep -vE -- '-(sources|javadoc)\.jar$')"; \
    test "$(echo "$jar" | wc -l)" -eq 1; \
    cp "$jar" /fetch/openapi-generator-timvw-rust.jar

# ---- runtime stage ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
ARG OPENAPI_GENERATOR_VERSION
LABEL org.opencontainers.image.source="https://github.com/timvw/openapi-generator-timvw-rust" \
      org.opencontainers.image.description="openapi-generator CLI ${OPENAPI_GENERATOR_VERSION} with the timvw-rust generator" \
      org.opencontainers.image.licenses="Apache-2.0 OR MIT"
WORKDIR /opt/gen
COPY --from=fetch /fetch/openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar /opt/gen/
# The jar is produced by `./mvnw -Dopenapi-generator.version=<version> package` before `docker build`.
COPY --from=fetch /fetch/openapi-generator-timvw-rust.jar /opt/gen/
ENV JAVA_OPTS=""
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -cp \"/opt/gen/*\" org.openapitools.codegen.OpenAPIGenerator \"$@\"", "--"]
CMD ["help"]
