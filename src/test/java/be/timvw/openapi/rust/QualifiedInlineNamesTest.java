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
    static Path other;

    @BeforeAll
    static void generate() {
        generate("inline_names.yaml", qualified);
    }

    private static void generate(String spec, Path output) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "names");
        props.put(TimvwRustCodegen.TITLE_NAMING, "false");
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
        return assertThrows(IllegalArgumentException.class, () -> generate(spec, output)).getMessage();
    }

    @Test
    void anInlineTitleCannotReplaceTheComponentItNormalizesTo() throws IOException {
        assertTrue(read(qualified, "static_chunking_strategy").contains("pub max_chunk_size_tokens: i64,"));
        String branch = read(qualified, "chunking_strategy_static_chunking_strategy");
        assertTrue(branch.contains("pub struct ChunkingStrategyStaticChunkingStrategy {"), branch);
        assertTrue(branch.contains("models::StaticChunkingStrategy"), branch);
    }

    @Test
    void aBranchWithTheTitleOfItsUnionKeepsItsFields() throws IOException {
        assertFalse(exists(qualified, "tracing_configuration"));

        String union = read(qualified, "session_tracing");
        assertTrue(union.contains("models::SessionTracingTracingConfiguration"), union);
        assertFalse(union.contains("models::SessionTracing>"), union);
        assertTrue(read(qualified, "session_tracing_tracing_configuration").contains("pub workflow_name:"));
    }

    @Test
    void aPropertyWithTheTitleOfItsParentKeepsItsFields() throws IOException {
        assertFalse(exists(qualified, "grammar_format"));

        String outer = read(qualified, "session_format");
        assertTrue(outer.contains("models::SessionFormatGrammar"), outer);
        assertTrue(read(qualified, "session_format_grammar").contains("pub definition:"));
    }

    @Test
    void unnamedBranchesAreQualifiedInsteadOfNumbered() throws IOException {
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
        generate("title_order_forward.yaml", forward);
        generate("title_order_reversed.yaml", reversed);
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
}
