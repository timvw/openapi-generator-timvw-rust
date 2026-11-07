package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With modelUnions, a multipart union with file variants uploads the files instead of sending their paths as text. */
class MultipartFileUnionsTest {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @BeforeAll
    static void generate() {
        generate("multipart_unions.yaml", on, true);
        generate("multipart_unions.yaml", off, false);
    }

    private static void generate(String spec, Path output, boolean modelUnions) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "multipart_unions");
        props.put("supportAsync", "true");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        props.put(TimvwRustCodegen.BOX_ONLY_CYCLES, "true");
        if (modelUnions) {
            props.put(TimvwRustCodegen.MODEL_UNIONS, "true");
            props.put(TimvwRustCodegen.QUALIFIED_INLINE_NAMES, "true");
        }
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/" + spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String api(Path root, String module) throws IOException {
        return Files.readString(root.resolve("src/apis/" + module + "_api.rs")).replaceAll("\\s+", " ");
    }

    private static final String ADD_VIDEO_FILE =
            "multipart_form = multipart_helper::add_file_to_form(multipart_form, value, \"video\")?;";

    @Test
    void aRequiredUnionMatchesAndItsFileGoesThroughTheHelper() throws IOException {
        String api = api(on, "videos");
        assertTrue(api.contains("match &p_form_video { models::VideoBodyVideo::Text(value) => { " + ADD_VIDEO_FILE + " }"),
                api);
        assertFalse(api.contains("multipart_form.text(\"video\""), api);
    }

    @Test
    void anOptionalUnionMatchesInsideTheOption() throws IOException {
        String api = api(on, "videos");
        assertTrue(api.contains("if let Some(ref param_value) = p_form_reference { match param_value {"), api);
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"reference\")?;"), api);
        assertFalse(api.contains("multipart_form.text(\"reference\""), api);
    }

    @Test
    void anObjectVariantIsOneJsonPart() throws IOException {
        String api = api(on, "videos");
        assertTrue(api.contains("models::VideoBodyVideo::Reference(value) => { multipart_form = "
                + "multipart_helper::add_schema_value_to_form( multipart_form, \"video\", "
                + "serde_json::to_value(value)?, None, )?; }"), api);
    }

    @Test
    void anArraySendsOnePartPerFileUnderTheDocumentedName() throws IOException {
        String api = api(on, "skills");
        assertTrue(api.contains("for path in value { multipart_form = multipart_helper::add_file_to_form(multipart_form, "
                + "path, \"files[]\")?; }"), api);
        // A single file keeps the property's name, as the spec documents the brackets only for the array.
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"files\")?;"), api);
    }

    @Test
    void anArrayKeepsThePropertyNameWhenTheSpecDocumentsNoOther() throws IOException {
        String api = api(on, "skills");
        String version = api.substring(api.indexOf("pub async fn skill_version_create"));
        assertTrue(version.contains("for path in value { multipart_form = multipart_helper::add_file_to_form("
                + "multipart_form, path, \"files\")?; }"), version);
        assertFalse(version.contains("files[]"), version);
    }

    @Test
    void aModuleWithOnlyFileUnionsImportsTheHelper() throws IOException {
        // No operation decodes a response, so ContentType is not imported (Imports).
        assertTrue(api(on, "skills").contains("use super::{Error, configuration, multipart_helper};"), api(on, "skills"));
    }

    @Test
    void anEncodingContentTypeIsForTheOtherVariantsNotTheFile() throws IOException {
        String api = api(on, "mixed");
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"encoded\")?;"), api);
        assertTrue(api.contains("\"encoded\", serde_json::to_value(value)?, Some(\"application/vnd.fixture+json\"), )?;"),
                api);
    }

    @Test
    void aVariantThatIsNotAFileGoesOutAsASchemaValue() throws IOException {
        String api = api(on, "mixed");
        // The helper sends a JSON string as its plain text and a number as its digits.
        assertTrue(api.contains("models::MixedCreateRequestLabelled::Text2(value) => { multipart_form = "
                + "multipart_helper::add_schema_value_to_form( multipart_form, \"labelled\", "
                + "serde_json::to_value(value)?, None, )?; }"), api);
        assertTrue(api.contains("\"source\", serde_json::to_value(value)?, None, )?;"), api);
    }

    @Test
    void aRequiredNullableUnionKeepsTheStockEmptyPart() throws IOException {
        String api = api(on, "mixed");
        assertTrue(api.contains("match &p_form_nullable_source { None => { multipart_form = "
                + "multipart_form.text(\"nullable_source\", \"\"); }, Some(param_value) => match param_value {"), api);
    }

    @Test
    void aUnionWithoutFilesKeepsItsTextPart() throws IOException {
        String api = api(on, "plain");
        assertTrue(api.contains("multipart_form = multipart_form.text(\"model\", param_value.to_string());"), api);
        assertFalse(api.contains("add_file_to_form"), api);
        assertFalse(api.contains("multipart_helper"), api);
    }

    @Test
    void withoutTheOptionNothingMatches() throws IOException {
        for (String module : List.of("videos", "skills", "mixed", "plain")) {
            String api = api(off, module);
            assertFalse(api.contains("add_file_to_form"), api);
            assertFalse(api.contains("match param_value {"), api);
            assertFalse(api.contains("files[]"), api);
        }
    }

    @Test
    void filesInAShapeMultipartCannotCarryFailGeneration(@TempDir Path output) {
        Throwable failure = assertThrows(RuntimeException.class,
                () -> generate("multipart_unions_nested.yaml", output, true));
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("nested_create.files"), messages.toString());
    }

    /** The generated client has to compile: the arms unwrap the parameter in three ways. Needs cargo. */
    @Test
    void itCompiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(on, 30, "--version") != null, "no cargo");
        String output = cargo(on, 600, "check", "--lib");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("Finished"), output);
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
