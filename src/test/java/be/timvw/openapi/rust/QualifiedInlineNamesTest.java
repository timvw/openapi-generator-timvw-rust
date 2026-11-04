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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.3: inline schemas are named after where they stand, and a real name collision fails generation. */
class QualifiedInlineNamesTest {

    @TempDir
    static Path qualified;

    @TempDir
    static Path stock;

    @TempDir
    static Path other;

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
        assertTrue(exists(qualified, "chunking_strategy_auto_chunking_strategy"));
    }

    @Test
    void aBranchWithTheTitleOfItsUnionKeepsItsFields() throws IOException {
        assertFalse(exists(stock, "session_tracing_tracing_configuration"));

        String union = read(qualified, "session_tracing");
        assertTrue(union.contains("models::SessionTracingTracingConfiguration"), union);
        assertFalse(union.contains("models::SessionTracing>"), union);
        assertTrue(read(qualified, "session_tracing_tracing_configuration").contains("pub workflow_name:"));
    }

    @Test
    void aPropertyWithTheTitleOfItsParentKeepsItsFields() throws IOException {
        // Stock: the object requires a box of itself, and can never decode.
        String stockModel = read(stock, "grammar_format");
        assertTrue(stockModel.contains("pub grammar: Box<models::GrammarFormat>,"), stockModel);

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
        assertFalse(exists(qualified, "thing_body"));
        assertFalse(exists(qualified, "created_thing"));
        assertTrue(read(qualified, "create_thing_request").contains("pub name:"));
        assertTrue(read(qualified, "create_thing_200_response").contains("pub id:"));
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
