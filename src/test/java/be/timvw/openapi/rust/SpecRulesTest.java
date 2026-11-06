package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 2.3: the spec rules that replace openai-client-base's spec patch scripts. */
class SpecRulesTest {

    private static final Path SPEC = Path.of("src/test/resources/spec_rules.yaml").toAbsolutePath();

    @TempDir
    static Path output;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "spec_rules");
        props.put(TimvwRustCodegen.TITLE_NAMING, "false");
        props.put(TimvwRustCodegen.WEBHOOK_MODULE, "SpecRulesWebhooks");
        props.put(TimvwRustCodegen.WEBHOOK_MODULE_REEXPORT, "true");
        // A config file gives lists; the command line gives a delimited string. Both forms are exercised.
        props.put(TimvwRustCodegen.STRING_ID_SCHEMAS, Arrays.asList("ModelIds"));
        props.put(TimvwRustCodegen.STRING_ID_PROPERTIES, "model=ID_of_the_model_to_use|voice");
        props.put(TimvwRustCodegen.STRING_PROPERTIES, "Event.kind");
        props.put(TimvwRustCodegen.DEEP_OBJECT_QUERY_PARAMETERS, "/things?filter");
        props.put(TimvwRustCodegen.RESET_QUERY_PARAMETER_STYLE, "true");
        props.put(TimvwRustCodegen.PREPROCESSED_SPEC_FILE, "target/specs/preprocessed.json");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(SPEC.toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
        String keep = System.getenv("TIMVW_TEST_KEEP");
        if (keep != null) {
            try (var files = Files.walk(output)) {
                for (Path p : files.toList()) {
                    Path t = Path.of(keep).resolve(output.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    } else {
                        Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    private static OpenAPI parse() {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        return new OpenAPIV3Parser().read(SPEC.toString(), null, options);
    }

    @Test
    void entriesComeFromListsOrDelimitedStrings() {
        assertEquals(Arrays.asList("A", "B", "C"), SpecRules.entries("A|B C;"));
        assertEquals(Arrays.asList("A", "B"), SpecRules.entries(Arrays.asList("A", "B")));
        assertTrue(SpecRules.entries(null).isEmpty());
    }

    @Test
    void webhooksGetTheirOwnModuleAndDefaultApiReexportsIt() throws IOException {
        String mod = read("src/apis/mod.rs");
        assertTrue(mod.contains("pub mod spec_rules_webhooks_api;"), mod);
        assertTrue(Files.exists(output.resolve("src/apis/spec_rules_webhooks_api.rs")));
        String api = read("src/apis/default_api.rs");
        assertTrue(api.contains("pub async fn things_list("), api);
        assertTrue(api.contains("}\n\npub use super::spec_rules_webhooks_api::*;\n"), api);
        assertFalse(api.contains("\n\n\npub use"), api);
        assertFalse(api.contains("thing_created"), api);
    }

    @Test
    void reservedWebhookTagOnAPathOperationFails() {
        OpenAPI spec = parse();
        spec.getPaths().get("/things").getGet().addTagsItem("SpecRulesWebhooks");
        assertThrows(IllegalStateException.class, () -> SpecRules.routeWebhooks(spec, "SpecRulesWebhooks"));
    }

    @Test
    void idSchemasAndIdPropertiesBecomeStrings() throws IOException {
        String thing = read("src/models/thing.rs");
        assertTrue(thing.contains("pub model: String,"), thing);
        assertTrue(thing.contains("/// Which model made the thing."), thing);
        assertTrue(thing.contains("pub voice: Option<String>,"), thing);
        assertFalse(Files.exists(output.resolve("src/models/model_ids.rs")));
        // Inherited through allOf: redeclared as a string in Derived's own component.
        String derived = read("src/models/derived.rs");
        assertTrue(derived.contains("pub model: String,"), derived);
        assertTrue(derived.contains("/// ID of the model to use"), derived);
    }

    @Test
    void stringProperties() throws IOException {
        String event = read("src/models/event.rs");
        assertTrue(event.contains("pub kind: Option<String>,"), event);
    }

    @Test
    void genericRulesShapeTheNames() throws IOException {
        List<String> models;
        try (var s = Files.list(output.resolve("src/models"))) {
            models = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        // Hyphenated schema renamed and still referenced.
        assertTrue(models.contains("item2.rs"), String.join(",", models));
        assertTrue(read("src/models/thing.rs").contains("pub item: Box<models::Item2>,"));
        // Inline union hoisted into ThingChoice; the string branch is titled Text. (The array branch's
        // ArrayOfItem2s title is an array title, which titleNaming=false strips before naming.)
        assertTrue(models.contains("thing_choice.rs"), String.join(",", models));
        String choice = read("src/models/thing_choice.rs");
        assertTrue(choice.contains("Text(String),"), choice);
        assertTrue(choice.contains("(Vec<models::Item2>),"), choice);
        // Untitled primitive variants are titled Text and Integer (the stock generator still renders an all-primitive
        // anyOf as an empty struct; the union group gives it variants).
        String json = read("target/specs/preprocessed.json");
        assertTrue(json.contains("\"title\" : \"Text\""), json);
        assertTrue(json.contains("\"title\" : \"Integer\""), json);
        // The inline title colliding with the Options component is renamed ThingOptions.
        assertTrue(models.contains("thing_options.rs"), String.join(",", models));
        assertTrue(read("src/models/options.rs").contains("pub struct Options {"));
        // The string component referenced from a top-level union is inlined: no PlainText variant.
        String union = read("src/models/text_or_meta.rs");
        assertFalse(union.contains("PlainText"), union);
        assertTrue(union.contains("Text(String),"), union);
    }

    @Test
    void validationOnlyUnionsAndDefaultsAreDropped() {
        OpenAPI spec = parse();
        assertEquals(1, SpecRules.collapseRequiredOnlyUnions(spec));
        Schema<?> source = (Schema<?>) spec.getComponents().getSchemas().get("Thing").getProperties().get("source");
        assertNull(source.getOneOf());
        assertTrue(SpecRules.pruneGeneratorHostileKeywords(spec) >= 1);
        assertNull(((Schema<?>) spec.getComponents().getSchemas().get("Thing").getProperties().get("id")).getDefault());
        assertEquals(1, SpecRules.stripRefSiblings(spec));
    }

    @Test
    void objectQueryParametersBecomeStringsUnlessAllowed() throws IOException {
        String api = read("src/apis/default_api.rs");
        assertTrue(api.contains("metadata: Option<&str>,"), api);
        assertTrue(api.contains("filter: Option<std::collections::HashMap<String, String>>,"), api);
        assertTrue(api.contains("ids: Option<Vec<String>>)"), api);
        // style reset to form/explode: arrays go as repeated parameters.
        assertTrue(api.contains("match \"multi\""), api);
        assertFalse(api.contains("match \"csv\""), api);
    }

    @Test
    void preprocessedSpecIsWritten() throws IOException {
        String json = read("target/specs/preprocessed.json");
        assertTrue(json.contains("\"ThingChoice\""), json);
        assertTrue(json.contains("\"SpecRulesWebhooks\""), json);
        assertFalse(json.contains("#/components/schemas/Item-2\""), json);
        assertTrue(json.contains("\"Item2\""), json);
        // A removed default or example must not come back as an explicit null (a $ref sibling after re-parsing).
        assertFalse(json.contains("\"default\" : null"), json);
        assertFalse(json.contains("\"example\" : null"), json);
    }
}
