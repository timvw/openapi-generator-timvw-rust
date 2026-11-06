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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.4: integersAsI64 renders an integer as i64 unless the spec says format: int32. */
class IntegersAsI64Test {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @TempDir
    static Path legacyOn;

    @TempDir
    static Path legacyOff;

    @BeforeAll
    static void generate() {
        generate(on, Map.of(TimvwRustCodegen.INTEGERS_AS_I64, "true"));
        generate(off, Map.of());
        generate(legacyOn, Map.of(TimvwRustCodegen.INTEGERS_AS_I64, "true",
                TimvwRustCodegen.LEGACY_MODEL_REWRITES, "true"));
        generate(legacyOff, Map.of(TimvwRustCodegen.LEGACY_MODEL_REWRITES, "true"));
    }

    private static void generate(Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "integers");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/integers.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String model(Path output, String name) throws IOException {
        return Files.readString(output.resolve("src/models/" + name + ".rs"));
    }

    /** The signature of an API function, up to its return type. */
    private static String function(Path output, String name) throws IOException {
        String api = Files.readString(output.resolve("src/apis/default_api.rs"));
        int start = api.indexOf("pub async fn " + name + "(");
        assertTrue(start >= 0, api);
        return api.substring(start, api.indexOf('{', start));
    }

    private static void assertContainsAll(String text, List<String> needles) {
        for (String needle : needles) {
            assertTrue(text.contains(needle), needle + " not in:\n" + text);
        }
    }

    @Test
    void onlyInt32StaysI32OnModelFields() throws IOException {
        assertContainsAll(model(on, "thing"), List.of(
                "pub plain: i64,", "pub big: i64,", "pub stamp: i64,", "pub odd: i64,", "pub bounded: i64,",
                "pub opt: Option<i64>,", "pub nullable_opt: Option<Option<i64>>,",
                "pub small: i32,", "pub opt_small: Option<i32>,"));
    }

    @Test
    void aliasesFollowTheFormatOfTheirTarget() throws IOException {
        assertContainsAll(model(on, "thing"), List.of(
                "pub count: i64,", "pub tiny: i32,", "pub list_count: Option<Vec<i64>>,"));
    }

    @Test
    void containerItemsFollowTheRule() throws IOException {
        assertContainsAll(model(on, "thing"), List.of(
                "pub list: Option<Vec<i64>>,", "pub matrix: Option<Vec<Vec<i64>>>,",
                "pub by_name: Option<std::collections::HashMap<String, i64>>,",
                "pub list_small: Option<Vec<i32>>,",
                "pub by_name_small: Option<std::collections::HashMap<String, i32>>,"));
    }

    @Test
    void constructorParametersFollowTheRule() throws IOException {
        assertContainsAll(model(on, "thing"), List.of(
                "pub fn new(plain: i64, small: i32, big: i64, stamp: i64, odd: i64, bounded: i64, count: i64, tiny: i32)"));
    }

    @Test
    void numbersAreUntouched() throws IOException {
        for (Path output : List.of(on, off)) {
            assertContainsAll(model(output, "thing"), List.of("pub price: Option<f64>,", "pub ratio: Option<f64>,"));
        }
    }

    @Test
    void integerEnumsKeepTheirRendering() throws IOException {
        assertContainsAll(model(on, "priority"), List.of("#[repr(i64)]", "pub enum Priority {", "Serialize_repr"));
        assertEquals(model(off, "priority"), model(on, "priority"));
        assertEquals(inlineEnum(off), inlineEnum(on));
    }

    /** The inline enum rendered behind the struct, which the template handles apart from component enums. */
    private static String inlineEnum(Path output) throws IOException {
        String thing = model(output, "thing");
        int level = thing.indexOf("pub enum Level {");
        assertTrue(level >= 0, thing);
        return thing.substring(level);
    }

    @Test
    void pathQueryAndHeaderParametersFollowTheRule() throws IOException {
        assertContainsAll(function(on, "get_thing"), List.of(
                "id: i64,", "shard: i32,", "limit: Option<i64>,", "after: Option<i64>,", "since: Option<i64>,",
                "page: Option<i32>,", "ids: Option<Vec<i64>>,", "pages: Option<Vec<i32>>,", "x_count: Option<i64>,",
                "x_small: Option<i32>"));
    }

    @Test
    void multipartFieldsFollowTheRule() throws IOException {
        assertContainsAll(function(on, "upload"), List.of("size: i64,", "chunk: i32,", "stamp: Option<i64>"));
    }

    @Test
    void responsesFollowTheRule() throws IOException {
        assertTrue(function(on, "get_count").contains("Result<i64,"), function(on, "get_count"));
        assertTrue(function(on, "get_small_count").contains("Result<i32,"), function(on, "get_small_count"));
    }

    @Test
    void unionVariantsFollowTheRule() throws IOException {
        assertTrue(model(on, "epochs").contains("Integer(i64),"), model(on, "epochs"));
        assertTrue(model(on, "small_epochs").contains("Integer(i32),"), model(on, "small_epochs"));
    }

    @Test
    void unionsTheLegacyRewritesWriteFollowTheRule() throws IOException {
        String tokens = model(legacyOn, "tokens");
        assertContainsAll(tokens, List.of("ArrayOfIntegers(Vec<i64>),", "ArrayOfIntegerArrays(Vec<Vec<i64>>),",
                "impl Default for Tokens"));
        assertFalse(tokens.contains("i32"), tokens);
        assertContainsAll(model(legacyOn, "small_tokens"), List.of("ArrayOfIntegers(Vec<i32>),",
                "ArrayOfIntegerArrays(Vec<Vec<i32>>),", "impl Default for SmallTokens"));
    }

    @Test
    void withoutTheOptionIntegersStayAsInStock() throws IOException {
        assertContainsAll(model(off, "thing"), List.of(
                "pub plain: i32,", "pub small: i32,", "pub big: i64,", "pub stamp: i32,", "pub odd: i32,",
                "pub bounded: i32,", "pub count: i32,", "pub list: Option<Vec<i32>>,"));
        assertContainsAll(function(off, "get_thing"), List.of("id: i32,", "limit: Option<i32>,", "after: Option<i64>,"));
        assertTrue(model(off, "epochs").contains("Integer(i32),"), model(off, "epochs"));
        assertContainsAll(model(legacyOff, "tokens"), List.of("ArrayOfIntegers(Vec<i32>),",
                "ArrayOfIntegerArrays(Vec<Vec<i32>>),"));
    }

    @Test
    void unsignedAndBestFitStayRejected() {
        for (String option : List.of("preferUnsignedInt", "bestFitInt")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
                TimvwRustCodegen codegen = new TimvwRustCodegen();
                codegen.additionalProperties().put(TimvwRustCodegen.INTEGERS_AS_I64, "true");
                codegen.additionalProperties().put(option, "true");
                codegen.processOpts();
            });
            assertTrue(e.getMessage().contains(option), e.getMessage());
        }
    }
}
