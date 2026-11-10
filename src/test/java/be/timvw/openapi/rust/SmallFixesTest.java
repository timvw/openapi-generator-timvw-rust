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
    private static final String NESTED_SPEC = "src/test/resources/nested_enum_containers.yaml";
    private static final String INTEGER_SPEC = "src/test/resources/integer_enum_containers.yaml";
    private static final String STREAM_SPEC = "src/test/resources/stream_without_typed_streams.yaml";
    private static final String UNION_SPEC = "src/test/resources/union_shared_tag_enum.yaml";
    private static final String UNION_MAPPING_SPEC = "src/test/resources/union_mapping_and_tag_values.yaml";

    @TempDir
    static Path mapEnums;

    @TempDir
    static Path nestedEnums;

    @TempDir
    static Path integerEnums;

    @TempDir
    static Path serdePath;

    @TempDir
    static Path streamWithoutTyped;

    @TempDir
    static Path streamTyped;

    @TempDir
    static Path union;

    @TempDir
    static Path unionMapping;

    @BeforeAll
    static void generate() {
        generate(MAP_SPEC, mapEnums, Map.of());
        generate(NESTED_SPEC, nestedEnums, Map.of());
        generate(INTEGER_SPEC, integerEnums, Map.of());
        generate(MAP_SPEC, serdePath, Map.of(RustCodegenBase.USE_SERDE_PATH_TO_ERROR, "true"));
        generate(STREAM_SPEC, streamWithoutTyped, Map.of(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true"));
        generate(STREAM_SPEC, streamTyped, Map.of(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true",
                TimvwRustCodegen.TYPED_EVENT_STREAMS, "true"));
        generate(UNION_SPEC, union, Map.of());
        generate(UNION_MAPPING_SPEC, unionMapping, Map.of());
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
        assertContains(readme, "```text\nattribute only, still rust\n```");
        assertContains(readme, "```text\nignored rust\n```");
        assertContains(readme, "- a list item\n\n  with a continuation paragraph\n\n  ```text\n  code inside the item\n  ```");
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

    /**
     * A mapping that names some values and branches whose tag enums hold others: a branch is selected by the mapping
     * keys that name it and by its own tag values the mapping does not give to another branch.
     */
    @Test
    void theMappingAddsToTheBranchesOwnTagValues() throws IOException, InterruptedException {
        String pet = Files.readString(unionMapping.resolve("src/models/pet.rs"));
        assertContains(pet, "Tag::Value(\"cat\" | \"kitty\" | \"feline\") => dispatch::decode(value, UNION, \"Cat\")");
        assertContains(pet, "Tag::Value(\"dog\" | \"puppy\") => dispatch::decode(value, UNION, \"Dog\")");
        assertClippyClean(unionMapping);
        Files.createDirectories(unionMapping.resolve("tests"));
        Files.copy(Path.of("src/test/rust/union_mapping.rs"), unionMapping.resolve("tests/union_mapping.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(unionMapping, 900, "test", "--test", "union_mapping");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
    }

    /**
     * An inline enum two or more lists or maps deep is typed at every level, generated once and named after the
     * property; {@code validate()} checks the size keywords of every list and map that holds it.
     */
    @Test
    void anInlineEnumNestedAtAnyDepthIsTypedAtEveryLevel() throws IOException, InterruptedException {
        String holder = Files.readString(nestedEnums.resolve("src/models/holder.rs"));
        String map = "std::collections::HashMap<String, ";
        assertContains(holder, "    pub map_of_maps: Option<" + map + map + "MapOfMaps>>>,\n");
        assertContains(holder, "    pub list_of_maps: Option<Vec<" + map + "ListOfMaps>>>,\n");
        assertContains(holder, "    pub map_of_lists: Option<" + map + "Vec<MapOfLists>>>,\n");
        assertContains(holder, "    pub map_of_lists_of_lists: Option<" + map + "Vec<Vec<MapOfListsOfLists>>>>,\n");
        assertContains(holder,
                "    pub list_of_maps_of_maps: Option<Vec<" + map + map + "ListOfMapsOfMaps>>>>,\n");
        assertContains(holder, "    pub required_grid: " + map + map + "RequiredGrid>>,\n");
        assertContains(holder, "pub fn new(required_grid: " + map + map + "RequiredGrid>>) -> Holder {");
        // three deep, through a component map of maps: clippy's type_complexity is allowed crate-wide
        assertContains(holder, "    pub grids: Option<" + map + map + map + "Grids>>>>,\n");
        assertContains(Files.readString(nestedEnums.resolve("src/lib.rs")), "#![allow(clippy::type_complexity)]\n");
        // uniqueItems: a set of the enum itself, a Vec (checked by validate) of anything that holds it
        assertContains(holder, "    pub unique_sets: Option<" + map + "std::collections::HashSet<UniqueSets>>>,\n");
        assertContains(holder, "    pub unique_lists: Option<Vec<Vec<UniqueLists>>>,\n");
        assertContains(holder, "crate::models::validation::unique_items(value0, &path.field(\"unique_lists\"), errors);");
        // each enum once, named after its property
        for (String name : List.of("MapOfMaps", "ListOfMaps", "MapOfLists", "MapOfListsOfLists", "ListOfMapsOfMaps",
                "RequiredGrid", "UniqueSets", "UniqueLists")) {
            assertEquals(1, holder.split("pub enum " + name + " \\{", -1).length - 1, name);
        }
        // the size keywords of every level are checked and documented
        assertContains(holder, "items(item2.len(), None, Some(2), "
                + "&path.field(\"map_of_lists_of_lists\").key(key1).index(index2), errors);");
        assertContains(holder, "properties(entry2.len(), None, Some(2), "
                + "&path.field(\"list_of_maps_of_maps\").index(index1).key(key2), errors);");
        assertContains(holder, "/// Validation: `minProperties: 1`; each value: `minItems: 1`; "
                + "each item of a value: `maxItems: 2`.");
        String doc = Files.readString(nestedEnums.resolve("docs/Holder.md"));
        assertContains(doc, "**map_of_maps** | Option<**" + map + map + "MapOfMaps>>**>");
        // an operation's inline enum stays a String, in containers as alone
        String api = Files.readString(nestedEnums.resolve("src/apis/default_api.rs"));
        assertContains(api, "Result<" + map + map + "String>>, Error<GetGridError>>");
        assertContains(api, "request_body: &[" + map + "String>]");
        assertClippyClean(nestedEnums);
        Files.createDirectories(nestedEnums.resolve("tests"));
        Files.copy(Path.of("src/test/rust/nested_enum_containers.rs"),
                nestedEnums.resolve("tests/nested_enum_containers.rs"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(nestedEnums, 900, "test", "--test", "nested_enum_containers");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
    }

    /**
     * An integer inline enum (int32 or int64) is {@code #[repr(i64)]} through {@code serde_repr} alone and as the
     * items or values of lists and maps at any depth, so {@code [1]} and {@code {"a": 2}} decode; it was a string
     * enum ({@code #[serde(rename = "1")]}) in a container, and for {@code format: int64} everywhere.
     */
    @Test
    void anIntegerInlineEnumIsAnIntegerEnumAtAnyDepth() throws IOException, InterruptedException {
        String holder = Files.readString(integerEnums.resolve("src/models/holder.rs"));
        String repr = "#[repr(i64)]\n#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize_repr, "
                + "Deserialize_repr)]\npub enum ";
        for (String name : List.of("Level", "Wide", "List", "Set", "Map", "RequiredMap", "MapOfLists",
                "ListOfMapsOfLists")) {
            assertContains(holder, repr + name + " {");
        }
        assertEquals(1, holder.split("use serde_repr::", -1).length - 1, holder);
        assertFalse(holder.contains("#[serde(rename = \"1\")]"), holder);
        // the types and validate() are those of any inline enum in containers
        assertContains(holder, "    pub set: Option<std::collections::HashSet<Set>>,\n");
        assertContains(holder, "    pub list_of_maps_of_lists: Option<Vec<std::collections::HashMap<String, "
                + "Vec<ListOfMapsOfLists>>>>,\n");
        assertContains(holder, "/// Validation: each value: `maxItems: 2`.");
        // a number enum stays a string enum; an int64 component enum is an integer enum
        assertContains(holder, "    #[serde(rename = \"1.5\")]\n    Variant15,\n");
        assertContains(Files.readString(integerEnums.resolve("src/models/big.rs")), repr + "Big {");
        assertContains(Files.readString(integerEnums.resolve("src/models/request.rs")), repr + "Codes {");
        assertClippyClean(integerEnums);
        Files.createDirectories(integerEnums.resolve("tests"));
        Files.copy(Path.of("src/test/rust/integer_enum_containers.rs"),
                integerEnums.resolve("tests/integer_enum_containers.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(integerEnums, 900, "test", "--test", "integer_enum_containers");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
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
