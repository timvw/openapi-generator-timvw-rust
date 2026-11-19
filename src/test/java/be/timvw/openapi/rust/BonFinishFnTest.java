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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public {@code build()} and {@code call()} of the bon builders are the generator's, bounded by the state of each
 * required member, and bon's own (which requires {@code IsComplete}) is private ({@link BonNames}); on
 * src/test/resources/request_options.yaml. {@link RequestOptionsTest} compiles the same crate and calls its builders.
 */
class BonFinishFnTest {

    private static final String SPEC = "src/test/resources/request_options.yaml";

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>(Map.of(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true",
                TimvwRustCodegen.CONTENT_TYPE_METHODS, "true", TimvwRustCodegen.RESPONSE_FUNCTIONS, "true"));
        props.put("packageName", "bon_finish_fn_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aModelBuildsThroughItsOwnBuild() throws IOException {
        String model = Files.readString(out.resolve("src/models/item.rs"));
        assertContains(model, "#[builder(state_mod = item_builder, finish_fn(vis = \"\", name = build_internal))]\n"
                + "pub struct Item {");
        assertContains(model, "impl<S: item_builder::State> ItemBuilder<S>\nwhere\n"
                + "    S::Name: item_builder::IsSet,\n{\n");
        assertContains(model, "    pub fn build(self) -> Item {\n        self.build_internal()\n    }\n");
        assertFalse(model.contains("IsComplete"), model);
    }

    @Test
    void aFunctionCallsThroughItsOwnCall() throws IOException {
        String api = Files.readString(out.resolve("src/apis/default_api.rs"));
        assertFalse(api.contains("IsComplete"), api);
        assertFalse(api.contains("#[bon::builder]"), api);
        // the configuration, the borrowed body, the &str header and the request options borrow
        assertContains(api, "#[bon::builder(state_mod = create_item_builder, finish_fn(vis = \"\", "
                + "name = call_internal))]\npub async fn create_item(");
        assertContains(api, "impl<S: create_item_builder::State> CreateItemBuilder<'_, '_, '_, '_, S>\nwhere\n"
                + "    S::Configuration: create_item_builder::IsSet,\n"
                + "    S::Item: create_item_builder::IsSet,\n{\n");
        assertContains(api, "    pub async fn call(self) -> Result<models::Item, Error<CreateItemError>> {\n"
                + "        self.call_internal().await\n    }\n");
        // the twin that returns the response undecoded
        assertContains(api, "impl<S: create_item_response_builder::State> CreateItemResponseBuilder<'_, '_, '_, '_, S>");
        assertContains(api, "    pub async fn call(self) -> Result<super::ApiResponse<models::Item>, "
                + "Error<CreateItemError>> {\n");
        // a stream method
        assertContains(api, "impl<S: create_chat_stream_builder::State> CreateChatStreamBuilder<'_, '_, '_, S>");
    }

    @Test
    void namesItemsAsBonDoes() {
        assertEquals("TopLogprobs", BonNames.pascal("top_logprobs"));
        assertEquals("Type", BonNames.pascal("r#type"));
        assertEquals("X2", BonNames.pascal("x_2"));
        assertEquals("Self_", BonNames.pascal("self_"));
        assertEquals("CreateItemResponse", BonNames.pascal("create_item_response"));
        assertEquals("item_builder", BonNames.snake("ItemBuilder"));
        assertEquals("gpt4o_mini_builder", BonNames.snake("Gpt4oMiniBuilder"));
    }
}
