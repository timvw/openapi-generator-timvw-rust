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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A multipart union with file variants uploads the files instead of sending their paths as text. */
class MultipartFileUnionsTest {

    @TempDir
    static Path on;

    @TempDir
    static Path middleware;

    @TempDir
    static Path features;

    @BeforeAll
    static void generate() {
        generate("multipart_unions.yaml", on);
        generate("multipart_unions.yaml", middleware,
                Map.of(TimvwRustCodegen.HTTP_STACK, TimvwRustCodegen.HTTP_STACK_REQWEST_MIDDLEWARE));
        generate("multipart_unions.yaml", features, Map.of(TimvwRustCodegen.API_FEATURES, "true"));
    }

    private static void generate(String spec, Path output) {
        generate(spec, output, Map.of());
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "multipart_unions");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
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

    private static String model(Path root, String file) throws IOException {
        return Files.readString(root.resolve("src/models/" + file + ".rs")).replaceAll("\\s+", " ");
    }

    private static final String UPLOAD = "timvw_rust_runtime::Upload";

    @Test
    void theFileVariantsHoldUploadsThatSerdeSkips() throws IOException {
        String model = model(on, "skill_body_files");
        assertTrue(model.contains("#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]"), model);
        assertTrue(model.contains("#[serde(skip)] ArrayOfStrings(Vec<" + UPLOAD + ">),"), model);
        assertTrue(model.contains("#[serde(skip)] Text(" + UPLOAD + "),"), model);
        assertFalse(model.contains("Text(std::path::PathBuf)"), model);
        String video = model(on, "video_body_video");
        assertTrue(video.contains("#[serde(skip)] Text(" + UPLOAD + "), Reference(models::Reference),"), video);
        assertTrue(Files.readString(on.resolve("docs/SkillBodyFiles.md")).contains("| Vec<" + UPLOAD + "> |"));
    }

    @Test
    void theUnionsConvertFromUploadsAndPaths() throws IOException {
        String model = model(on, "skill_body_files");
        assertTrue(model.contains("pub fn file(upload: impl Into<" + UPLOAD + ">) -> Self { Self::Text(upload.into()) }"),
                model);
        assertTrue(model.contains("pub fn files(uploads: impl IntoIterator<Item = impl Into<" + UPLOAD + ">>) -> Self {"
                + " Self::ArrayOfStrings(uploads.into_iter().map(Into::into).collect()) }"), model);
        assertTrue(model.contains("impl From<" + UPLOAD + "> for SkillBodyFiles"), model);
        assertTrue(model.contains("impl From<std::path::PathBuf> for SkillBodyFiles"), model);
        assertTrue(model.contains("impl From<Vec<" + UPLOAD + ">> for SkillBodyFiles"), model);
        assertTrue(model.contains("impl From<Vec<std::path::PathBuf>> for SkillBodyFiles"), model);
        // A file prints as its name: an upload has no JSON.
        assertTrue(model.contains("Self::Text(value) => f.write_str(value.name().unwrap_or(\"file\")),"), model);
    }

    @Test
    void theSetterOfAFileUnionConverts() throws IOException {
        assertTrue(api(on, "skills").contains("#[builder(into)] files: models::SkillBodyFiles,"), api(on, "skills"));
        String videos = api(on, "videos");
        assertTrue(videos.contains("#[builder(into)] video: models::VideoBodyVideo, prompt: &str, #[builder(into)] "
                + "reference: Option<models::VideoBodyReference>,"), videos);
        assertFalse(api(on, "plain").contains("#[builder(into)]"), api(on, "plain"));
        String cargo = Files.readString(on.resolve("Cargo.toml"));
        assertTrue(cargo.contains("features = [\"fs\"]"), cargo);
    }

    /** A model with an upload needs the runtime: with apiFeatures, the -types features that compile one depend on it. */
    @Test
    void theTypesFeaturesWithUploadsDependOnTheRuntime() throws IOException {
        String cargo = Files.readString(features.resolve("Cargo.toml"));
        assertTrue(cargo.contains("\nvideos-types = [\"dep:timvw-rust-runtime\"]\n"), cargo);
        assertTrue(cargo.contains("\nskills-types = [\"dep:timvw-rust-runtime\"]\n"), cargo);
        assertTrue(cargo.contains("\nplain-types = []\n"), cargo);
        assertTrue(cargo.contains("optional = true }"), cargo);
    }

    /** {@code videos-types} alone compiles, holding uploads. Needs cargo. */
    @Test
    void aTypesFeatureWithUploadsCompiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(features, 30, "--version") != null, "no cargo");
        String output = cargo(features, 900, "check", "--lib", "--no-default-features", "--features", "videos-types");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("Finished") && !output.contains("warning:"), output);
    }

    @Test
    void reqwestMiddlewareKeepsPaths() throws IOException {
        String model = model(middleware, "skill_body_files");
        assertTrue(model.contains("ArrayOfStrings(Vec<std::path::PathBuf>), Text(std::path::PathBuf),"), model);
        assertFalse(model.contains("serde(skip)"), model);
        assertFalse(model.contains("Upload"), model);
        assertFalse(api(middleware, "skills").contains("#[builder(into)]"), api(middleware, "skills"));
    }

    private static final String ADD_VIDEO_FILE =
            "multipart_form = multipart_helper::add_file_to_form(multipart_form, value, \"video\").await?;";

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
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"reference\").await?;"), api);
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
                + "path, \"files[]\").await?; }"), api);
        // A single file keeps the property's name, as the spec documents the brackets only for the array.
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"files\").await?;"), api);
    }

    @Test
    void anArrayKeepsThePropertyNameWhenTheSpecDocumentsNoOther() throws IOException {
        String api = api(on, "skills");
        String version = api.substring(api.indexOf("pub async fn skill_version_create"));
        assertTrue(version.contains("for path in value { multipart_form = multipart_helper::add_file_to_form("
                + "multipart_form, path, \"files\").await?; }"), version);
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
        assertTrue(api.contains("multipart_helper::add_file_to_form(multipart_form, value, \"encoded\").await?;"), api);
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
    void filesInAShapeMultipartCannotCarryFailGeneration(@TempDir Path output) {
        Throwable failure = assertThrows(RuntimeException.class,
                () -> generate("multipart_unions_nested.yaml", output));
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("nested_create.files"), messages.toString());
    }

    /**
     * The generated client has to compile (the arms unwrap the parameter in three ways), and
     * src/test/rust/multipart_unions.rs checks what it sends: bytes, paths and arrays of uploads as file parts. Needs
     * cargo.
     */
    @Test
    void whatArrivesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(on, 30, "--version") != null, "no cargo");
        Path manifest = on.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tempfile = "3"
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(on.resolve("tests"));
        Files.copy(Path.of("src/test/rust/multipart_unions.rs"), on.resolve("tests/multipart_unions.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(on, 900, "test", "--test", "multipart_unions");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
        String clippy = cargo(on, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        Assumptions.assumeTrue(clippy == null || !clippy.contains("no such command"), "no clippy");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"), clippy);
    }

    /** reqwest-middleware's file unions hold paths and compile as before. Needs cargo. */
    @Test
    void reqwestMiddlewareCompiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(middleware, 30, "--version") != null, "no cargo");
        String output = cargo(middleware, 900, "check", "--lib");
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
