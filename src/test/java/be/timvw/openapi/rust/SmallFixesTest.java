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
 * Renderings that did not compile or did not pass clippy, each on a small spec, and each crate checked with
 * {@code cargo clippy -D warnings}: a map of an inline enum, {@code useSerdePathToError}, and an event stream with
 * {@code contentTypeMethods} but without {@code typedEventStreams}; and the README's doctests.
 */
class SmallFixesTest {

    private static final String MAP_SPEC = "src/test/resources/map_enum_values.yaml";
    private static final String STREAM_SPEC = "src/test/resources/stream_without_typed_streams.yaml";

    @TempDir
    static Path mapEnums;

    @TempDir
    static Path serdePath;

    @TempDir
    static Path streamWithoutTyped;

    @BeforeAll
    static void generate() {
        generate(MAP_SPEC, mapEnums, Map.of());
        generate(MAP_SPEC, serdePath, Map.of(RustCodegenBase.USE_SERDE_PATH_TO_ERROR, "true"));
        generate(STREAM_SPEC, streamWithoutTyped, Map.of(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true"));
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "small_fixes_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected\n" + needle + "\nin\n" + haystack);
    }

    /** additionalProperties with an inline enum: a map of the enum, not the enum itself. */
    @Test
    void aMapOfAnInlineEnumIsAMapOfTheEnum() throws IOException, InterruptedException {
        String holder = Files.readString(mapEnums.resolve("src/models/holder.rs"));
        assertContains(holder, "    pub labels: Option<std::collections::HashMap<String, Labels>>,\n");
        assertContains(holder, "    pub required_labels: std::collections::HashMap<String, RequiredLabels>,\n");
        assertContains(holder,
                "pub fn new(required_labels: std::collections::HashMap<String, RequiredLabels>) -> Holder {");
        assertContains(holder, "pub enum Labels {");
        assertClippyClean(mapEnums);
    }

    /**
     * lib.rs includes the README as the crate's documentation, so its code blocks are doctests unless marked as
     * another language: the Cargo.toml line is {@code toml} and the cargo command {@code sh}, and
     * {@code cargo test --doc} passes on a crate generated without options.
     */
    @Test
    void theReadmeIsNoFailingDoctest() throws IOException, InterruptedException {
        String readme = Files.readString(mapEnums.resolve("README.md"));
        assertContains(readme, "```toml\nsmall_fixes_fixture = { path = \"./small_fixes_fixture\" }\n```");
        assertContains(readme, "```sh\ncargo doc --open\n```");
        // Every block is opened with a language: the bare fences are the closing ones.
        assertEquals(readme.split("```[a-z]+\n", -1).length - 1, readme.split("```\n", -1).length - 1, readme);
        Assumptions.assumeTrue(cargo(mapEnums, 30, "--version") != null, "no cargo");
        String doc = cargo(mapEnums, 900, "test", "--doc");
        assertNotNull(doc, "cargo did not finish");
        assertTrue(doc.contains("test result: ok.") && !doc.contains("FAILED"), doc);
    }

    /** useSerdePathToError: Display formats the path and the error themselves (clippy to_string_in_format_args). */
    @Test
    void useSerdePathToErrorPassesClippy() throws IOException, InterruptedException {
        String mod = Files.readString(serdePath.resolve("src/apis/mod.rs"));
        assertContains(mod, "Error::SerdePathToError(e) => (\"serde\", format!(\"{}: {}\", e.path(), e.inner())),");
        assertClippyClean(serdePath);
    }

    /**
     * Without typedEventStreams an event stream is decoded as JSON in every method of the operation, also the one
     * for another request media type, and no method refers to the sse module that is not written.
     */
    @Test
    void withoutTypedEventStreamsNoMethodReturnsAStream() throws IOException, InterruptedException {
        String api = Files.readString(streamWithoutTyped.resolve("src/apis/default_api.rs"));
        assertContains(api, "pub async fn create_events_form(configuration: &configuration::Configuration, "
                + "name: Option<&str>) -> Result<models::Event, Error<CreateEventsError>> {");
        assertFalse(api.contains("sse::"), api);
        assertFalse(Files.exists(streamWithoutTyped.resolve("src/apis/sse.rs")));
        assertClippyClean(streamWithoutTyped);
    }

    private static void assertClippyClean(Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        String clippy = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                && !clippy.contains("warning:"), clippy);
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
