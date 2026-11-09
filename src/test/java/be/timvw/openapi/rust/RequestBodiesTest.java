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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request body the function only serializes is borrowed ({@code requestBody.content.json}), rendered from
 * src/test/resources/request_bodies.yaml. src/test/rust/request_bodies.rs calls the functions through their builders and checks what goes on the wire; {@link #whatItDoesAtRunTime} runs it with cargo, clippy included.
 */
class RequestBodiesTest {

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "request_bodies");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/request_bodies.yaml").toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api() throws IOException {
        return Files.readString(out.resolve("src/apis/default_api.rs"));
    }

    /** The text of one generated function, from its signature to the next function or the end of the file. */
    private static String function(String name) throws IOException {
        String text = api();
        int start = text.indexOf("pub async fn " + name + "(");
        assertTrue(start >= 0, name + " not in:\n" + text);
        int end = text.indexOf("pub async fn ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aModelBodyIsBorrowedAndSentAsIs() throws IOException {
        String create = function("create_item");
        assertContains(create, "create_item(configuration: &configuration::Configuration, item: &models::Item)");
        assertContains(create, "req_builder = req_builder.json(p_body_item);");
    }

    @Test
    void anOptionalBodyIsAnOptionalReference() throws IOException {
        String update = function("update_item");
        assertContains(update, "update_item(configuration: &configuration::Configuration, item: Option<&models::Item>)");
        assertContains(update, "req_builder = req_builder.json(&p_body_item);");
    }

    @Test
    void anArrayBodyIsASlice() throws IOException {
        assertContains(function("create_items"),
                "create_items(configuration: &configuration::Configuration, item: &[models::Item])");
    }

    @Test
    void aMapAndAnyValueAreBorrowed() throws IOException {
        assertContains(function("set_labels"), "request_body: &std::collections::HashMap<String, String>)");
        assertContains(function("send_anything"), "body: Option<&serde_json::Value>)");
    }

    @Test
    void numbersStringsAndFilesKeepTheirType() throws IOException {
        assertContains(function("set_count"), "body: i64)");
        assertContains(function("set_count"), "req_builder = req_builder.json(&p_body_body);");
        assertContains(function("set_name"), "body: &str)");
        assertContains(function("upload"), "body: std::path::PathBuf)");
    }

    /** Runs src/test/rust/request_bodies.rs and clippy on the generated crate. Needs cargo. */
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
        Files.copy(Path.of("src/test/rust/request_bodies.rs"), out.resolve("tests/request_bodies.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(out, 900, "test", "--test", "request_bodies");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        // needless_return: ContentType::from in src/apis/mod.rs, which this rule does not touch
        String clippy = cargo(out, 900, "clippy", "--all-targets", "--", "-D", "warnings",
                "-A", "clippy::needless_return");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);
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
