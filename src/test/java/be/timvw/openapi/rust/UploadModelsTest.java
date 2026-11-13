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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file fields of the models hold uploads, which serde leaves out ({@link UploadParams#markModels}). */
class UploadModelsTest {

    @TempDir
    static Path on;

    @TempDir
    static Path features;

    @TempDir
    static Path plain;

    @BeforeAll
    static void generate() {
        generate(on, Map.of());
        generate(features, Map.of(TimvwRustCodegen.API_FEATURES, "true"));
        generate(plain, Map.of("bonBuilders", "false"));
    }

    private static void generate(Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "upload_models");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/upload_models.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String model(Path root, String file) throws IOException {
        return Files.readString(root.resolve("src/models/" + file + ".rs")).replaceAll("\\s+", " ");
    }

    private static final String UPLOAD = "timvw_rust_runtime::Upload";

    @Test
    void anOptionalFileIsAnUploadThatSerdeSkips() throws IOException {
        String model = model(on, "container_file_body");
        assertTrue(model.contains("#[derive(Clone, Default, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]"),
                model);
        assertTrue(model.contains("/// The file to upload. /// /// An upload has no JSON form: the model's JSON leaves it "
                + "out, and JSON never sets it. #[serde(rename = \"file\", skip)] #[builder(into)] pub file: Option<"
                + UPLOAD + ">,"), model);
        assertFalse(model.contains("PathBuf"), model);
        assertTrue(Files.readString(on.resolve("docs/ContainerFileBody.md")).contains("Option<**" + UPLOAD + "**>"));
    }

    @Test
    void aRequiredFileRejectsJson() throws IOException {
        String model = model(on, "document_body");
        assertTrue(model.contains("#[serde(rename = \"file\", skip_serializing, deserialize_with = "
                + "\"crate::models::upload_has_no_json\")] #[builder(into)] pub file: " + UPLOAD + ","), model);
        assertTrue(Files.readString(on.resolve("src/models/mod.rs"))
                .contains("pub(crate) fn upload_has_no_json<'de, D: serde::Deserializer<'de>, T>(_: D)"));
        assertFalse(Files.readString(on.resolve("src/models/container_file_body.rs")).contains("upload_has_no_json"));
    }

    @Test
    void arraysMapsAndNullableFilesHoldUploads() throws IOException {
        String model = model(on, "document_body");
        String each = "#[builder(with = |files: impl IntoIterator<Item = impl Into<" + UPLOAD + ">>| "
                + "files.into_iter().map(Into::into).collect())]";
        assertTrue(model.contains("#[serde(rename = \"attachments\", skip)] " + each + " pub attachments: Vec<" + UPLOAD
                + ">,"), model);
        assertTrue(model.contains("#[serde(rename = \"extra\", skip)] " + each + " pub extra: Option<Vec<" + UPLOAD
                + ">>,"), model);
        assertTrue(model.contains("#[serde(rename = \"nullable_file\", skip)] #[builder(into)] pub nullable_file: "
                + "Option<" + UPLOAD + ">,"), model);
        assertTrue(model.contains("#[serde(rename = \"named\", skip)] pub named: Option<std::collections::HashMap<"
                + "String, " + UPLOAD + ">>,"), model);
    }

    @Test
    void newConvertsTheRequiredFiles() throws IOException {
        String model = model(on, "document_body");
        assertTrue(model.contains("pub fn new(file: impl Into<" + UPLOAD + ">, attachments: impl IntoIterator<Item = "
                + "impl Into<" + UPLOAD + ">>, nullable_file: Option<" + UPLOAD + ">) -> DocumentBody {"), model);
        assertTrue(model.contains("file: file.into(), attachments: attachments.into_iter().map(Into::into).collect(),"),
                model);
        assertTrue(Files.readString(on.resolve("Cargo.toml")).contains("features = [\"fs\"]"));
    }

    @Test
    void withoutBonTheFieldsHaveNoBuilderAttribute() throws IOException {
        String model = model(plain, "document_body");
        assertTrue(model.contains("#[serde(rename = \"attachments\", skip)] pub attachments: Vec<" + UPLOAD + ">,"),
                model);
        assertFalse(model.contains("#[builder"), model);
    }

    /** A model with an upload needs the runtime: with apiFeatures, the -types features that compile one depend on it. */
    @Test
    void theTypesFeaturesWithUploadsDependOnTheRuntime() throws IOException {
        String cargo = Files.readString(features.resolve("Cargo.toml"));
        assertTrue(cargo.contains("\ncontainers-types = [\"dep:timvw-rust-runtime\"]\n"), cargo);
        assertTrue(cargo.contains("\ndocuments-types = [\"dep:timvw-rust-runtime\"]\n"), cargo);
    }

    /** {@code documents-types} alone compiles, holding uploads. Needs cargo. */
    @Test
    void aTypesFeatureWithUploadsCompiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(features, 30, "--version") != null, "no cargo");
        String output = cargo(features, 900, "check", "--lib", "--no-default-features", "--features",
                "documents-types");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("Finished") && !output.contains("warning:"), output);
    }

    /** src/test/rust/upload_models.rs: conversions, builders and the JSON form. Needs cargo. */
    @Test
    void whatTheModelsDo() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(on, 30, "--version") != null, "no cargo");
        Files.createDirectories(on.resolve("tests"));
        Files.copy(Path.of("src/test/rust/upload_models.rs"), on.resolve("tests/upload_models.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(on, 900, "test", "--test", "upload_models");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
        String clippy = cargo(on, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        Assumptions.assumeTrue(clippy == null || !clippy.contains("no such command"), "no clippy");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"), clippy);
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
