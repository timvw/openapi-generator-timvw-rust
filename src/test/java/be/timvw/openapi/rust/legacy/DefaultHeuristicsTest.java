package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rewrites that infer {@code Default} from the rendered text stay out under {@code defaultOnlyWhereValid}. */
class DefaultHeuristicsTest {

    private static final String HOLDER = """
            #[derive(Clone, Default, Debug, PartialEq, Serialize, Deserialize)]
            pub struct Holder {
                #[serde(rename = "object", skip_serializing_if = "Option::is_none")]
                pub object: Option<Object>,
            }

            #[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]
            pub enum Object {
                #[serde(rename = "holder")]
                Holder,
            }
            """;

    /** Another module's type without {@code Default}, with the same bare name as Holder's local enum. */
    private static final String OTHER_OBJECT = """
            #[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
            #[serde(untagged)]
            pub enum Object {
                Text(String),
            }
            """;

    @TempDir
    Path root;

    private Path models() throws IOException {
        Path models = root.resolve("src/models");
        Files.createDirectories(models);
        return models;
    }

    private LegacyContext context(boolean defaultOnlyWhereValid) {
        return new LegacyContext(root, Map.of(), Map.of(), BoxPolicy.ALWAYS, defaultOnlyWhereValid);
    }

    @Test
    void aBareNameCollisionOnlyStripsWithoutTheOption() throws IOException {
        Path holder = models().resolve("holder.rs");
        Files.writeString(models().resolve("other.rs"), OTHER_OBJECT);

        Files.writeString(holder, HOLDER);
        new FixGeneratedCode().apply(context(true));
        assertTrue(Files.readString(holder).contains("#[derive(Clone, Default, Debug,"), Files.readString(holder));

        Files.writeString(holder, HOLDER);
        new FixGeneratedCode().apply(context(false));
        assertFalse(Files.readString(holder).contains("Default"), Files.readString(holder));
    }

    @Test
    void theSeedListOnlyStripsWithoutTheOption() throws IOException {
        String struct = FixDefaultIssues.REMOVE_DEFAULT_FROM.get(0);
        String file = struct.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase() + ".rs";
        Path path = models().resolve(file);
        String text = "#[derive(Clone, Default, Debug)]\npub struct " + struct + " {}\n";

        Files.writeString(path, text);
        new FixDefaultIssues().apply(context(true));
        assertEquals(text, Files.readString(path));

        new FixDefaultIssues().apply(context(false));
        assertFalse(Files.readString(path).contains("Default"), Files.readString(path));
    }
}
