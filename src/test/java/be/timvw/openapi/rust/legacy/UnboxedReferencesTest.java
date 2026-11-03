package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rewrites read a model held by value the same whether boxOnlyCycles boxed it or not (Milestone 3.1). */
class UnboxedReferencesTest {

    @TempDir
    Path root;

    private Path models() throws IOException {
        return Files.createDirectories(root.resolve("src/models"));
    }

    private static final String TAGGED = "#[serde(tag = \"type\")]\npub enum Tool {\n"
            + "    #[serde(rename = \"function\")]\n    Function(%s),\n}\n";

    @Test
    void taggedEnumOverATypedStructBecomesUntaggedBoxedOrNot() throws IOException {
        Path models = models();
        Files.writeString(models.resolve("function_tool.rs"), "pub struct FunctionTool {\n    pub r#type: String,\n}\n");
        for (String payload : new String[]{"Box<models::FunctionTool>", "models::FunctionTool"}) {
            Path file = models.resolve("tool.rs");
            Files.writeString(file, String.format(TAGGED, payload));
            assertTrue(FixToolEnumTagging.shouldFixEnum(file, models), payload);
            FixToolEnumTagging.fixEnumTagging(file);
            assertTrue(Files.readString(file).startsWith("#[serde(untagged)]\npub enum Tool {\n    Function("), payload);
        }
        Path vec = models.resolve("tools.rs");
        Files.writeString(vec, String.format(TAGGED, "Vec<models::FunctionTool>"));
        assertFalse(FixToolEnumTagging.shouldFixEnum(vec, models));
    }

    @Test
    void defaultIsDroppedForARunObjectHeldByValueOnly() throws IOException {
        Path models = models();
        String derive = "#[derive(Clone, Default, Debug)]\npub struct %s {\n    pub run: %s,\n}\n";
        Map<String, String> cases = Map.of(
                "Boxed", "Box<models::RunObject>",
                "Unboxed", "models::RunObject",
                "Optional", "Option<models::RunObject>",
                "Listed", "Vec<models::RunObject>",
                "Mapped", "std::collections::HashMap<String, models::RunObject>",
                "Other", "models::RunObjectStatus");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Files.writeString(models.resolve(c.getKey().toLowerCase() + ".rs"),
                    String.format(derive, c.getKey(), c.getValue()));
        }
        new FixDefaultIssues().apply(new LegacyContext(root, Map.of(), Map.of()));
        for (String held : new String[]{"Boxed", "Unboxed", "Optional"}) {
            assertFalse(Files.readString(models.resolve(held.toLowerCase() + ".rs")).contains("Default"), held);
        }
        for (String notHeld : new String[]{"Listed", "Mapped", "Other"}) {
            assertTrue(Files.readString(models.resolve(notHeld.toLowerCase() + ".rs")).contains("Default"), notHeld);
        }
    }
}
