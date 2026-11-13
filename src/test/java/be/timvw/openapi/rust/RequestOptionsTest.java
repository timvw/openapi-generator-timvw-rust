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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-call {@code RequestOptions} (extra headers and query parameters) on every operation's builder with
 * {@code httpStack=tower}, on src/test/resources/request_options.yaml. src/test/rust/request_options.rs checks against a
 * local server that they arrive and override generated headers; {@link #whatArrivesAtRunTime} runs it and clippy.
 */
class RequestOptionsTest {

    private static final String SPEC = "src/test/resources/request_options.yaml";

    @TempDir
    static Path tower;

    @TempDir
    static Path middleware;

    @TempDir
    static Path positional;

    @BeforeAll
    static void generate() {
        Map<String, Object> all = Map.of(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true",
                TimvwRustCodegen.CONTENT_TYPE_METHODS, "true", TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        generate(tower, all);
        Map<String, Object> legacy = new LinkedHashMap<>(all);
        legacy.put(TimvwRustCodegen.HTTP_STACK, TimvwRustCodegen.HTTP_STACK_REQWEST_MIDDLEWARE);
        generate(middleware, legacy);
        generate(positional, Map.of(TimvwRustCodegen.BON_BUILDERS, "false"));
    }

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "request_options_fixture");
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

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void everyMethodTakesOptionsLast() throws IOException {
        String api = api(tower);
        assertContains(api, "pub async fn create_item(configuration: &configuration::Configuration, item: &models::Item, "
                + "x_trace: Option<&str>, limit: Option<i64>, options: Option<&timvw_rust_runtime::RequestOptions>) ->");
        for (String method : new String[] {"create_item_response", "create_item_multipart", "create_chat",
                "create_chat_stream", "create_chat_response"}) {
            assertContains(api, "pub async fn " + method + "(");
        }
        // every method, the twins and the content type and stream methods included
        assertEquals(count(api, "pub async fn "), count(api, "RequestOptions>) -> Result<"), api);
        assertEquals(count(api, "pub async fn "), count(api, "req_builder = req_builder.options(options);"), api);
    }

    @Test
    void theyAreAppliedLastBeforeBuilding() throws IOException {
        String api = api(tower);
        assertContains(api, """
                    req_builder = req_builder.header(timvw_rust_runtime::header::ACCEPT, "text/event-stream");
                    // last, so its headers replace the generated ones
                    if let Some(options) = options {
                        req_builder = req_builder.options(options);
                    }
                    let req = req_builder.build()?;
                """);
        assertContains(Files.readString(tower.resolve("src/apis/mod.rs")),
                "pub use timvw_rust_runtime::RequestOptions;\n");
    }

    @Test
    void aParameterNamedOptionsKeepsItsName() throws IOException {
        assertContains(api(tower), "pub async fn search(configuration: &configuration::Configuration, "
                + "options: Option<&str>, request_options: Option<&timvw_rust_runtime::RequestOptions>) ->");
        assertContains(api(tower), "    if let Some(options) = request_options {\n");
    }

    @Test
    void notWithReqwestMiddlewareOrPositionalArguments() throws IOException {
        for (Path dir : new Path[] {middleware, positional}) {
            assertFalse(api(dir).contains("options("), api(dir));
            assertFalse(api(dir).contains("RequestOptions"), api(dir));
        }
        assertFalse(Files.readString(middleware.resolve("src/apis/mod.rs")).contains("RequestOptions"));
        assertContains(api(positional), "pub async fn create_item(configuration: &configuration::Configuration, "
                + "item: &models::Item, x_trace: Option<&str>, limit: Option<i64>) ->");
    }

    @Test
    void theNameIsFree() {
        assertEquals("options", TimvwRustCodegen.optionsParam(new org.openapitools.codegen.CodegenOperation()));
    }

    /** Runs src/test/rust/request_options.rs on the generated crate, and clippy. Needs cargo. */
    @Test
    void whatArrivesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(tower, 30, "--version") != null, "no cargo");
        Path manifest = tower.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    futures-util = "0.3"
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(tower.resolve("tests"));
        Files.copy(Path.of("src/test/rust/request_options.rs"), tower.resolve("tests/request_options.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String result = cargo(tower, 900, "test", "--test", "request_options");
        assertNotNull(result, "cargo did not finish");
        assertTrue(result.contains("test result: ok.") && !result.contains(" 0 passed"), result);
        String clippy = cargo(tower, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        Assumptions.assumeTrue(clippy == null || !clippy.contains("no such command"), "no clippy");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"),
                clippy);
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
