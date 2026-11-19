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
 * {@code serverWebhooks} (docs/design/server-webhooks.md) on src/test/resources/webhooks.yaml: the server sends the
 * spec's webhooks through a {@code timvw-rust-runtime} client, and the client's webhook functions send to
 * {@code base_path} itself. src/test/rust/server_webhooks.rs sends to a receiver of its own;
 * {@link #theServerAndTheClientSendSignedWebhooks} runs it and clippy with cargo.
 */
class ServerWebhooksTest {

    private static final String SPEC = "src/test/resources/webhooks.yaml";

    @TempDir
    Path out;

    private static void generate(Path dir, String spec, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "webhooks_fixture");
        props.putAll(options);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void generate(Path dir, Map<String, Object> options) {
        generate(dir, SPEC, options);
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void theOptionAppliesToTheServer() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.SERVER_WEBHOOKS, "true")));
        assertContains(e.getMessage(), "option 'serverWebhooks' applies to the server");
    }

    @Test
    void aClientWebhookFunctionSendsToBasePathItself() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.WEBHOOK_MODULE, "Webhooks"));
        String api = Files.readString(out.resolve("src/apis/webhooks_api.rs"));
        assertContains(api, "    let uri_str = configuration.base_path.clone();\n");
        assertFalse(api.contains("/itemCreated\""), api);
        // a path operation keeps its path
        assertContains(Files.readString(out.resolve("src/apis/default_api.rs")),
                "let uri_str = format!(\"{}/items\", configuration.base_path);");
    }

    @Test
    void withoutTheOptionTheServerSendsNothing() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        assertFalse(Files.readString(out.resolve("src/server.rs")).contains("pub mod webhooks"));
        assertFalse(Files.readString(out.resolve("Cargo.toml")).contains("timvw-rust-runtime ="));
    }

    @Test
    void aServerAloneDependsOnTheRuntimeToSend() throws IOException {
        // as a user generates it: no runtime path (the tests' system property), the released runtime
        String runtimePath = System.clearProperty("timvw-rust.runtimePath");
        try {
            generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_WEBHOOKS, "true"));
        } finally {
            if (runtimePath != null) {
                System.setProperty("timvw-rust.runtimePath", runtimePath);
            }
        }
        String server = Files.readString(out.resolve("src/server.rs"));
        assertContains(server, """
                    pub enum Webhook {
                        /// `itemCreated`
                        ItemCreated(models::Item),
                        /// `petAdopted`
                        PetAdopted(models::Pet),
                    }
                """);
        assertContains(server, "                Webhook::ItemCreated(_) => \"itemCreated\",\n");
        assertContains(server, "    pub use timvw_rust_runtime::webhooks::{verify, StandardWebhooksLayer, "
                + "WebhookError, WebhookSecret};\n");
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        // the first runtime release with its webhooks feature
        assertContains(cargo, "timvw-rust-runtime = { version = \"^0.4.2\", default-features = false, "
                + "features = [\"webhooks\", \"rustls\", \"retry\"]");
        assertFalse(cargo.contains("optional = true"), cargo);
        // a path dependency is what the path holds, whatever its version: the default requirement
        Path withPath = out.resolve("path");
        generate(withPath, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_WEBHOOKS, "true",
                TimvwRustCodegen.RUNTIME_PATH, "../runtime"));
        assertContains(Files.readString(withPath.resolve("Cargo.toml")), "timvw-rust-runtime = { version = \"^"
                + TimvwRustCodegen.DEFAULT_RUNTIME_VERSION + "\", ");
    }

    @Test
    void withBothTargetsTheServerFeatureEnablesTheRuntime() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.SERVER_WEBHOOKS, "true"));
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        assertContains(cargo, "features = [\"webhooks\"]");
        assertContains(cargo, "server = [\"dep:axum\", \"dep:http\", \"dep:timvw-rust-runtime\"]\n");
    }

    @Test
    void aSpecWithoutWebhooksGetsNoModule() throws IOException {
        generate(out, "src/test/resources/server.yaml", Map.of(TimvwRustCodegen.TARGETS, "server",
                TimvwRustCodegen.SERVER_WEBHOOKS, "true"));
        assertFalse(Files.readString(out.resolve("src/server.rs")).contains("pub mod webhooks"));
        assertFalse(Files.readString(out.resolve("Cargo.toml")).contains("timvw-rust-runtime ="));
    }

    /**
     * Runs src/test/rust/server_webhooks.rs and clippy on a crate with both targets, each target alone, and the server
     * alone with API features. Needs cargo; without it there is nothing to run.
     */
    @Test
    void theServerAndTheClientSendSignedWebhooks() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path dir = out.resolve("both");
        generate(dir, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.WEBHOOK_MODULE, "Webhooks", TimvwRustCodegen.SERVER_WEBHOOKS, "true"));
        Path manifest = dir.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1"] }
                tokio = { version = "1", features = ["rt-multi-thread", "macros", "net"] }
                """);
        Files.createDirectories(dir.resolve("tests"));
        Files.copy(Path.of("src/test/rust/server_webhooks.rs"), dir.resolve("tests/server_webhooks.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(dir, 900, "test", "--test", "server_webhooks");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        for (List<String> args : List.of(List.of("clippy", "--all-targets"),
                List.of("clippy", "--no-default-features", "--features", "server"),
                List.of("clippy", "--no-default-features", "--features", "client,rustls"))) {
            List<String> command = new ArrayList<>(args);
            command.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(dir, 900, command.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), args + ":\n" + output);
        }

        // the server alone, with API features: each group compiles its webhooks
        Path gated = out.resolve("gated");
        generate(gated, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.API_FEATURES, "true",
                TimvwRustCodegen.WEBHOOK_MODULE, "Webhooks", TimvwRustCodegen.SERVER_WEBHOOKS, "true"));
        for (String features : List.of("full", "pets", "")) {
            List<String> command = new ArrayList<>(List.of("clippy", "--no-default-features"));
            if (!features.isEmpty()) {
                command.addAll(List.of("--features", features));
            }
            command.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(gated, 900, command.toArray(String[]::new));
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
