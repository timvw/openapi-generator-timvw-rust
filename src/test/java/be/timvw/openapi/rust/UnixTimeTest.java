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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Unix timestamp ({@code integer} or {@code number} of format {@code unixtime}): a model property of that format
 * is a {@code jiff::Timestamp} with {@code dateLibrary=jiff}, read and written as seconds through
 * {@code models::unix_time}; an array item and a parameter keep their number, as does everything with chrono or
 * {@code string}. {@link #theClientCallsTheServer} runs src/test/rust/unix_time.rs on the generated crate.
 */
class UnixTimeTest {

    private static final String SPEC = "src/test/resources/unix_time.yaml";

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "unix_time");
        props.putAll(options);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(SPEC)
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\s+", " ");
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), () -> "expected `" + needle + "` in:\n" + text);
    }

    @Test
    void aPropertyIsATimestampWithJiff(@TempDir Path out) throws IOException {
        generate(out, Map.of(RustCodegenBase.DATE_LIBRARY, "jiff"));
        String event = read(out.resolve("src/models/event.rs"));
        assertContains(event, "#[serde(rename = \"created_at\", with = \"super::unix_time\")] "
                + "pub created_at: jiff::Timestamp,");
        assertContains(event, "#[serde(rename = \"expires_at\", with = \"super::unix_time::option\")] "
                + "pub expires_at: Option<jiff::Timestamp>,");
        assertContains(event, "#[serde(rename = \"updated_at\", default, skip_serializing_if = \"Option::is_none\", "
                + "with = \"super::unix_time::option\")] pub updated_at: Option<jiff::Timestamp>,");
        assertContains(event, "#[serde(rename = \"cancelled_at\", default, skip_serializing_if = "
                + "\"Option::is_none\", with = \"super::unix_time::double_option\")] "
                + "pub cancelled_at: Option<Option<jiff::Timestamp>>,");
        assertContains(event, "#[serde(rename = \"completed_at\", with = \"super::unix_time::fractional\")] "
                + "pub completed_at: jiff::Timestamp,");
        assertContains(event, "with = \"super::unix_time::fractional::double_option\")] "
                + "pub started_at: Option<Option<jiff::Timestamp>>,");
        // what is no time, and what is no property itself, keep their number
        assertContains(event, "pub sequence: i64,");
        assertContains(event, "pub history: Option<Vec<i64>>,");
        // an inline object's properties are properties too
        String window = read(out.resolve("src/models/event_window.rs"));
        assertContains(window, "with = \"super::unix_time\")] pub opens_at: jiff::Timestamp,");
        assertContains(window, "with = \"super::unix_time::option\")] pub closes_at: Option<jiff::Timestamp>,");

        assertContains(read(out.resolve("src/models/mod.rs")), "pub mod unix_time {");
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        assertContains(cargo, "jiff = ");
        // the double options are the module's own: nothing asks for serde_with
        assertFalse(cargo.contains("serde_with"), cargo);
        // a parameter keeps its number
        assertContains(read(out.resolve("src/apis/events_api.rs")), "since: Option<i64>");
    }

    @Test
    void itStaysANumberWithChronoAndString(@TempDir Path chrono, @TempDir Path string) throws IOException {
        for (Map.Entry<String, Path> library : Map.of("chrono", chrono, "string", string).entrySet()) {
            Path out = library.getValue();
            generate(out, Map.of(RustCodegenBase.DATE_LIBRARY, library.getKey()));
            String event = read(out.resolve("src/models/event.rs"));
            assertContains(event, "pub created_at: i64,");
            assertContains(event, "pub expires_at: Option<i64>,");
            assertContains(event, "pub completed_at: f64,");
            assertFalse(event.contains("unix_time"), event);
            assertFalse(read(out.resolve("src/models/mod.rs")).contains("unix_time"));
            String cargo = Files.readString(out.resolve("Cargo.toml"));
            assertFalse(cargo.contains("jiff") || cargo.contains("chrono"), cargo);
        }
    }

    @Test
    void aModelNamedUnixTimeFailsGeneration(@TempDir Path out, @TempDir Path work) throws IOException {
        Path spec = work.resolve("unix_time_model.yaml");
        Files.writeString(spec, Files.readString(Path.of(SPEC)).replace("    Event:", "    UnixTime:")
                .replace("schemas/Event'", "schemas/UnixTime'"));
        // the generator wraps what a hook throws
        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> new DefaultGenerator().opts(new CodegenConfigurator()
                        .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                        .setInputSpec(spec.toString())
                        .setOutputDir(out.toString())
                        .setAdditionalProperties(Map.of("packageName", "unix_time"))
                        .toClientOptInput()).generate());
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertContains(cause.getMessage(), "the model UnixTime is the module models::unix_time");
    }

    /**
     * The generated client calls the generated server, and the JSON of each shape is checked: src/test/rust/unix_time.rs
     * as the crate's integration test, clippy on every target, and a check for {@code wasm32-unknown-unknown} when
     * that target is installed. Needs cargo.
     */
    @Test
    void theClientCallsTheServer(@TempDir Path out) throws IOException, InterruptedException {
        generate(out, Map.of(RustCodegenBase.DATE_LIBRARY, "jiff", TimvwRustCodegen.TARGETS,
                List.of("client", "server"), "validateMethods", "true", "bonBuilders", "true"));
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path manifest = out.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1", "json"] }
                tokio = { version = "1", features = ["rt-multi-thread", "macros", "net"] }
                """);
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/unix_time.rs"), out.resolve("tests/unix_time.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 1200, "test", "--all-features", "--test", "unix_time");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);

        String clippy = cargo(out, 900, "clippy", "--all-features", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "cargo did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error"), clippy);

        String sysroot = rustcSysroot(out);
        if (sysroot != null && Files.isDirectory(Path.of(sysroot, "lib", "rustlib", "wasm32-unknown-unknown"))) {
            String wasm = cargo(out, 900, "check", "--lib", "--no-default-features", "--features", "client",
                    "--target", "wasm32-unknown-unknown");
            assertNotNull(wasm, "cargo did not finish");
            assertTrue(wasm.contains("Finished") && !wasm.contains("error:") && !wasm.contains("error["), wasm);
        }
    }

    /** The sysroot of the toolchain cargo uses in {@code dir}, or {@code null}. */
    private static String rustcSysroot(Path dir) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("rustc", "--print", "sysroot").directory(dir.toFile())
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            return null;
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        return new String(process.getInputStream().readAllBytes()).trim();
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
