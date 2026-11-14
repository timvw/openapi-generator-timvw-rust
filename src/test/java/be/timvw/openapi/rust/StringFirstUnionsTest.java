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
 * A union whose first branch is a string next to a list is a typed enum wherever a property holds it: a reference
 * added by an {@code allOf} part, and a required {@code anyOf: [union, null]} (an {@code Option} of the union that
 * decodes {@code null}). A union of strings only keeps its mapping. {@code stringProperties} renders such a property
 * as a {@code String}, and keeps it nullable: a required nullable property is an {@code Option<String>} that decodes
 * {@code null}, in 3.0 ({@code nullable: true}) and 3.1 ({@code null} branch).
 */
class StringFirstUnionsTest {

    private static final String STRING_PROPERTIES = "CreateReply.input|Reply.instructions";

    @TempDir
    static Path unions;

    @TempDir
    static Path strings;

    @TempDir
    static Path strings30;

    @BeforeAll
    static void generate() {
        generate("src/test/resources/string_first_unions.yaml", unions, null);
        generate("src/test/resources/string_first_unions.yaml", strings, STRING_PROPERTIES);
        generate("src/test/resources/string_first_unions_30.yaml", strings30, STRING_PROPERTIES);
    }

    private static void generate(String spec, Path output, String stringProperties) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "string_first_unions");
        if (stringProperties != null) {
            props.put(TimvwRustCodegen.STRING_PROPERTIES, stringProperties);
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String model(Path output, String name) throws IOException {
        return Files.readString(output.resolve("src/models/" + name + ".rs"));
    }

    @Test
    void anAllOfPropertyReferencingAStringFirstUnionIsTheUnion() throws IOException {
        String input = model(unions, "input_param");
        assertContains(input, "    TextInput(String),\n");
        assertContains(input, "    InputItemList(Vec<models::Item>),\n");
        assertContains(model(unions, "create_reply"), "    pub input: Option<models::InputParam>,\n");
    }

    @Test
    void aRequiredNullableStringFirstUnionIsAnOptionOfTheUnion() throws IOException {
        String reply = model(unions, "reply");
        assertContains(reply, "    #[serde(rename = \"instructions\", deserialize_with = \"Option::deserialize\")]\n"
                + "    pub instructions: Option<models::ReplyAllOfInstructions>,\n");
        // the same convention as a required nullable string
        assertContains(reply, "    #[serde(rename = \"note\", deserialize_with = \"Option::deserialize\")]\n"
                + "    pub note: Option<String>,\n");
        String instructions = model(unions, "reply_all_of_instructions");
        assertContains(instructions, "    Text(String),\n");
        assertContains(instructions, "    InputItemList(Vec<models::Item>),\n");
    }

    @Test
    void aUnionOfStringsOnlyKeepsItsMapping() throws IOException {
        String modelId = model(unions, "model_id");
        assertContains(modelId, "    TextVariant(models::ModelIdTextVariantEnum),\n");
        assertContains(modelId, "    Text(String),\n");
        assertContains(model(unions, "create_reply"), "    pub model: Option<models::ModelId>,\n");
    }

    @Test
    void stringPropertiesKeepTheirNullability() throws IOException {
        for (Path output : List.of(strings, strings30)) {
            assertContains(model(output, "create_reply"), "    pub input: Option<String>,\n");
            String reply = model(output, "reply");
            assertContains(reply, "    #[serde(rename = \"instructions\", deserialize_with = \"Option::deserialize\")]\n"
                    + "    pub instructions: Option<String>,\n");
            assertFalse(reply.contains("pub instructions: String,"), reply);
            assertFalse(Files.exists(output.resolve("src/models/reply_all_of_instructions.rs")));
        }
    }

    @Test
    void theFieldsDecodeAndRoundTrip() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(unions, 30, "--version") != null, "no cargo");
        for (Map.Entry<Path, String> crate : Map.of(unions, "string_first_unions",
                strings, "string_first_unions_string_properties").entrySet()) {
            Path dir = crate.getKey();
            String clippy = cargo(dir, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            assertNotNull(clippy, "clippy did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                    && !clippy.contains("warning:"), clippy);
            Files.createDirectories(dir.resolve("tests"));
            Files.copy(Path.of("src/test/rust/" + crate.getValue() + ".rs"),
                    dir.resolve("tests/" + crate.getValue() + ".rs"), StandardCopyOption.REPLACE_EXISTING);
            String test = cargo(dir, 900, "test", "--test", crate.getValue());
            assertNotNull(test, "cargo did not finish");
            assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected\n" + needle + "\nin\n" + haystack);
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
