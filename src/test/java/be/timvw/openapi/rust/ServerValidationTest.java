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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code serverValidation} (slice 4 of docs/design/targets-and-server.md) on src/test/resources/server_validation.yaml:
 * the server checks the validation keywords of the path and query parameters, the form fields and the body's models
 * before calling the implementation. src/test/rust/server_validation.rs calls the router in process;
 * {@link #theServerAnswersWhatBreaksAKeywordWithA400} runs it and clippy with cargo.
 */
class ServerValidationTest {

    private static final String SPEC = "src/test/resources/server_validation.yaml";

    @TempDir
    Path out;

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "validation_fixture");
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
    void serverValidationNeedsTheModelsValidate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_VALIDATION, "true",
                        TimvwRustCodegen.VALIDATE_METHODS, "false")));
        assertEquals("timvw-rust: option 'serverValidation' validates requests with the models' Validate, which "
                + "'validateMethods=false' does not generate; remove one of them", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.SERVER_VALIDATION, "true")));
        assertContains(e.getMessage(), "option 'serverValidation' applies to the server");
    }

    @Test
    void withoutTheOptionNothingIsChecked() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertFalse(server.contains("Validate"), server);
        assertFalse(server.contains("models::validation"), server);
        assertFalse(server.contains("RequestError::invalid"), server);
        assertFalse(Files.readString(out.resolve("Cargo.toml")).contains("regex"));
    }

    @Test
    void aRequestStructChecksItsParametersAndItsBody() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_VALIDATION, "true"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertContains(server, """
                    impl crate::models::validation::Validate for ListItemsRequest {
                        fn validate_into(
                            &self,
                            path: &crate::models::validation::Path<'_>,
                            errors: &mut Vec<crate::models::validation::ValidationError>,
                        ) {
                            if let Some(value0) = &self.limit {
                                crate::models::validation::minimum(*value0, 1, &path.field("query").field("limit"), errors);
                                crate::models::validation::maximum(*value0, 100, &path.field("query").field("limit"), errors);
                            }
                """);
        assertContains(server, "            crate::models::validation::Validate::validate_into(&self.body, "
                + "&path.field(\"body\"), errors);\n");
        assertContains(server, "            super::pattern(&self.code, &super::PATTERN_0, r\"^[A-Z]{3}$\", "
                + "&path.field(\"path\").field(\"code\"), errors);\n");
        assertContains(server, "static PATTERN_0: std::sync::LazyLock<Option<regex::Regex>> = "
                + "std::sync::LazyLock::new(|| regex::Regex::new(r\"^[A-Z]{3}$\").ok());\n");
        // a form struct and a body enum check what they hold
        assertContains(server, "    impl crate::models::validation::Validate for SearchItemsForm {\n");
        assertContains(server, "    impl crate::models::validation::Validate for ImportItemBody {\n");
        // one variant of several to check: an if let
        assertContains(server, "            if let PostNoteBody::Json(value) = self {\n");
        // the handler checks a request after decoding it, and only where there is something to check
        assertContains(server, """
                        if let Err(errors) = crate::models::validation::Validate::validate(&request) {
                            return api.invalid_request(super::RequestError::invalid(errors));
                        }
                """);
        assertFalse(server.contains("Validate for HealthRequest"), server);
        assertEquals(6, server.split("Validate::validate\\(&request\\)", -1).length - 1);
        // our own models have no pattern: the server's own regex dependency
        assertContains(Files.readString(out.resolve("Cargo.toml")), "regex = { version = \"^1.10\" }\n");
    }

    @Test
    void aServerOnAModelsCrateHasItsOwnRegex() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_VALIDATION, "true",
                TimvwRustCodegen.MODELS_CRATE, "validation_models", TimvwRustCodegen.MODELS_CRATE_VERSION, "1"));
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        assertContains(cargo, "regex = { version = \"^1.10\" }\n");
        assertEquals(1, cargo.split("regex = ", -1).length - 1, cargo);
    }

    /**
     * Runs src/test/rust/server_validation.rs and clippy on the server alone, and builds the crate with both targets
     * and with a models crate. Needs cargo; without it there is nothing to run.
     */
    @Test
    void theServerAnswersWhatBreaksAKeywordWithA400() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path dir = out.resolve("server");
        generate(dir, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_VALIDATION, "true",
                TimvwRustCodegen.SERVER_DEFAULT_METHODS, "true"));
        Path manifest = dir.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1", "json"] }
                http-body-util = "0.1"
                tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
                tower = { version = "0.5", features = ["util"] }
                """);
        Files.createDirectories(dir.resolve("tests"));
        Files.copy(Path.of("src/test/rust/server_validation.rs"), dir.resolve("tests/server_validation.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(dir, 900, "test", "--test", "server_validation");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        String clippy = cargo(dir, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "cargo did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);

        // both targets, each feature on its own
        Path both = out.resolve("both");
        generate(both, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.SERVER_VALIDATION, "true"));
        String bothCargo = Files.readString(both.resolve("Cargo.toml"));
        assertContains(bothCargo, "regex = { version = \"^1.10\", optional = true }\n");
        assertContains(bothCargo, "\"dep:regex\"");
        for (String features : List.of("server", "client,rustls", "")) {
            List<String> args = new ArrayList<>(List.of("clippy", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            args.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(both, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), features + ":\n" + output);
        }

        // a server on the models of a client crate, whose models have no pattern of their own
        Path workspace = out.resolve("workspace");
        generate(workspace.resolve("models"), Map.of("packageName", "validation_models"));
        generate(workspace.resolve("server"), Map.of(TimvwRustCodegen.TARGETS, "server",
                TimvwRustCodegen.SERVER_VALIDATION, "true", TimvwRustCodegen.MODELS_CRATE, "validation_models",
                TimvwRustCodegen.MODELS_CRATE_PATH, "../models"));
        Files.writeString(workspace.resolve("Cargo.toml"), "[workspace]\nmembers = [\"models\", \"server\"]\n"
                + "resolver = \"2\"\n");
        String models = cargo(workspace, 900, "clippy", "-p", "validation_fixture", "--", "-D", "warnings");
        assertNotNull(models, "cargo did not finish");
        assertTrue(models.contains("Finished") && !models.contains("warning:"), models);
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
