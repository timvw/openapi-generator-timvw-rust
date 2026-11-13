package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credentials of a {@code Configuration} on src/test/resources/credentials.yaml. The tokens, the basic-auth
 * password and the api key are {@code SecretString}s, exposed only where the header (or query parameter) is built and
 * sent in headers marked sensitive.
 * What goes on the wire is in src/test/rust/credentials.rs, which {@link #whatItDoesAtRunTime} runs with cargo against
 * a local server.
 */
class CredentialsTest {

    private static final String SPEC = "src/test/resources/credentials.yaml";

    @TempDir
    static Path root;

    private static Path out;

    @BeforeAll
    static void generate() {
        out = root.resolve("tower");
        generate(out, Map.of());
    }

    private static void generate(Path dir, Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "credentials_fixture");
        props.putAll(extra);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void assertContains(String text, String part) {
        assertTrue(text.contains(part), part + " not in:\n" + text);
    }

    @Test
    void theSecretsAreSecretStrings() throws IOException {
        String configuration = Files.readString(out.resolve("src/apis/configuration.rs"));
        assertContains(configuration, "pub use timvw_rust_runtime::{ExposeSecret, SecretString};");
        assertContains(configuration, "pub oauth_access_token: Option<SecretString>,");
        assertContains(configuration, "pub bearer_access_token: Option<SecretString>,");
        assertContains(configuration, "pub type BasicAuth = (String, Option<SecretString>);");
        assertContains(configuration, "    pub prefix: Option<String>,\n    pub key: SecretString,\n");
    }

    @Test
    void theyAreExposedOnlyIntoSensitiveHeaders() throws IOException {
        String api = Files.readString(out.resolve("src/apis/default_api.rs"));
        assertContains(api, "req_builder.bearer_auth(timvw_rust_runtime::ExposeSecret::expose_secret(token));");
        assertContains(api, "req_builder.basic_auth(&auth_conf.0, "
                + "auth_conf.1.as_ref().map(timvw_rust_runtime::ExposeSecret::expose_secret));");
        assertContains(api, "req_builder = req_builder.sensitive_header(\"X-Api-Key\", value);");
        assertContains(api, "req_builder.sensitive_header(timvw_rust_runtime::header::COOKIE, cookies.join(\"; \"));");
        assertFalse(api.contains("apikey.key.clone()"), api);
        assertFalse(api.contains("token.to_owned()"), api);
    }

    /** Runs src/test/rust/credentials.rs on the generated crate. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path manifest = out.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/credentials.rs"), out.resolve("tests/credentials.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "credentials");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
    }

    /** What cargo printed, or {@code null} when it cannot run or does not finish in time. */
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
