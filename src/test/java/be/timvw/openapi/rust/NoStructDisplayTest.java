package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.2: structs have no Display, and every use of one in the API code is explicit JSON. */
class NoStructDisplayTest {

    @TempDir
    static Path on;

    @TempDir
    static Path onHelper;

    @BeforeAll
    static void generate() {
        generate(on, false);
        generate(onHelper, true);
    }

    private static void generate(Path output, boolean multipartHelper) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "enums");
        props.put(TimvwRustCodegen.DEEP_OBJECT_QUERY_PARAMETERS, "/things/deep?filter");
        if (multipartHelper) {
            props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/enum_display.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path output) throws IOException {
        return Files.readString(output.resolve("src/apis/things_api.rs"));
    }

    private static String model(Path output, String name) throws IOException {
        return Files.readString(output.resolve("src/models/" + name + ".rs"));
    }

    @Test
    void structsHaveNoDisplay() throws IOException {
        assertFalse(model(on, "filter").contains("Display"));
    }

    @Test
    void enumsKeepTheirDisplay() throws IOException {
        assertTrue(model(on, "color").contains("impl std::fmt::Display for Color"));
    }

    @Test
    void pathParameter() throws IOException {
        assertTrue(api(on).contains("filter=crate::apis::urlencode(serde_json::to_string(&p_path_filter)?)"), api(on));
    }

    @Test
    void requiredQueryParameter() throws IOException {
        // listed in deepObjectQueryParameters: sent in deepObject style, not as its JSON
        assertTrue(api(on).contains("let param_value = &p_query_filter;\n        req_builder = req_builder.query("
                + "&crate::apis::parse_deep_object(\"filter\", &serde_json::to_value(param_value)?));"), api(on));
    }

    @Test
    void queryArraysSerializeTheirItems() throws IOException {
        String on = api(NoStructDisplayTest.on);
        String items = "let items: Vec<String> = param_value.iter().map(serde_json::to_string).collect::<Result<_, _>>()?;";
        for (String id : List.of("p_query_filters", "p_query_joined")) {
            assertTrue(on.contains("let param_value = &" + id + ";\n        " + items), on);
        }
        assertTrue(on.contains("for item in items {\n            req_builder = req_builder.query(&[(\"filters\", item)]);"),
                on);
        assertTrue(on.contains("req_builder = req_builder.query(&[(\"joined\", items.join(\",\"))]);"), on);
        assertTrue(on.contains("if let Some(ref param_value) = p_query_maybe_joined {\n        " + items), on);
    }

    @Test
    void enumArraysStayOnDisplay() throws IOException {
        assertTrue(api(on).contains("if let Some(ref param_value) = p_query_colors {\n"
                + "        let items: Vec<String> = param_value.iter().map(|p| p.to_string()).collect();"), api(on));
    }

    @Test
    void headers() throws IOException {
        assertTrue(api(on).contains("req_builder.header(\"X-Filter\", serde_json::to_string(&p_header_x_filter)?)"),
                api(on));
        assertTrue(api(on).contains("req_builder.header(\"X-Maybe-Filter\", serde_json::to_string(&param_value)?)"),
                api(on));
        assertTrue(api(on).contains("req_builder.header(\"X-Color\", param_value.to_string())"), api(on));
    }

    @Test
    void urlencodedForms() throws IOException {
        String api = api(on);
        assertTrue(api.contains("let param_value = &p_form_filter;\n"
                + "        multipart_form_params.push((\"filter\", serde_json::to_string(param_value)?));"), api);
        assertTrue(api.contains("multipart_form_params.push((\"maybe_filter\", serde_json::to_string(param_value)?));"),
                api);
        assertTrue(api.contains("let param_value = &p_form_filters;\n        let items: Vec<String> = param_value.iter()"
                + ".map(serde_json::to_string).collect::<Result<_, _>>()?;"), api);
        assertTrue(api.contains("multipart_form_params.push((\"filters\", item));"), api);
    }

    @Test
    void multipartTextParts() throws IOException {
        for (Path output : List.of(on, onHelper)) {
            String api = api(output);
            assertTrue(api.contains("multipart_form.text(\"filter\", serde_json::to_string(&p_form_filter)?);"), api);
            assertTrue(api.contains("multipart_form.text(\"filters\", p_form_filters.into_iter()"
                    + ".map(|p| serde_json::to_string(&p)).collect::<Result<Vec<String>, _>>()?.join(\",\"));"), api);
            assertTrue(api.contains("multipart_form.text(\"color\", param_value.to_string());"), api);
        }
    }

    @Test
    void nothingStillCallsDisplayOnAStruct() throws IOException {
        // Stock -g rust calls to_string() on these identifiers; none of them may.
        for (Path output : List.of(on, onHelper)) {
            String api = api(output);
            for (String id : List.of("p_path_filter", "p_query_filter", "p_header_x_filter", "p_form_filter")) {
                assertFalse(api.contains(id + ".to_string()"), id + "\n" + api);
            }
        }
    }

    @Test
    void aModelRewrittenIntoAUnionAfterRenderingFailsInsteadOfChangingBytes(@TempDir Path root) throws IOException {
        // Rendered as a struct, then turned into a union by a rewrite: its Display would not be its JSON.
        Path models = Files.createDirectories(root.resolve("src/models"));
        Files.writeString(models.resolve("filter.rs"), "#[serde(untagged)]\npub enum Filter {\n    Text(String),\n}\n");
        org.openapitools.codegen.CodegenParameter parameter = new org.openapitools.codegen.CodegenParameter();
        parameter.dataType = "models::Filter";
        TimvwRustCodegen.markStructJson(parameter, java.util.Set.of("Filter"));
        assertTrue(parameter.vendorExtensions.containsKey(TimvwRustCodegen.STRUCT_JSON));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> TimvwRustCodegen.checkStructJsonModels(models, java.util.Set.of("Filter"),
                        Map.of("Filter", "filter")));
        assertTrue(e.getMessage().contains("Filter"), e.getMessage());
        Files.writeString(models.resolve("filter.rs"), "pub struct Filter {\n}\n");
        TimvwRustCodegen.checkStructJsonModels(models, java.util.Set.of("Filter"), Map.of("Filter", "filter"));
    }
}
