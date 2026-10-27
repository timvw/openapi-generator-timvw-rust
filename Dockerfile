# syntax=docker/dockerfile:1
#
# Image layout:
#   /opt/gen/openapi-generator-cli-<version>.jar   stock CLI (downloaded here, checksum-verified)
#   /opt/gen/openapi-generator-timvw-rust.jar      this generator (built with ./mvnw package)
#
# Usage mirrors openapitools/openapi-generator-cli:
#   docker run --rm -v "$PWD:/local" ghcr.io/timvw/openapi-generator-timvw-rust:<version> \
#       generate -g timvw-rust -i /local/spec.yaml -o /local/out --additional-properties=packageName=foo

ARG OPENAPI_GENERATOR_VERSION=7.25.0
# SHA-1 as published next to the jar on Maven Central (…/openapi-generator-cli-<version>.jar.sha1).
ARG OPENAPI_GENERATOR_CLI_SHA1=56a9bb79e3bb565f477eddca2c6daa288a9c6f35

# ---- build stage: fetch and verify the stock CLI jar --------------------------------------------
FROM eclipse-temurin:17-jdk AS fetch
ARG OPENAPI_GENERATOR_VERSION
ARG OPENAPI_GENERATOR_CLI_SHA1
WORKDIR /fetch
RUN set -eux; \
    url="https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/${OPENAPI_GENERATOR_VERSION}/openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar"; \
    curl -fsSL -o "openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar" "$url"; \
    echo "${OPENAPI_GENERATOR_CLI_SHA1}  openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar" | sha1sum -c -

# ---- runtime stage ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
ARG OPENAPI_GENERATOR_VERSION
LABEL org.opencontainers.image.source="https://github.com/timvw/openapi-generator-timvw-rust" \
      org.opencontainers.image.description="openapi-generator CLI ${OPENAPI_GENERATOR_VERSION} with the timvw-rust generator" \
      org.opencontainers.image.licenses="Apache-2.0 OR MIT"
WORKDIR /opt/gen
COPY --from=fetch /fetch/openapi-generator-cli-${OPENAPI_GENERATOR_VERSION}.jar /opt/gen/
# The jar is produced by `./mvnw package`; CI builds it before `docker build`.
COPY target/openapi-generator-timvw-rust-*.jar /opt/gen/openapi-generator-timvw-rust.jar
ENV JAVA_OPTS=""
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -cp \"/opt/gen/*\" org.openapitools.codegen.OpenAPIGenerator \"$@\"", "--"]
CMD ["help"]
