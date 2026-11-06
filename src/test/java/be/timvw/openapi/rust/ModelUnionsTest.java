package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.3: modelUnions renders unions from the model. */
class ModelUnionsTest {

    @TempDir
    static Path on;

    @TempDir
    static Path qualified;

    @TempDir
    static Path legacy;

    @TempDir
    static Path off;

    @BeforeAll
    static void generate() {
        generate(on, true, false, false);
        generate(qualified, true, true, false);
        generate(legacy, true, false, true);
        generate(off, false, false, false);
    }

    private static void generate(Path output, boolean modelUnions, boolean qualifiedNames, boolean rewrites) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "unions");
        props.put(TimvwRustCodegen.BOX_ONLY_CYCLES, "true");
        props.put(TimvwRustCodegen.DEFAULT_ONLY_WHERE_VALID, "true");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        props.put(TimvwRustCodegen.JSON_VALUE_SCHEMAS, "Whatever");
        if (modelUnions) {
            props.put(TimvwRustCodegen.MODEL_UNIONS, "true");
            props.put(TimvwRustCodegen.UNION_PLAN_FILE, "target/union_plan.json");
        }
        if (qualifiedNames) {
            props.put(TimvwRustCodegen.QUALIFIED_INLINE_NAMES, "true");
        }
        if (rewrites) {
            props.put(TimvwRustCodegen.LEGACY_MODEL_REWRITES, "true");
        }
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/unions.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    /** The variants of an enum, in order, as {@code Name(Payload)}. */
    private static List<String> variants(String source, String name) {
        Matcher m = Pattern.compile("pub enum " + name + " \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), source);
        List<String> variants = new ArrayList<>();
        for (String line : m.group(1).split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("///")) {
                variants.add(trimmed.replaceAll(",$", ""));
            }
        }
        return variants;
    }

    private static List<String> names(List<String> variants) {
        return variants.stream().map(v -> v.replaceAll("\\(.*", "")).toList();
    }

    @Test
    void aDiscriminatedOneOfIsUntaggedSoTheTagIsWrittenOnce() throws IOException {
        String message = read(on, "message");
        assertTrue(message.contains("#[serde(untagged)]"), message);
        assertFalse(message.contains("serde(tag"), message);
        assertEquals(List.of("System(models::SystemMessage)", "User(models::UserMessage)"),
                variants(message, "Message"));
        // Structs keep their tag field: it is what goes on the wire.
        assertTrue(read(on, "system_message").contains("pub role: Role,"));
    }

    @Test
    void aDiscriminatedAnyOfHasItsVariants() throws IOException {
        String event = read(on, "server_event");
        assertTrue(event.contains("#[serde(untagged)]"), event);
        // A tag tells them apart, so they stay in the order of the spec.
        assertEquals(List.of("StartedEvent(models::StartedEvent)", "StoppedEvent(models::StoppedEvent)"),
                variants(event, "ServerEvent"));
        assertFalse(read(off, "server_event").contains("StartedEvent"), "stock leaves a discriminated anyOf empty");
    }

    @Test
    void aModelThatIsAllOfOneUnionHasThatUnionsVariants() throws IOException {
        String alias = read(on, "server_event_alias");
        assertTrue(alias.contains("#[serde(untagged)]"), alias);
        assertEquals(List.of("StartedEvent(models::StartedEvent)", "StoppedEvent(models::StoppedEvent)"),
                variants(alias, "ServerEventAlias"));
    }

    @Test
    void aNullableReferenceDoesNotRewriteItsTarget() throws IOException {
        for (Path output : List.of(on, legacy)) {
            String log = read(output, "log");
            assertTrue(log.contains("pub actor: Option<models::LogActor>,"), log);
            assertTrue(log.contains("pub error: Option<Option<models::LogError>>,"), log);
            assertTrue(read(output, "log_actor").contains("pub struct LogActor {"));
            assertEquals(List.of("ProtocolError(models::ProtocolError)", "HttpError(models::HttpError)"),
                    variants(read(output, "log_error"), "LogError"));
        }
    }

    @Test
    void anObjectNextToNullIsAStruct() throws IOException {
        for (Path output : List.of(on, legacy)) {
            String prompt = read(output, "prompt");
            assertTrue(prompt.contains("pub struct Prompt {"), prompt);
            assertTrue(prompt.contains("pub id: String,"), prompt);
        }
    }

    @Test
    void anInlineBranchKeepsItsOwnModel() throws IOException {
        assertEquals(List.of("Pcm(models::Pcm)", "Ulaw(models::Ulaw)"), variants(read(on, "format"), "Format"));
        assertTrue(read(on, "pcm").contains("pub rate: i32,"));
        // Qualified names carry the union's name, which the variant does not repeat.
        assertEquals(List.of("Pcm", "Ulaw"), names(variants(read(qualified, "format"), "Format")));
        assertTrue(read(qualified, "format").contains("Pcm(models::FormatPcm)"));
        assertTrue(read(qualified, "format_pcm").contains("pub rate: i32,"));
    }

    @Test
    void theIntegerBranchSurvivesAnEnumNextToIt() throws IOException {
        String batchSize = read(on, "log_batch_size");
        assertEquals(List.of("TextVariant(models::LogBatchSizeTextVariantEnum)", "Integer(i32)"),
                variants(batchSize, "LogBatchSize"));
        String values = read(on, "log_batch_size_text_variant_enum");
        assertTrue(values.contains("#[serde(rename = \"auto\")]"), values);
        // An enum is an ordinary enum, declared in mod.rs.
        String mod = Files.readString(on.resolve("src/models/mod.rs"));
        assertTrue(mod.contains("pub use self::log_batch_size_text_variant_enum::LogBatchSizeTextVariantEnum;"), mod);
    }

    @Test
    void anIntegerGoesBeforeAFloatAndAnEnumBeforeAString() throws IOException {
        assertEquals(List.of("Integer(i32)", "Number(f64)"), variants(read(on, "log_temperature"), "LogTemperature"));
        assertEquals(List.of("TextVariant(models::ModelChoiceTextVariantEnum)", "Text(String)"),
                variants(read(on, "model_choice"), "ModelChoice"));
    }

    @Test
    void aUnionWithARicherArmGoesBeforeAStructItCouldLoseFieldsTo() throws IOException {
        // Item's arm InputMessage and EasyMessage decode each other's values. They tie on fields but for status,
        // which InputMessage has; ItemReference takes neither's values and stays where the spec has it.
        assertEquals(List.of("ItemReference", "Item", "EasyMessage"),
                names(variants(read(on, "input_item"), "InputItem")));
        assertEquals(List.of("InputMessage", "ToolCall"), names(variants(read(on, "item"), "Item")));
    }

    @Test
    void aStructGoesBeforeOneThatWouldDecodeItsValues() throws IOException {
        // Notice requires message; Warning requires message and code, so Notice would swallow a warning.
        assertEquals(List.of("Warning", "Notice", "Plain"), names(variants(read(on, "alert"), "Alert")));
        // Fixed tags with other values tell these two apart, however their fields compare.
        assertEquals(List.of("Short", "Long"), names(variants(read(on, "tagged"), "Tagged")));
    }

    @Test
    void arraysSharingATitleAreNamedAfterTheirItems() throws IOException {
        assertEquals(List.of("Text", "ArrayOfStrings", "ArrayOfIntegers", "ArrayOfIntegerArrays"),
                names(variants(read(on, "input_value"), "InputValue")));
    }

    @Test
    void whatAcceptsEverythingComesLast() throws IOException {
        List<String> variants = variants(read(on, "anything"), "Anything");
        assertEquals(List.of("Numbers", "ArrayOfItems", "LogActor", "Object1", "Whatever"), names(variants));
        assertEquals("Whatever(serde_json::Value)", variants.get(4));
    }

    @Test
    void aTitleRepeatedInsideItsUnionDoesNotMakeTheUnionHoldItself() throws IOException {
        for (Path output : List.of(on, qualified)) {
            try (Stream<Path> models = Files.list(output.resolve("src/models"))) {
                for (Path file : (Iterable<Path>) models::iterator) {
                    String source = Files.readString(file);
                    Matcher m = Pattern.compile("pub enum (\\w+) \\{").matcher(source);
                    while (m.find()) {
                        assertFalse(source.contains("(models::" + m.group(1) + ")"), file + ": " + source);
                        assertFalse(source.contains("Box<models::" + m.group(1) + ">"), file + ": " + source);
                    }
                }
            }
        }
        String fields = read(on, "tracing_configuration_object");
        assertTrue(fields.contains("pub workflow_name: Option<String>,"), fields);
        assertTrue(read(on, "tracing_configuration").contains("(models::TracingConfigurationObject)"));
    }

    @Test
    void nothingIsEmptyAndNoUnionHasADefault() throws IOException {
        try (Stream<Path> models = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) models::iterator) {
                String source = Files.readString(file);
                assertFalse(Pattern.compile("pub enum \\w+ \\{\\s*\\}").matcher(source).find(), file.toString());
                if (source.contains("#[serde(untagged)]")) {
                    assertFalse(source.contains("impl Default"), file.toString());
                }
            }
        }
    }

    @Test
    void aStringVariantGoesOutAsPlainText() throws IOException {
        String display = read(on, "model_choice");
        assertTrue(display.contains("Self::Text(value) => write!(f, \"{}\", value),"), display);
        assertTrue(display.contains("Self::TextVariant(value) => write!(f, \"{}\", value),"), display);
        // An optional union part goes through that Display on 7.25, where stock JSON-encodes it, and on 7.15, where
        // every part does.
        String api = Files.readString(on.resolve("src/apis/default_api.rs"));
        assertFalse(api.contains("multipart_form.text(\"model\", serde_json::to_string"), api);
        assertTrue(api.contains("multipart_form.text(\"model\", param_value.to_string())"), api);
        assertTrue(api.contains("multipart_form.text(\"title\", p_form_title.to_string())"), api);
    }

    @Test
    void aJsonVariantPrintsItsJson() throws IOException {
        String display = read(on, "user_message_content");
        assertTrue(display.contains("Self::TextContent(value) => write!(f, \"{}\", value),"), display);
        assertTrue(display.contains("Self::ArrayOfParts(value) => match serde_json::to_string(value) {"), display);
    }

    @Test
    void helpersKeepTheirNames() throws IOException {
        String content = read(on, "user_message_content");
        assertTrue(content.contains("pub fn new_text(text: String) -> Self {"), content);
        assertTrue(content.contains("Self::TextContent(text)"), content);
        assertTrue(content.contains("pub fn new_arrayofparts(items: Vec<String>) -> Self {"), content);
        assertTrue(content.contains("impl From<String> for UserMessageContent {"), content);
        assertTrue(content.contains("impl From<&str> for UserMessageContent {"), content);
    }

    @Test
    void aCycleThroughAUnionIsBoxedOnlyThere() throws IOException {
        assertEquals(List.of("Leaf(models::Leaf)", "Branch(Box<models::Branch>)"), variants(read(on, "tree"), "Tree"));
        String branch = read(on, "branch");
        assertTrue(branch.contains("pub left: Box<models::Tree>,"), branch);
    }

    @Test
    void theRewritesDoNotTouchAModelUnion() throws IOException {
        assertEquals(variants(read(on, "message"), "Message"), variants(read(legacy, "message"), "Message"));
        assertEquals(variants(read(on, "format"), "Format"), variants(read(legacy, "format"), "Format"));
        assertTrue(read(legacy, "log_actor").contains("pub struct LogActor {"));
    }

    @Test
    void thePlanNamesEveryVariantWithItsBranch() throws IOException {
        JsonNode plan = new ObjectMapper().readTree(on.resolve("target/union_plan.json").toFile());
        JsonNode message = null;
        for (JsonNode union : plan.get("unions")) {
            if (union.get("union").asText().equals("Message")) {
                message = union;
            }
        }
        assertEquals("role", message.get("tag").asText());
        assertEquals("System", message.get("variants").get(0).get("name").asText());
        assertEquals("#/components/schemas/SystemMessage", message.get("variants").get(0).get("branch").get("$ref").asText());
        assertTrue(plan.get("schemas").has("SystemMessage"));
    }

    @Test
    void withoutTheOptionNothingChanges() throws IOException {
        String message = read(off, "message");
        assertTrue(message.contains("#[serde(tag = \"role\")]"), message);
        assertFalse(Files.exists(off.resolve("target/union_plan.json")));
    }

    @Test
    void aVariantHoldingAnUntypedAnyOfHoldsJsonValues() throws IOException {
        String union = Files.readString(on.resolve("src/models/untyped_payload.rs"));
        assertFalse(union.contains("AnyOfLessThanGreaterThan"), union);
        assertTrue(union.contains("serde_json::Value"), union);
    }
}
