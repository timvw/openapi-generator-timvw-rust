package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleNamingTest {

    @Test
    void stripsComponentAndArrayTitlesButKeepsInlineObjectTitles() {
        ObjectSchema inlineObject = new ObjectSchema();
        inlineObject.setTitle("Object 027");
        inlineObject.addProperty("value", new StringSchema());

        ComposedSchema union = new ComposedSchema();
        union.setTitle("Part");
        union.addOneOfItem(new Schema<>().$ref("#/components/schemas/Other"));

        ArraySchema parts = new ArraySchema();
        parts.setTitle("Array of content parts");
        parts.setItems(union);

        Schema<?> nullableArray = new Schema<>();
        nullableArray.setTypes(new java.util.LinkedHashSet<>(Arrays.asList("array", "null")));
        nullableArray.setTitle("Array of items");
        nullableArray.setItems(new StringSchema());

        ObjectSchema model = new ObjectSchema();
        model.setTitle("Tool choice mode");
        model.addProperty("parts", parts);
        model.addProperty("nullable_parts", nullableArray);
        model.addProperty("inline", inlineObject);

        ObjectSchema other = new ObjectSchema();
        other.setTitle("Tool choice mode");

        OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Model", model).addSchemas("Other", other));

        List<String> stripped = TitleNaming.stripNamingTitles(openAPI);

        assertEquals(Arrays.asList(
                "#/components/schemas/Model",
                "#/components/schemas/Model/properties/parts",
                "#/components/schemas/Model/properties/nullable_parts",
                "#/components/schemas/Other"), stripped);
        assertNull(model.getTitle());
        assertNull(other.getTitle());
        assertNull(parts.getTitle());
        assertNull(nullableArray.getTitle());
        assertEquals("Object 027", inlineObject.getTitle());
        assertEquals("Part", union.getTitle());
    }

    @TempDir
    Path output;

    private void generate(Path dir, boolean titleNaming) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/task20.yaml").toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(Map.of(
                        "packageName", "task20",
                        TimvwRustCodegen.TITLE_NAMING, String.valueOf(titleNaming)));
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    @Test
    void withTitleNamingOffEveryComponentKeepsItsOwnTypeAndArraysNameItemsByPath() throws IOException {
        Path dir = output.resolve("off");
        generate(dir, false);
        Path models = dir.resolve("src/models");
        // Two titled components of the same shape stay two types.
        assertTrue(Files.exists(models.resolve("apply_patch_call_status.rs")));
        assertTrue(Files.exists(models.resolve("beta_apply_patch_call_status.rs")));
        // The inline array of unions is named after its parent and property, not after the array's title.
        assertTrue(Files.exists(models.resolve("message_content_inner.rs")), String.join(",", list(models)));
        assertFalse(Files.exists(models.resolve("array_of_content_parts_inner.rs")));
        String message = Files.readString(models.resolve("message.rs"));
        assertTrue(message.contains("pub content: Vec<models::MessageContentInner>,"), message);
        assertTrue(message.contains("pub status: models::BetaApplyPatchCallStatus,"), message);
        // Titles on inline objects still name them.
        assertTrue(Files.exists(models.resolve("object_027.rs")), String.join(",", list(models)));
    }

    @Test
    void optionalEnumMultipartPartsUseTheWireValueNotJson() throws IOException {
        Path dir = output.resolve("multipart");
        generate(dir, false);
        String api = Files.readString(dir.resolve("src/apis/audio_api.rs"));
        // A referenced string enum is a text part holding the enum's wire value; 7.25 would JSON-quote it.
        assertTrue(api.contains("multipart_form.text(\"response_format\", param_value.to_string());"), api);
        assertFalse(api.contains("multipart_form.text(\"response_format\", serde_json::to_string(&param_value)?);"), api);
        // Objects still travel as JSON on 7.25 (7.15 had no JSON branch and uses Display for everything).
        boolean jsonBranch = api.contains("serde_json::to_string(&param_value)?");
        assertTrue(api.contains("multipart_form.text(\"chunking_strategy\", " + (jsonBranch ? "serde_json::to_string(&param_value)?" : "param_value.to_string()") + ");"), api);
        assertTrue(api.contains("multipart_form.text(\"temperature\", param_value.to_string());"), api);
    }

    @Test
    void withTitleNamingOnTheStockBehaviourIsUnchanged() throws IOException {
        Path dir = output.resolve("on");
        generate(dir, true);
        Path models = dir.resolve("src/models");
        assertTrue(Files.exists(models.resolve("apply_patch_call_status.rs")));
        assertTrue(Files.exists(models.resolve("object_027.rs")));
        String message = Files.readString(models.resolve("message.rs"));
        boolean deduplicated = !Files.exists(models.resolve("beta_apply_patch_call_status.rs"));
        boolean titleNamedArray = Files.exists(models.resolve("array_of_content_parts_inner.rs"));
        // openapi-generator 7.25 merges the twins and names the array items after the title; 7.15 does neither.
        assertEquals(deduplicated, titleNamedArray, String.join(",", list(models)));
        if (deduplicated) {
            assertTrue(message.contains("pub status: models::ApplyPatchCallStatus,"), message);
            assertTrue(message.contains("pub content: Vec<models::ArrayOfContentPartsInner>,"), message);
        }
    }

    private static List<String> list(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
