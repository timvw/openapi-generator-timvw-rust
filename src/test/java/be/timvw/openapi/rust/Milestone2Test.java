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
import java.util.regex.Matcher;
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
        // Required nullable: the shape the stock generator gives a model reference, with the type replaced.
        assertTrue(item.contains("pub output: Option<Box<serde_json::Value>>,"), item);
        assertTrue(item.contains("pub error: Option<Option<Box<serde_json::Value>>>,"), item);
        assertTrue(item.contains("output: Option<serde_json::Value>,"), item);
        // Milestone 2.2: a required nullable model field is built with map(Box::new), not if-let/else.
        assertTrue(item.contains("output: output.map(Box::new),"), item);
        assertFalse(item.contains("if let Some(x) = output"), item);
        assertFalse(Files.exists(output.resolve("src/models/any_of_less_than_greater_than.rs")));
        String mod = read("src/models/mod.rs");
        assertFalse(mod.contains("any_of_less_than_greater_than"), mod);
    }

    @Test
    void duplicateOneOfVariantsCollapseToOne() throws IOException {
        String conversation = read("src/models/call_item_conversation.rs");
        assertEquals(1, count(conversation, "    Text(String),"), conversation);
        String reason = read("src/models/call_item_closed_reason.rs");
        assertEquals(1, count(reason, "    Text(String),"), reason);
        // The Default impl still points at the surviving variant.
        Matcher m = Pattern.compile("fn default\\(\\) -> Self \\{\\s*Self::Text\\(Default::default\\(\\)\\)").matcher(reason);
        assertTrue(m.find(), reason);
    }
}
