package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 2.1: the generator artifacts openai-client-base used to repair in fix_generator_artifacts.py. */
class Milestone2Test {

    @TempDir
    static Path output;

    @BeforeAll
    static void generate() {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/milestone2.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(Map.of("packageName", "milestone2"));
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void unconstrainedAnyOfIsSerdeJsonValue() throws IOException {
        String item = read("src/models/call_item.rs");
        assertFalse(item.contains("AnyOfLessThanGreaterThan"), item);
        // Required nullable: the shape of a model reference, with the type replaced.
        assertTrue(item.contains("pub output: Option<serde_json::Value>,"), item);
        assertTrue(item.contains("pub error: Option<Option<serde_json::Value>>,"), item);
        assertTrue(item.contains("output: Option<serde_json::Value>,"), item);
        assertFalse(item.contains("if let Some(x) = output"), item);
        assertFalse(Files.exists(output.resolve("src/models/any_of_less_than_greater_than.rs")));
        String mod = read("src/models/mod.rs");
        assertFalse(mod.contains("any_of_less_than_greater_than"), mod);
    }

    @Test
    void oneOfBranchesWithOneTitleRenderOneVariantEach() throws IOException {
        // The stock generator renders two Text(String) variants, which does not compile; the enum branch is a model.
        String conversation = read("src/models/call_item_conversation.rs");
        assertEquals(1, count(conversation, "    Text(String),"), conversation);
        assertEquals(1, count(conversation, "    TextVariant(models::CallItemConversationTextVariantEnum),"),
                conversation);
        String reason = read("src/models/call_item_closed_reason.rs");
        assertEquals(1, count(reason, "    Text(String),"), reason);
        assertEquals(1, count(reason, "    TextVariant2(models::CallItemClosedReasonTextVariant2Enum),"), reason);
        assertFalse(reason.contains("impl Default"), reason);
    }
}
