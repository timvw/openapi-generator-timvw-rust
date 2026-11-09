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
    private static final String UNION_SPEC = "src/test/resources/union_shared_tag_enum.yaml";

    @TempDir
    static Path mapEnums;

    @TempDir
    static Path serdePath;

    @TempDir
    static Path streamWithoutTyped;

    @TempDir
    static Path streamTyped;

    @TempDir
    static Path union;

    @BeforeAll
    static void generate() {
        generate(MAP_SPEC, mapEnums, Map.of());
        generate(MAP_SPEC, serdePath, Map.of(RustCodegenBase.USE_SERDE_PATH_TO_ERROR, "true"));
        generate(STREAM_SPEC, streamWithoutTyped, Map.of(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true"));
        generate(STREAM_SPEC, streamTyped, Map.of(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true",
                TimvwRustCodegen.TYPED_EVENT_STREAMS, "true"));
        generate(UNION_SPEC, union, Map.of());
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
        // One level each way: a map of lists, and a list of maps.
        assertContains(holder, "    pub array_labels: Option<std::collections::HashMap<String, Vec<ArrayLabels>>>,\n");
        assertContains(holder, "    pub label_maps: Option<Vec<std::collections::HashMap<String, LabelMaps>>>,\n");
        assertClippyClean(mapEnums);
        Files.createDirectories(mapEnums.resolve("tests"));
        Files.copy(Path.of("src/test/rust/small_fixes.rs"), mapEnums.resolve("tests/small_fixes.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(mapEnums, 900, "test", "--test", "small_fixes");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
    }

    /**
     * lib.rs includes the README as the crate's documentation, so its code blocks are doctests unless marked as
     * another language: the Cargo.toml line is {@code toml}, the cargo command {@code sh}, the description's Rust,
     * unlabelled and indented code blocks {@code text}, and {@code cargo test --doc} passes on a crate generated
     * without options.
     */
    @Test
    void theReadmeIsNoFailingDoctest() throws IOException, InterruptedException {
        String readme = Files.readString(mapEnums.resolve("README.md"));
        assertContains(readme, "```toml\nsmall_fixes_fixture = { path = \"./small_fixes_fixture\" }\n```");
        assertContains(readme, "```sh\ncargo doc --open\n```");
        // The description's code blocks: a bare fence, a rust one and an indented block become text; json stays.
        assertContains(readme, "```text\ncurl https://example.com/x\n```");
        assertContains(readme, "```text\nthis is not rust either\n```");
        assertContains(readme, "```json\n{\"stays\": \"json\"}\n```");
        assertContains(readme, "```text\nGET /x HTTP/1.1\nHost: example.com\n```");
        assertContains(readme, "- a list item\n\n    with an indented continuation");
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

    /**
     * With typedEventStreams, a stream whose schema models the SSE envelope gets no typed stream (the generator logs
     * it): neither the operation's own method nor the one for another request media type returns an EventStream,
     * while an ordinary stream does in both.
     */
    @Test
    void anEnvelopeStreamGetsNoStreamInAnyMethod() throws IOException, InterruptedException {
        String api = Files.readString(streamTyped.resolve("src/apis/default_api.rs"));
        assertContains(api, "pub async fn create_envelopes_form(configuration: &configuration::Configuration, "
                + "name: Option<&str>) -> Result<models::Envelope, Error<CreateEnvelopesError>> {");
        assertContains(api, "pub async fn create_envelopes(configuration: &configuration::Configuration, "
                + "req: &models::Req) -> Result<models::Envelope, Error<CreateEnvelopesError>> {");
        assertContains(api, "pub async fn create_events_form(configuration: &configuration::Configuration, "
                + "name: Option<&str>) -> Result<super::sse::EventStream<models::Event, CreateEventsError>, "
                + "Error<CreateEventsError>> {");
        assertClippyClean(streamTyped);
    }

    /**
     * A discriminated union whose branches share one enum of every tag value: the discriminator mapping says which
     * value selects which branch, so each tag decodes its own branch (not both through first_of), and the dispatch
     * helpers nothing calls are left out (no dead code).
     */
    @Test
    void theMappingPicksTheBranchWhenBranchesShareTheTagEnum() throws IOException, InterruptedException {
        String pet = Files.readString(union.resolve("src/models/pet.rs"));
        assertContains(pet, "Tag::Value(\"cat\") => dispatch::decode(value, UNION, \"Cat\").map(Self::Cat),");
        assertContains(pet, "Tag::Value(\"dog\") => dispatch::decode(value, UNION, \"Dog\").map(Self::Dog),");
        String helper = Files.readString(union.resolve("src/models/union_dispatch.rs"));
        assertFalse(helper.contains("fn first_of"), helper);
        assertClippyClean(union);
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
