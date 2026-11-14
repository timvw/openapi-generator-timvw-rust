package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code optionalProperties} drops {@code Schema.property} entries from the {@code required} lists of the schema and
 * of its inline {@code allOf} parts, for properties a spec marks required that the API omits: such a property is an
 * {@code Option} skipped when {@code None}, and a required nullable one becomes an optional nullable one, an
 * {@code Option<Option<T>>}. An entry that names no property fails generation.
 */
class OptionalPropertiesTest {

    private static final String SPEC = "src/test/resources/optional_properties.yaml";
    private static final String SPEC_30 = "src/test/resources/optional_properties_30.yaml";
    private static final String OPTIONAL_PROPERTIES =
            "OutputText.logprobs|Usage.cache_write_tokens|Reply.access_programs";

    @TempDir
    static Path required;

    @TempDir
    static Path optional;

    @TempDir
    static Path optional30;

    @BeforeAll
    static void generate() {
        generate(SPEC, required, null);
        generate(SPEC, optional, OPTIONAL_PROPERTIES);
        generate(SPEC_30, optional30, OPTIONAL_PROPERTIES);
    }

    private static void generate(String spec, Path output, Object optionalProperties) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "optional_properties");
        props.put(TimvwRustCodegen.PREPROCESSED_SPEC_FILE, "target/specs/preprocessed.json");
        if (optionalProperties != null) {
            props.put(TimvwRustCodegen.OPTIONAL_PROPERTIES, optionalProperties);
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
    void withoutTheOptionThePropertiesAreRequired() throws IOException {
        assertContains(model(required, "output_text"), "    pub logprobs: Vec<f64>,\n");
        assertContains(model(required, "usage"), "    pub cache_write_tokens: i64,\n");
        assertContains(model(required, "reply"),
                "    #[serde(rename = \"access_programs\", deserialize_with = \"Option::deserialize\")]\n"
                        + "    pub access_programs: Option<Vec<String>>,\n");
    }

    @Test
    void aListedPropertyIsAnOptionSkippedWhenNone() throws IOException {
        for (Path output : List.of(optional, optional30)) {
            String text = model(output, "output_text");
            assertContains(text, "    #[serde(rename = \"logprobs\", skip_serializing_if = \"Option::is_none\")]\n"
                    + "    pub logprobs: Option<Vec<f64>>,\n");
            // the other required property stays required
            assertContains(text, "    pub text: String,\n");
            assertContains(text, "    pub fn new(text: String) -> OutputText {\n");
        }
    }

    @Test
    void aPropertyOfAnAllOfPartIsOptional() throws IOException {
        for (Path output : List.of(optional, optional30)) {
            String usage = model(output, "usage");
            assertContains(usage, "    #[serde(rename = \"cache_write_tokens\", skip_serializing_if = \"Option::is_none\")]\n"
                    + "    pub cache_write_tokens: Option<i64>,\n");
            assertContains(usage, "    pub cached_tokens: i64,\n");
            assertContains(usage, "    pub input_tokens: i64,\n");
        }
    }

    @Test
    void aRequiredNullablePropertyBecomesAnOptionalNullableOne() throws IOException {
        for (Path output : List.of(optional, optional30)) {
            String reply = model(output, "reply");
            assertContains(reply, "    #[serde(rename = \"access_programs\", default, "
                    + "with = \"::serde_with::rust::double_option\", skip_serializing_if = \"Option::is_none\")]\n"
                    + "    pub access_programs: Option<Option<Vec<String>>>,\n");
        }
    }

    @Test
    void thePreprocessedSpecHasTheRequiredListsWithoutTheEntries() throws IOException {
        for (Path output : List.of(optional, optional30)) {
            String json = Files.readString(output.resolve("target/specs/preprocessed.json"));
            assertContains(json, "\"required\" : [ \"text\" ]");
            assertContains(json, "\"required\" : [ \"cached_tokens\" ]");
            assertContains(json, "\"required\" : [ \"id\", \"output\", \"usage\" ]");
        }
        String json = Files.readString(required.resolve("target/specs/preprocessed.json"));
        assertContains(json, "\"required\" : [ \"logprobs\", \"text\" ]");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Reply.nope", "Nope.id", "Reply"})
    void anEntryThatNamesNoPropertyFailsGeneration(String entry, @TempDir Path out) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(SPEC, out, List.of("OutputText.logprobs", entry)));
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        String expected = entry.contains(".")
                ? "timvw-rust: optionalProperties: " + entry + " names no property of a component schema "
                        + "(nor of its inline allOf parts); remove it or correct its name"
                : "timvw-rust: optionalProperties entry must be Schema.property: " + entry;
        assertEquals(expected, cause.getMessage());
    }

    @Test
    void theFieldsDecodeAndRoundTrip() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(optional, 30, "--version") != null, "no cargo");
        String clippy = cargo(optional, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                && !clippy.contains("warning:"), clippy);
        Files.createDirectories(optional.resolve("tests"));
        Files.copy(Path.of("src/test/rust/optional_properties.rs"), optional.resolve("tests/optional_properties.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(optional, 900, "test", "--test", "optional_properties");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
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
