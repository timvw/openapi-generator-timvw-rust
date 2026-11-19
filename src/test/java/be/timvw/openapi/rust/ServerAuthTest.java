package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code serverAuthentication} (docs/design/server-authentication.md) on src/test/resources/server_auth.yaml: the
 * server reads the credentials of the spec's security schemes, authenticates a request with an {@code Authenticator}
 * before decoding it, and gives an operation with security its claims. src/test/rust/server_auth.rs calls the router in
 * process; {@link #theServerAuthenticatesBeforeCallingTheImplementation} runs it and clippy with cargo.
 */
class ServerAuthTest {

    private static final String SPEC = "src/test/resources/server_auth.yaml";

    @TempDir
    Path out;

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "auth_fixture");
        props.putAll(options);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void theOptionAppliesToTheServer() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.SERVER_AUTHENTICATION, "true")));
        assertContains(e.getMessage(), "option 'serverAuthentication' applies to the server");
    }

    @Test
    void withoutTheOptionNothingChanges() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertFalse(server.contains("Authenticator"), server);
        assertFalse(server.contains("claims:"), server);
        assertContains(server, "    pub trait AuthApi: Send + Sync {\n");
    }

    @Test
    void anOperationWithSecurityGetsTheClaims() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_AUTHENTICATION, "true",
                TimvwRustCodegen.SERVER_DEFAULT_METHODS, "true"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertContains(server, "    pub trait AuthApi: super::Authenticator + Send + Sync {\n");
        // required (the global security), none (security: []), optional (an empty requirement)
        assertContains(server, """
                        fn get_me(
                            &self,
                            claims: Self::Claims,
                            request: GetMeRequest,
                """);
        assertContains(server, """
                        fn health(
                            &self,
                            request: HealthRequest,
                """);
        assertContains(server, """
                        fn get_feed(
                            &self,
                            claims: Option<Self::Claims>,
                            request: GetFeedRequest,
                """);
        assertContains(server, "            let _ = (claims, request);\n");
        // the requirements as the spec gives them: alternatives (OR) of schemes (AND) with their scopes
        assertContains(server, "        const REQUIREMENTS: &[&[super::Requirement]] = &[&[], "
                + "&[super::Requirement { scheme: \"bearerAuth\", scopes: &[] }]];\n");
        assertContains(server, "&[&[super::Requirement { scheme: \"headerKey\", scopes: &[] }, "
                + "super::Requirement { scheme: \"cookieKey\", scopes: &[] }]]");
        assertContains(server, "&[&[super::Requirement { scheme: \"oauth\", scopes: &[\"admin:write\"] }]]");
        assertContains(server, "        let claims = match super::optional_claims(&*api, \"get_feed\", credentials, "
                + "REQUIREMENTS).await {\n");
        // a query API key is read from the query
        assertContains(server, "        axum::extract::RawQuery(credentials_query): axum::extract::RawQuery,\n");
        assertContains(server, "super::credentials(&headers, credentials_query.as_deref(), "
                + "&[\"headerKey\", \"queryKey\"]);\n");
        // a variant per scheme
        assertContains(server, "    BearerAuth(Secret),\n");
        assertContains(server, "    BasicAuth {\n");
        assertContains(server, "    Oauth(Secret),\n");
        assertContains(server, "http::HeaderValue::from_static(\"Bearer\")");
    }

    @Test
    void aSchemeWhoseCredentialsAreNotReadHasNoReader() throws IOException {
        Path spec = out.resolve("mtls.yaml");
        Files.writeString(spec, """
                openapi: 3.1.0
                info: { title: mtls, version: 1.0.0 }
                security: [ { tls: [] } ]
                paths:
                  /ping:
                    get:
                      operationId: ping
                      responses: { '204': { description: ok } }
                components:
                  securitySchemes:
                    tls: { type: mutualTLS }
                """);
        Path dir = out.resolve("crate");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(spec.toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(Map.of("packageName", "mtls", TimvwRustCodegen.TARGETS, "server",
                        TimvwRustCodegen.SERVER_AUTHENTICATION, "true"))
                .toClientOptInput()).generate();
        String server = Files.readString(dir.resolve("src/server.rs"));
        // named in the requirements, never read: the operation answers 401 (no credentials)
        assertContains(server, "    Tls(Secret),\n");
        assertFalse(server.contains("schemes.contains(&\"tls\")"), server);
        assertContains(server, "super::credentials(&headers, None, &[]);\n");
    }

    /**
     * Runs src/test/rust/server_auth.rs and clippy on the server alone, and builds the crate with both targets and
     * with validation. Needs cargo; without it there is nothing to run.
     */
    @Test
    void theServerAuthenticatesBeforeCallingTheImplementation() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path dir = out.resolve("server");
        generate(dir, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_AUTHENTICATION, "true"));
        Path manifest = dir.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1", "json"] }
                http-body-util = "0.1"
                tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
                tower = { version = "0.5", features = ["util"] }
                """);
        Files.createDirectories(dir.resolve("tests"));
        Files.copy(Path.of("src/test/rust/server_auth.rs"), dir.resolve("tests/server_auth.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(dir, 900, "test", "--test", "server_auth");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        String clippy = cargo(dir, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "cargo did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);

        Path both = out.resolve("both");
        generate(both, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.SERVER_AUTHENTICATION, "true", TimvwRustCodegen.SERVER_VALIDATION, "true",
                TimvwRustCodegen.SERVER_DEFAULT_METHODS, "true", TimvwRustCodegen.API_FEATURES, "true"));
        // with API features, the groups of the full feature
        for (String features : List.of("server,full", "client,rustls,full", "server")) {
            List<String> args = new ArrayList<>(List.of("clippy", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            args.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(both, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), features + ":\n" + output);
        }
    }

    private static String cargo(Path dir, long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            return null;
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output = Files.readString(log);
        Files.delete(log);
        return output;
    }
}
