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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generates the Milestone 1 fixture and checks the rendered Rust, under whichever openapi-generator is on the classpath. */
class GenerationTest {

    @TempDir
    static Path output;

    @BeforeAll
    static void generate() {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/milestone1.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(Map.of("packageName", "milestone1"));
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    @Test
    void libIncludesReadmeAsCrateDocs() throws IOException {
        assertTrue(read("src/lib.rs").startsWith("#![doc = include_str!(\"../README.md\")]\n\n#![allow(unused_imports)]\n"));
        assertTrue(Files.exists(output.resolve("README.md")));
    }

    @Test
    void modelStructsDeriveBonBuilderAndEnumsDoNot() throws IOException {
        String widget = read("src/models/widget.rs");
        assertTrue(widget.contains("#[derive(Clone, Default, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]\npub struct Widget {"), widget);
        String kind = read("src/models/widget_kind.rs");
        assertFalse(kind.contains("bon::Builder"), kind);
    }

    @Test
    void apiFunctionsGetBonBuilderAfterDeprecated() throws IOException {
        String api = read("src/apis/widgets_api.rs");
        assertTrue(api.contains("#[bon::builder]\npub async fn widgets_list("), api);
        assertTrue(api.contains("#[bon::builder]\npub async fn widgets_create("), api);
        // openapi-generator 7.25 marks deprecated operations; the attribute must stay directly above the builder one.
        if (api.contains("#[deprecated]")) {
            assertTrue(api.contains("#[deprecated]\n#[bon::builder]\npub async fn widgets_create("), api);
        }
        assertEquals(2, api.split("#\\[bon::builder]", -1).length - 1);
    }

    @Test
    void cargoDependsOnBon() throws IOException {
        String cargo = read("Cargo.toml");
        assertTrue(cargo.contains("[dependencies]\nbon = \"^3\"\n"), cargo);
    }

    @Test
    void deepObjectParsingDoesNotPanic() throws IOException {
        String mod = read("src/apis/mod.rs");
        assertFalse(mod.contains("unimplemented!"), mod);
        assertTrue(mod.contains("vec![(prefix.to_string(), value)]"), mod);
    }

    @Test
    void bareUrlsInDocCommentsAreWrapped() throws IOException {
        String widget = read("src/models/widget.rs");
        assertTrue(widget.contains("/// Widget : A widget. Reference <https://example.com/docs/widget>."), widget);
        assertTrue(widget.contains("/// Human name, see <https://example.com/docs/naming>."), widget);
        String kind = read("src/models/widget_kind.rs");
        assertTrue(kind.contains("/// Kinds are listed at <https://example.com/docs/kinds>."), kind);

        String api = read("src/apis/widgets_api.rs");
        assertTrue(api.contains("/// Filters use deepObject style; see <https://example.com/docs/filters> (and <https://example.com/wrapped>)."), api);

        // Markdown docs and the file header keep the text as written.
        assertTrue(read("docs/Widget.md").contains("Human name, see https://example.com/docs/naming."));
        assertTrue(widget.contains(" * Exercises every Milestone 1 rendering rule. Spec at https://example.com/openapi.yml"), widget);
    }

    @Test
    void integerWithFloatFormatIsRenderedAsNumber() throws IOException {
        String widget = read("src/models/widget.rs");
        assertTrue(widget.contains("pub weight: Option<f32>,"), widget);
    }
}
