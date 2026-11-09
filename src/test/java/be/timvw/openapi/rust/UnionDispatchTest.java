package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 3.3: a union with a tag decodes by that tag. What the generated code does at run time is in
 * src/test/rust/union_dispatch.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class UnionDispatchTest {

    private static final String SPEC = "src/test/resources/union_dispatch.yaml";
    /** To update: run with -DargLine=-Dgolden.update=true and read the diff. */
    private static final Path GOLDEN = Path.of("src/test/resources/union_dispatch_golden");

    @TempDir
    static Path on;

    private static JsonNode plan;

    @BeforeAll
    static void generate() throws IOException {
        generate(SPEC, on);
        plan = new ObjectMapper().readTree(on.resolve("target/union_plan.json").toFile());
    }

    private static void generate(String spec, Path output) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "dispatch_fixture");
        props.put(TimvwRustCodegen.BON_BUILDERS, "false");
        props.put(TimvwRustCodegen.UNION_PLAN_FILE, "target/union_plan.json");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static JsonNode dispatch(String union) {
        for (JsonNode u : plan.get("unions")) {
            if (u.get("union").asText().equals(union)) {
                return u.get("dispatch");
            }
        }
        throw new AssertionError(union + " is not in the plan");
    }

    private static Map<String, List<String>> values(String union) {
        Map<String, List<String>> values = new TreeMap<>();
        dispatch(union).get("values").fields().forEachRemaining(e -> {
            List<String> variants = new ArrayList<>();
            e.getValue().forEach(v -> variants.add(v.asText()));
            values.put(e.getKey(), variants);
        });
        return values;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(n -> names.add(n.asText()));
        return names;
    }

    @Test
    void aDiscriminatorOnOneOfOrAnyOfIsATag() {
        assertEquals("kind", dispatch("Shape").get("tag").asText());
        assertEquals("discriminator", dispatch("Shape").get("source").asText());
        assertEquals(Map.of("circle", List.of("Circle"), "square", List.of("Square")), values("Shape"));
        assertEquals(Map.of("started", List.of("StartedEvent"), "stopped", List.of("StoppedEvent")), values("Event"));
    }

    @Test
    void aModelThatIsAllOfOneUnionHasItsTag() {
        assertEquals("type", dispatch("EventAlias").get("tag").asText());
        assertEquals(values("Event"), values("EventAlias"));
    }

    @Test
    void aRequiredPropertyWithFixedValuesOnEveryBranchIsATag() {
        assertEquals("implicit", dispatch("Part").get("source").asText());
        assertEquals("type", dispatch("Part").get("tag").asText());
    }

    @Test
    void aBranchThatIsAUnionCountsThroughItsArms() {
        assertEquals("implicit", dispatch("Content").get("source").asText());
        assertEquals(List.of("Part"), values("Content").get("text"));
        assertEquals(List.of("Tool"), values("Content").get("web_search_2025_08_26"));
    }

    @Test
    void anInlineBranchOfABranchCountsToo() {
        assertEquals("implicit", dispatch("Stream").get("source").asText());
        assertEquals("event", dispatch("Stream").get("tag").asText());
        assertEquals(List.of("RunEvent"), values("Stream").get("run.done"));
        assertEquals(List.of("DoneEvent"), values("Stream").get("done"));
    }

    @Test
    void thePropertyThatSharesFewestValuesIsTheTag() {
        assertEquals("object", dispatch("TwoCandidates").get("tag").asText());
    }

    @Test
    void whatHasNoTagIsNotDispatched() throws IOException {
        for (String union : List.of("Scalar", "NoCommonField", "SameRoles", "SomeOptional", "Mixed", "NumberedKinds")) {
            assertTrue(dispatch(union) == null, union);
            String source = read(on, snake(union));
            assertTrue(source.contains("#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]\n#[serde(untagged)]"),
                    source);
            assertFalse(source.contains("fn dispatch"), source);
        }
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void everyValueOfAVariantSelectsIt() {
        assertEquals(List.of("WebSearchTool"), values("Tool").get("web_search"));
        assertEquals(List.of("WebSearchTool"), values("Tool").get("web_search_2025_08_26"));
    }

    @Test
    void aValueOnTwoVariantsListsThemInDecodingOrder() {
        assertEquals(List.of("InputMessage", "OutputMessage"), values("Item").get("message"));
        assertEquals(List.of("EasyMessage", "Item"), values("InputItem").get("message"));
        assertEquals(List.of("Item"), values("InputItem").get("tool_call"));
    }

    @Test
    void anOptionalOrNullableTagAllowsItToBeMissing() {
        assertEquals(List.of("InputMessage"), names(dispatch("Item").get("missing")));
        assertEquals(List.of("EasyMessage", "Item", "ItemReference"), names(dispatch("InputItem").get("missing")));
        assertEquals(List.of(), names(dispatch("Shape").get("missing")));
    }

    @Test
    void aBranchThatIsNoObjectDecodesWhatIsNoObject() {
        assertEquals(List.of("TextVariant", "ArrayOfStrings"), names(dispatch("ToolChoice").get("nonObject")));
        assertEquals(Map.of("named", List.of("NamedChoice")), values("ToolChoice"));
    }

    @Test
    void itSerializesAsBeforeAndEachStructKeepsItsTag() throws IOException {
        String shape = read(on, "shape");
        assertTrue(shape.contains("#[derive(Clone, Debug, PartialEq, Serialize)]\n#[serde(untagged)]\npub enum Shape {"), shape);
        assertTrue(shape.contains("impl<'de> Deserialize<'de> for Shape {"), shape);
        assertTrue(read(on, "circle").contains("#[serde(rename = \"kind\")]\n    pub kind: Kind,"));
    }

    @Test
    void goldens() throws IOException {
        for (String file : List.of("shape", "tool", "item", "input_item", "tool_choice", "content", "union_dispatch")) {
            Path golden = GOLDEN.resolve(file + ".rs");
            String generated = read(on, file);
            if (Boolean.getBoolean("golden.update")) {
                Files.createDirectories(GOLDEN);
                Files.writeString(golden, generated);
            }
            assertEquals(Files.readString(golden), generated, file);
        }
    }

    @Test
    void theHelperIsDeclaredOnceAndNotPublic() throws IOException {
        String mod = Files.readString(on.resolve("src/models/mod.rs"));
        assertEquals(1, mod.split("pub\\(crate\\) mod union_dispatch;", -1).length - 1, mod);
    }

    @Test
    void aVariantWithoutTheFieldFailsGeneration(@TempDir Path output) {
        String message = failure("src/test/resources/union_dispatch_no_field.yaml", output);
        assertTrue(message.contains("Broken is tagged on 'type', but its variant WithoutTag holds models::WithoutTag, "
                + "which has no such field"), message);
    }

    @Test
    void aVariantNoValueSelectsFailsGeneration(@TempDir Path output) {
        String message = failure("src/test/resources/union_dispatch_free_tag.yaml", output);
        assertTrue(message.contains("Free is tagged on 'type', but no value selects its variant Open"), message);
    }

    @Test
    void aSchemaWithTheNameOfTheHelperFailsGeneration(@TempDir Path output) {
        String message = assertThrows(IllegalStateException.class,
                () -> generate("src/test/resources/union_dispatch_reserved.yaml", output)).getMessage();
        assertTrue(message.contains("unionDispatch needs the module union_dispatch"), message);
    }

    private static String failure(String spec, Path output) {
        return assertThrows(IllegalArgumentException.class, () -> generate(spec, output)).getMessage();
    }

    /**
     * Compiles the models of the fixture with the dependencies they need and runs src/test/rust/union_dispatch.rs: the
     * tag decodes the variant, the tag goes out once, errors, numbers. Needs cargo; without it there is nothing to run.
     */
    @Test
    void whatItDoesAtRunTime(@TempDir Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        Files.writeString(crate.resolve("Cargo.toml"), """
                [package]
                name = "dispatch_fixture"
                version = "0.0.0"
                edition = "2021"

                [dependencies]
                serde = { version = "1", features = ["derive"] }
                serde_json = "1"
                serde_repr = "0.1"
                serde_with = { version = "3", default-features = false, features = ["base64", "std", "macros"] }
                """);
        Files.createDirectories(crate.resolve("src/models"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("src/lib.rs"), "#![allow(clippy::all)]\npub mod models;\n");
        try (Stream<Path> files = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, crate.resolve("src/models").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/rust/union_dispatch.rs"), crate.resolve("tests/union_dispatch.rs"));
        String output = cargo(crate, 600, "test", "--test", "union_dispatch");
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
