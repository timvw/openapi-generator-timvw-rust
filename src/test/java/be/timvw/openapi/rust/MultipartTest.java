package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 2.5: multipartHelper and the single models:: prefix of multipart model parameters. */
class MultipartTest {

    @TempDir
    Path output;

    private Path generate(String name, boolean helper) {
        Path dir = output.resolve(name);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "multipart");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, String.valueOf(helper));
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/multipart.yaml").toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
        return dir;
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ");
    }

    @Test
    void filePropertiesTurnAPropertyIntoAFile() {
        io.swagger.v3.parser.core.models.ParseOptions o = new io.swagger.v3.parser.core.models.ParseOptions();
        io.swagger.v3.oas.models.OpenAPI api = new io.swagger.v3.parser.OpenAPIV3Parser()
                .read(Path.of("src/test/resources/multipart.yaml").toAbsolutePath().toString(), null, o);
        SpecRules.fileProperties(api, java.util.List.of("Picture.image"), msg -> { });
        io.swagger.v3.oas.models.media.Schema<?> image = (io.swagger.v3.oas.models.media.Schema<?>)
                api.getComponents().getSchemas().get("Picture").getProperties().get("image");
        assertTrue("binary".equals(image.getFormat()) && "string".equals(SpecRules.singleType(image)));
    }

    @Test
    void modelParametersAreQualifiedOnce() throws IOException {
        String api = Files.readString(generate("stock", false).resolve("src/apis/uploads_api.rs"));
        assertFalse(api.contains("models::models::"), api);
        assertTrue(api.contains("mode: models::Mode"), api);
    }

    @Test
    void withoutTheOptionTheStockBlockRendersAndNoHelperIsWritten() throws IOException {
        Path dir = generate("stock2", false);
        String api = Files.readString(dir.resolve("src/apis/uploads_api.rs"));
        // The stock file branch (7.25 streams the file, 7.15 leaves a TODO).
        assertFalse(api.contains("add_file_to_form"), api);
        assertFalse(api.contains("multipart_helper"), api);
        assertFalse(Files.exists(dir.resolve("src/apis/multipart_helper.rs")));
        assertFalse(Files.readString(dir.resolve("src/apis/mod.rs")).contains("multipart_helper"));
    }

    @Test
    void helperRulesPerParameterShape() throws IOException {
        Path dir = generate("helper", true);
        String api = norm(Files.readString(dir.resolve("src/apis/uploads_api.rs")));
        assertTrue(api.contains("use super::{Error, configuration, ContentType, multipart_helper};"), api);
        // Required and optional files.
        assertTrue(api.contains("multipart_form = multipart_helper::add_file_to_form(multipart_form, &p_form_file, \"file\").await?;"), api);
        assertTrue(api.contains("if let Some(ref param_value) = p_form_mask { // Add file to multipart form multipart_form = multipart_helper::add_file_to_form(multipart_form, param_value, \"mask\").await?; }"), api);
        assertFalse(api.contains("TokioFile::open"), api);
        // Optional array: one part per item.
        assertTrue(api.contains("if let Some(param_value) = p_form_tags { for item in param_value { multipart_form = multipart_helper::add_schema_value_to_form( multipart_form, \"tags\", serde_json::to_value(item)?, None, )?; } }"), api);
        // Optional object with an explicit JSON content type.
        assertTrue(api.contains("if let Some(param_value) = p_form_options { multipart_form = multipart_helper::add_schema_value_to_form( multipart_form, \"options\", serde_json::to_value(param_value)?, Some(\"application/json\"), )?; }"), api);
        // Optional scalar with an explicit content type.
        assertTrue(api.contains("serde_json::Value::String(param_value.to_string()), Some(\"application/sdp\"),"), api);
        // A required scalar stays stock, a required object is its JSON; a binary union uploads its file.
        assertTrue(api.contains("multipart_form = multipart_form.text(\"name\", p_form_name.to_string());"), api);
        assertTrue(api.contains("multipart_form = multipart_form.text(\"mode\", serde_json::to_string(&p_form_mode)?);"), api);
        assertTrue(api.contains("models::UploadCreateRequestImage::Text(value) => { multipart_form = "
                + "multipart_helper::add_file_to_form(multipart_form, value, \"image\").await?; }"), api);
        // The helper module is written and declared after configuration; other modules do not import it.
        assertTrue(Files.readString(dir.resolve("src/apis/multipart_helper.rs")).contains("pub fn add_schema_value_to_form("));
        assertTrue(Files.readString(dir.resolve("src/apis/mod.rs")).contains("pub mod configuration;\n\n// Internal helper module for multipart file handling\npub(crate) mod multipart_helper;\n"));
        assertFalse(Files.readString(dir.resolve("src/apis/plain_api.rs")).contains("multipart_helper"));
    }
}
