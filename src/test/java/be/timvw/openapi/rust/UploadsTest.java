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
 * File parameters are {@code timvw_rust_runtime::Upload}s with {@code httpStack=tower}, on
 * src/test/resources/uploads.yaml: a builder setter takes anything that converts into one (a {@code PathBuf} too),
 * and files are streamed with a {@code Content-Length} and opened again by a retry. src/test/rust/uploads.rs checks
 * what arrives at a local server, with and without {@code multipartHelper}; reqwest-middleware keeps {@code PathBuf}.
 */
class UploadsTest {

    private static final String SPEC = "src/test/resources/uploads.yaml";
    private static final String UPLOAD = "timvw_rust_runtime::Upload";
    private static final String EACH_INTO = "#[builder(with = |files: impl IntoIterator<Item = impl Into<"
            + UPLOAD + ">>| files.into_iter().map(Into::into).collect())]";

    @TempDir
    static Path tower;

    @TempDir
    static Path helper;

    @TempDir
    static Path middleware;

    @TempDir
    static Path positional;

    @BeforeAll
    static void generate() {
        generate(tower, Map.of());
        generate(helper, Map.of(TimvwRustCodegen.MULTIPART_HELPER, "true"));
        generate(middleware, Map.of(TimvwRustCodegen.HTTP_STACK, TimvwRustCodegen.HTTP_STACK_REQWEST_MIDDLEWARE));
        generate(positional, Map.of(TimvwRustCodegen.BON_BUILDERS, "false"));
    }

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "uploads_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path dir) throws IOException {
        return Files.readString(dir.resolve("src/apis/default_api.rs"));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void fileParametersAreUploadsAndTheirSettersConvert() throws IOException {
        for (Path dir : new Path[] {tower, helper}) {
            String api = api(dir);
            assertContains(api, "#[builder(into)] file: " + UPLOAD + ", purpose: &str, #[builder(into)] mask: Option<"
                    + UPLOAD + ">, " + EACH_INTO + " images: Option<Vec<" + UPLOAD + ">>,");
            assertContains(api, EACH_INTO + " files: Vec<" + UPLOAD + ">,");
            assertContains(api, "#[builder(into)] body: " + UPLOAD + ",");
            assertContains(api, "#[builder(into)] body: Option<" + UPLOAD + ">,");
            assertFalse(api.contains("PathBuf"), api);
            assertFalse(api.contains("TokioFile"), api);
        }
        assertContains(api(positional), "file: " + UPLOAD + ", purpose: &str, mask: Option<" + UPLOAD + ">, images: "
                + "Option<Vec<" + UPLOAD + ">>) ->");
        assertFalse(api(positional).contains("#[builder"), api(positional));
    }

    @Test
    void filesAreResolvedAndStreamed() throws IOException {
        String api = api(tower);
        assertContains(api, "multipart_form = multipart_form.part(\"file\", "
                + "timvw_rust_runtime::multipart::Part::upload(p_form_file.resolve().await?));");
        assertContains(api, """
                    if let Some(param_value) = p_form_images {
                        for value in param_value {
                            multipart_form = multipart_form.part("images", timvw_rust_runtime::multipart::Part::upload(value.resolve().await?));
                """);
        assertContains(api, "req_builder = req_builder.body(p_body_body.resolve().await?);");
        String helped = api(helper);
        assertContains(helped, "multipart_helper::add_file_to_form(multipart_form, &p_form_file, \"file\").await?;");
        String module = Files.readString(helper.resolve("src/apis/multipart_helper.rs"));
        assertContains(module, "pub async fn add_file_to_form(");
        assertFalse(module.contains("std::fs::read"), module);
    }

    @Test
    void theCrateUsesTheRuntimesFilesInsteadOfTokio() throws IOException {
        for (Path dir : new Path[] {tower, helper}) {
            String cargo = Files.readString(dir.resolve("Cargo.toml"));
            assertTrue(cargo.matches("(?s).*timvw-rust-runtime = \\{ version = \"[^\"]+\", default-features = false, "
                    + "features = \\[\"fs\"\\].*"), cargo);
            assertFalse(cargo.contains("tokio"), cargo);
        }
    }

    @Test
    void reqwestMiddlewareKeepsPaths() throws IOException {
        String api = api(middleware);
        assertContains(api, "file: std::path::PathBuf, purpose: &str, mask: Option<std::path::PathBuf>,");
        assertContains(api, "TokioFile::open(");
        assertFalse(api.contains(UPLOAD), api);
        assertContains(Files.readString(middleware.resolve("Cargo.toml")), "tokio-util");
    }

    /** Runs src/test/rust/uploads.rs on the generated crates, and clippy. Needs cargo. */
    @Test
    void whatArrivesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(tower, 30, "--version") != null, "no cargo");
        for (Path dir : new Path[] {tower, helper}) {
            Path manifest = dir.resolve("Cargo.toml");
            String cargo = Files.readString(manifest);
            if (!cargo.contains("[dev-dependencies]")) {
                Files.writeString(manifest, cargo + """

                        [dev-dependencies]
                        tempfile = "3"
                        tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                        """);
            }
            Files.createDirectories(dir.resolve("tests"));
            Files.copy(Path.of("src/test/rust/uploads.rs"), dir.resolve("tests/uploads.rs"),
                    StandardCopyOption.REPLACE_EXISTING);
            String result = cargo(dir, 900, "test", "--test", "uploads");
            assertNotNull(result, "cargo did not finish");
            assertTrue(result.contains("test result: ok.") && !result.contains(" 0 passed"), result);
            String clippy = cargo(dir, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            Assumptions.assumeTrue(clippy == null || !clippy.contains("no such command"), "no clippy");
            assertNotNull(clippy, "clippy did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"),
                    clippy);
        }
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
        String result = Files.readString(log);
        Files.delete(log);
        return result;
    }
}
