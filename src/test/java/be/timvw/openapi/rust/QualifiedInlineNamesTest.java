package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.utils.ImplementationVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.3: an inline schema is named after a unique title or where it stands; a real collision fails generation. */
class QualifiedInlineNamesTest {

    @TempDir
    static Path qualified;

    @TempDir
    static Path stock;

    @TempDir
    static Path other;

    /**
     * Before openapi-generator 7.27, an inline schema carrying the title of its union or parent replaced that
     * model, losing its own fields. 7.27 keeps them; qualifiedInlineNames still renames it either way.
     */
    private static final boolean STOCK_LOSES_TITLED_FIELDS = minorVersion() < 27;

    /** The minor version of the running openapi-generator 7.x. */
    private static int minorVersion() {
        return Integer.parseInt(ImplementationVersion.read().split("\\.")[1]);
    }

    @BeforeAll
    static void generate() {
        generate("inline_names.yaml", qualified, true);
        generate("inline_names.yaml", stock, false);
    }

    private static void generate(String spec, Path output, boolean option) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "names");
        props.put(TimvwRustCodegen.TITLE_NAMING, "false");
        if (option) {
            props.put(TimvwRustCodegen.QUALIFIED_INLINE_NAMES, "true");
        }
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/" + spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static boolean exists(Path output, String model) {
        return Files.exists(output.resolve("src/models/" + model + ".rs"));
    }

    private static String failure(String spec, Path output) {
        return assertThrows(IllegalArgumentException.class, () -> generate(spec, output, true)).getMessage();
    }

    @Test
    void anInlineTitleCannotReplaceTheComponentItNormalizesTo() throws IOException {
        // Stock: one file, last write wins, so the component lost its fields and the branch holds a reference to itself.
        assertFalse(read(stock, "static_chunking_strategy").contains("max_chunk_size_tokens"));

        assertTrue(read(qualified, "static_chunking_strategy").contains("pub max_chunk_size_tokens: i32,"));
        String branch = read(qualified, "chunking_strategy_static_chunking_strategy");
        assertTrue(branch.contains("pub struct ChunkingStrategyStaticChunkingStrategy {"), branch);
        assertTrue(branch.contains("models::StaticChunkingStrategy"), branch);
    }

    @Test
    void aBranchWithTheTitleOfItsUnionKeepsItsFields() throws IOException {
        String stockModel = read(stock, "tracing_configuration");
        if (STOCK_LOSES_TITLED_FIELDS) {
            // Stock: the object is overwritten by the union, which then references itself.
            assertTrue(stockModel.contains("models::TracingConfiguration"), stockModel);
        } else {
            // openapi-generator 7.27 keeps the object's fields under the title.
            assertTrue(stockModel.contains("pub workflow_name:"), stockModel);
        }
        assertFalse(exists(qualified, "tracing_configuration"));

        String union = read(qualified, "session_tracing");
        assertTrue(union.contains("models::SessionTracingTracingConfiguration"), union);
        assertFalse(union.contains("models::SessionTracing>"), union);
        assertTrue(read(qualified, "session_tracing_tracing_configuration").contains("pub workflow_name:"));
    }

    @Test
    void aPropertyWithTheTitleOfItsParentKeepsItsFields() throws IOException {
        String stockModel = read(stock, "grammar_format");
        if (STOCK_LOSES_TITLED_FIELDS) {
            // Stock: the object requires a box of itself, and can never decode.
            assertTrue(stockModel.contains("pub grammar: Box<models::GrammarFormat>,"), stockModel);
        } else {
            // openapi-generator 7.27 keeps the inner object's fields under the title.
            assertTrue(stockModel.contains("pub definition:"), stockModel);
        }
        assertFalse(exists(qualified, "grammar_format"));

        String outer = read(qualified, "session_format");
        assertTrue(outer.contains("models::SessionFormatGrammar"), outer);
        assertTrue(read(qualified, "session_format_grammar").contains("pub definition:"));
    }

    @Test
    void unnamedBranchesAreQualifiedInsteadOfNumbered() throws IOException {
        assertTrue(exists(stock, "object0"));
        assertTrue(exists(stock, "object0_1"));

        assertFalse(exists(qualified, "object0"));
        assertFalse(exists(qualified, "object0_1"));
        assertTrue(read(qualified, "left_union_object0").contains("pub left:"));
        assertTrue(read(qualified, "right_union_object0").contains("pub right:"));
    }

    @Test
    void aNullableObjectIsNamedAfterItsProperty() throws IOException {
        assertTrue(read(qualified, "nullable_details").contains("pub reason:"));
        assertTrue(read(qualified, "nullable").contains("models::NullableDetails"));
    }

    @Test
    void aUniqueTitleNamesTheModelAndWhatLiesBelowIt() throws IOException {
        assertTrue(read(qualified, "auto_chunking_strategy").contains("pub struct AutoChunkingStrategy {"));
        assertFalse(exists(qualified, "chunking_strategy_auto_chunking_strategy"));
        assertTrue(read(qualified, "widget_config").contains("models::WidgetConfigSize"));
        assertTrue(read(qualified, "widget_config_size").contains("pub width:"));
        assertTrue(read(qualified, "unique").contains("models::WidgetConfig"));
    }

    @Test
    void identicalShapesWithOneTitleShareOneModel() throws IOException {
        assertTrue(exists(qualified, "shared_options"));
        assertFalse(exists(qualified, "shared_options_1"));
        assertFalse(exists(qualified, "same_shape_one_options"));
        assertFalse(exists(qualified, "same_shape_two_settings"));
        assertTrue(read(qualified, "same_shape_one").contains("models::SharedOptions"));
        assertTrue(read(qualified, "same_shape_two").contains("models::SharedOptions"));
        assertTrue(exists(qualified, "shared_options_nested"));
        assertFalse(exists(qualified, "shared_options_nested_1"));
    }

    @Test
    void aTitleThatIsTheNameOfAComponentNamesNothing() throws IOException {
        assertTrue(read(qualified, "existing_model").contains("pub original:"));
        assertFalse(read(qualified, "existing_model").contains("pub other:"));
        assertTrue(read(qualified, "title_like_component_thing").contains("pub other:"));
    }

    @Test
    void aTitleThatIsTheNameOfItsParentNamesNothing() throws IOException {
        // The component holding it.
        String parent = read(qualified, "parent_model");
        assertTrue(parent.contains("models::ParentModelChild"), parent);
        assertTrue(read(qualified, "parent_model_child").contains("pub value:"));
        // The untitled inline schema holding it.
        String middle = read(qualified, "outer_middle");
        assertTrue(middle.contains("models::OuterMiddleLeaf"), middle);
        assertFalse(middle.contains("pub value:"), middle);
        assertTrue(read(qualified, "outer_middle_leaf").contains("pub value:"));
    }

    @Test
    void theOutcomeDoesNotDependOnTheOrderOfTheSpec() throws IOException {
        Path forward = other.resolve("forward");
        Path reversed = other.resolve("reversed");
        generate("title_order_forward.yaml", forward, true);
        generate("title_order_reversed.yaml", reversed, true);
        // Two shapes: neither has the title, whichever comes first.
        for (Path output : List.of(forward, reversed)) {
            assertFalse(exists(output, "tool_filter"));
            assertTrue(read(output, "alpha_filter").contains("pub one:"));
            assertTrue(read(output, "beta_filter").contains("pub two:"));
            assertTrue(exists(output, "same_options"));
        }
        assertEquals(models(forward), models(reversed));
        for (String model : models(forward)) {
            if (!model.equals("mod.rs")) {
                assertEquals(Files.readString(forward.resolve("src/models/" + model)),
                        Files.readString(reversed.resolve("src/models/" + model)), model);
            }
        }
    }

    private static Set<String> models(Path output) throws IOException {
        try (Stream<Path> files = Files.list(output.resolve("src/models"))) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void oneTitleAtTwoPlacesIsTwoModels() throws IOException {
        assertTrue(exists(stock, "tool_filter_1"));

        assertFalse(exists(qualified, "tool_filter"));
        assertTrue(read(qualified, "first_filter").contains("pub one:"));
        assertTrue(read(qualified, "second_filter").contains("pub two:"));
    }

    @Test
    void branchesOfOneUnionWithOneTitleGetTheirPosition() throws IOException {
        assertTrue(read(qualified, "same_titles_same0").contains("pub a:"));
        assertTrue(read(qualified, "same_titles_same1").contains("pub b:"));
    }

    @Test
    void itemsAndValuesAreNamedAfterTheirContainer() throws IOException {
        assertTrue(read(qualified, "containers_list_inner").contains("pub x:"));
        assertTrue(read(qualified, "containers_labels_value").contains("pub y:"));
        String containers = read(qualified, "containers");
        assertTrue(containers.contains("Vec<models::ContainersListInner>"), containers);
        assertTrue(containers.contains("std::collections::HashMap<String, models::ContainersLabelsValue>"), containers);
    }

    @Test
    void operationSchemasAreNamedAfterTheOperation() throws IOException {
        assertTrue(read(qualified, "create_thing_request").contains("pub name:"));
        // A unique title names the response here as well.
        assertTrue(read(qualified, "created_thing").contains("pub id:"));
        assertFalse(exists(qualified, "create_thing_200_response"));
    }

    @Test
    void identicalSchemasMayShareAModel() throws IOException {
        // Different keys, annotations and referenced names; the same shape, also through the recursive reference.
        String twin = read(qualified, "twin_info");
        assertTrue(twin.contains("pub struct TwinInfo {"), twin);
        assertTrue(twin.contains("pub leaf:"), twin);
    }

    @Test
    void twoComponentsWithOneRustNameFail() {
        String message = failure("name_collision_components.yaml", other.resolve("components"));
        assertTrue(message.contains("model StaticChunkingStrategy"), message);
        assertTrue(message.contains("'Static_Chunking_Strategy' at #/components/schemas/Static_Chunking_Strategy"), message);
        assertTrue(message.contains("'StaticChunkingStrategy' at #/components/schemas/StaticChunkingStrategy"), message);
        assertTrue(message.contains("properties: max_chunk_size_tokens only in the second"), message);
    }

    @Test
    void anInlineSchemaNamedLikeAComponentFails() {
        String message = failure("name_collision_inline.yaml", other.resolve("inline"));
        assertTrue(message.contains("model AuditLogActor"), message);
        assertTrue(message.contains("'AuditLogActor' at #/components/schemas/AuditLogActor"), message);
        assertTrue(message.contains("'AuditLog_actor' at #/components/schemas/AuditLog/properties/actor"), message);
    }

    @Test
    void twoInlineSchemasWithOneKeyFailBeforeTheResolverOverwritesOne() {
        String message = failure("name_collision_overwrite.yaml", other.resolve("overwrite"));
        assertTrue(message.contains("model FooAB"), message);
        assertTrue(message.contains("#/components/schemas/Foo/properties/a_b"), message);
        assertTrue(message.contains("#/components/schemas/Foo/properties/a/properties/b"), message);
    }

    @Test
    void withoutTheOptionACollisionStaysSilent() throws IOException {
        Path output = other.resolve("stock-components");
        generate("name_collision_components.yaml", output, false);
        assertTrue(exists(output, "static_chunking_strategy"));
        assertEquals(1, countModels(output, "static_chunking_strategy"));
    }

    private static long countModels(Path output, String model) throws IOException {
        return Files.readAllLines(output.resolve("src/models/mod.rs")).stream()
                .filter(l -> l.equals("pub mod " + model + ";")).count();
    }

    @Test
    void theOptionIsOffByDefaultAndAdvertised() {
        TimvwRustCodegen codegen = new TimvwRustCodegen();
        assertTrue(codegen.cliOptions().stream()
                .anyMatch(o -> TimvwRustCodegen.QUALIFIED_INLINE_NAMES.equals(o.getOpt())
                        && "false".equals(o.getDefault())));
    }
}
