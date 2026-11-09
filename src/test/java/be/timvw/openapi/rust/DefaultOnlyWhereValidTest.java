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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.2: Default only where its value is one the API would accept. */
class DefaultOnlyWhereValidTest {

    private static final String DERIVE_DEFAULT = "#[derive(Clone, Default, Debug,";
    private static final String DERIVE_PLAIN = "#[derive(Clone, Debug,";

    @TempDir
    static Path root;

    private static Path rule;

    @BeforeAll
    static void generate() {
        rule = generate("rule");
    }

    private static Path generate(String name) {
        Path output = root.resolve(name);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "defaults");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/defaults.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
        return output;
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static List<Path> models(Path output) throws IOException {
        try (Stream<Path> files = Files.list(output.resolve("src/models"))) {
            return files.filter(p -> p.toString().endsWith(".rs")).toList();
        }
    }

    @Test
    void aRequiredFieldTakesTheDeriveAway() throws IOException {
        for (Path output : List.of(rule)) {
            String thing = read(output, "create_thing");
            assertTrue(thing.contains(DERIVE_PLAIN), thing);
            assertFalse(thing.contains(DERIVE_DEFAULT), thing);
            assertTrue(thing.contains("pub fn new(prompt: String) -> CreateThing"), thing);
        }
    }

    @Test
    void aRequiredNullableFieldCountsAsRequired() throws IOException {
        for (Path output : List.of(rule)) {
            String note = read(output, "nullable_note");
            assertTrue(note.contains("pub note: Option<String>,"), note);
            assertFalse(note.contains("Default"), note);
        }
    }

    @Test
    void anAllOptionalStructKeepsItWhateverItsFieldTypes() throws IOException {
        for (Path output : List.of(rule)) {
            String options = read(output, "options");
            assertTrue(options.contains(DERIVE_DEFAULT), options);
            // A union, a value enum, a property enum and a struct that has no Default themselves.
            assertTrue(options.contains("pub shape: Option<models::Shape>,"), options);
            assertTrue(options.contains("pub color: Option<models::Color>,"), options);
            assertTrue(options.contains("pub size: Option<Size>,"), options);
            assertTrue(options.contains("pub thing: Option<models::CreateThing>,"), options);
        }
    }

    @Test
    void noEnumImplementsDefaultWhateverItsKind() throws IOException {
        for (Path output : List.of(rule)) {
            assertTrue(read(output, "color").contains("pub enum Color {"));
            assertTrue(read(output, "shape").contains("pub enum Shape {"));
            assertTrue(read(output, "pet").contains("pub enum Pet {"));
            assertTrue(read(output, "options").contains("pub enum Size {"));
            for (Path model : models(output)) {
                String text = Files.readString(model);
                assertFalse(text.contains("impl Default for"), model + "\n" + text);
            }
        }
    }

    @Test
    void aStructWithALocalFieldNamedLikeAUnionKeepsItsDerive() throws IOException {
        // The rule looks at the struct's own required fields, not at bare type names elsewhere.
        String holder = read(rule, "holder");
        assertTrue(holder.contains("pub object: Option<Object>,"), holder);
        assertTrue(holder.contains(DERIVE_DEFAULT), holder);
    }
}
