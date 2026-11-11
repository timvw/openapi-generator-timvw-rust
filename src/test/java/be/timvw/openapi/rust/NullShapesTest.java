package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema of only {@code null} ({@code type: 'null'}) and one of any value or {@code null}
 * ({@code oneOf}/{@code anyOf: [{}, {type: 'null'}]}) are {@code serde_json::Value}, which holds {@code null}, as a
 * property, list item, map value, component, union variant, request body, response, query parameter and form field.
 * They referenced models that were never written ({@code models::Null}, {@code models::OneOfLessThanGreaterThan}),
 * or were an empty struct. A parameter or form field of only {@code null} fails generation: it could never be sent.
 */
class NullShapesTest {

    private static final String MAP = "std::collections::HashMap<String, ";

    @TempDir
    static Path out;

    @TempDir
    static Path helper;

    @TempDir
    static Path v30;

    @BeforeAll
    static void generate() {
        generate("src/test/resources/null_shapes.yaml", out, Map.of("packageName", "null_shapes_fixture"));
        generate("src/test/resources/null_shapes.yaml", helper,
                Map.of("packageName", "null_shapes_fixture", "multipartHelper", "true"));
        generate("src/test/resources/null_shapes_30.yaml", v30, Map.of("packageName", "null_shapes_30_fixture"));
    }

    private static void generate(String spec, Path output, Map<String, Object> props) {
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    @Test
    void nullAndAnyOrNullAreJsonValuesEverywhere() throws IOException {
        String holder = Files.readString(out.resolve("src/models/holder.rs"));
        // as {} is: nullable, so an optional field is a double Option and a required one an Option
        assertContains(holder, "    pub any_opt: Option<Option<serde_json::Value>>,\n");
        assertContains(holder, "    pub any_req: Option<serde_json::Value>,\n");
        // serde_json::Value holds null itself: no Option around an item or a value
        assertContains(holder, "    pub any_items: Option<Vec<serde_json::Value>>,\n");
        assertContains(holder, "    pub any_values: Option<" + MAP + "serde_json::Value>>,\n");
        assertContains(holder, "    pub nulls: Option<Vec<serde_json::Value>>,\n");
        assertContains(holder, "    pub nulls_req: Vec<serde_json::Value>,\n");
        assertContains(holder, "    pub null_values: Option<" + MAP + "serde_json::Value>>,\n");
        assertContains(holder, "    pub null_opt: Option<Option<serde_json::Value>>,\n");
        assertContains(holder, "    pub null_req: Option<serde_json::Value>,\n");
        String refs = Files.readString(out.resolve("src/models/refs.rs"));
        assertContains(refs, "    pub any: Option<Option<serde_json::Value>>,\n");
        assertContains(refs, "    pub null_only: Option<Option<serde_json::Value>>,\n");
        assertContains(refs, "    pub list_of_any_refs: Option<Vec<serde_json::Value>>,\n");
        // the components themselves are no models: no empty struct, no models::Null
        assertFalse(Files.exists(out.resolve("src/models/any_or_null.rs")));
        assertFalse(Files.exists(out.resolve("src/models/only_null.rs")));
        // a union holding a branch of any value or null holds any value; a reference to a component of only null is a
        // null branch; enum [null] and a single-entry allOf of null are only null
        assertFalse(Files.exists(out.resolve("src/models/nested_any.rs")));
        assertFalse(Files.exists(out.resolve("src/models/enum_null.rs")));
        assertContains(Files.readString(out.resolve("src/models/text_or_null_ref.rs")),
                "    pub value: Option<Option<String>>,\n");
        String wrapped = Files.readString(out.resolve("src/models/wrapped.rs"));
        assertContains(wrapped, "    pub all_null: Option<serde_json::Value>,\n");
        assertContains(wrapped, "    pub enum_null: Option<Option<serde_json::Value>>,\n");
        assertContains(wrapped, "    pub nested_any: Option<Option<serde_json::Value>>,\n");
        assertContains(wrapped, "    pub enum_ref: Option<Option<serde_json::Value>>,\n");
        assertContains(wrapped, "    pub nested_ref: Option<Option<serde_json::Value>>,\n");
        assertContains(wrapped, "    pub nested_component: Option<Option<serde_json::Value>>,\n");
        String holder30 = Files.readString(v30.resolve("src/models/holder.rs"));
        assertContains(holder30, "    pub any_opt: Option<Option<serde_json::Value>>,\n");
        assertContains(holder30, "    pub any_req: Option<serde_json::Value>,\n");
        assertContains(holder30, "    pub any_items: Option<Vec<serde_json::Value>>,\n");
        assertContains(holder30, "    pub nested: Option<Option<serde_json::Value>>,\n");
        assertContains(Files.readString(out.resolve("src/models/uni.rs")),
                "    Text(String),\n    ArrayOfItems(Vec<serde_json::Value>),\n}");
        for (Path crate : List.of(out, helper)) {
            String api = Files.readString(crate.resolve("src/apis/default_api.rs"));
            assertContains(api, "get_things(configuration: &configuration::Configuration, filter: Option<&str>, "
                    + "filters: Option<Vec<serde_json::Value>>) -> Result<models::Holder, ");
            assertContains(api, "post_things(configuration: &configuration::Configuration, body: "
                    + "Option<&serde_json::Value>) -> Result<serde_json::Value, ");
            assertContains(api, "post_form(configuration: &configuration::Configuration, anything: "
                    + "Option<serde_json::Value>, count: Option<i64>)");
            assertContains(api, "upload(configuration: &configuration::Configuration, file: "
                    + "Option<std::path::PathBuf>, meta: Option<serde_json::Value>)");
            assertFalse(api.contains("models::Null") || api.contains("LessThanGreaterThan")
                    || api.contains("models::serde_json"), api);
        }
    }

    @Test
    void aParameterOfOnlyNullFailsGeneration(@TempDir Path dir) {
        assertFails("src/test/resources/null_shapes_param.yaml", dir,
                "#/paths//things/get/parameters/nothing: parameter 'nothing' can only be null");
    }

    @Test
    void aFormFieldOfOnlyNullFailsGeneration(@TempDir Path dir) {
        assertFails("src/test/resources/null_shapes_form.yaml", dir,
                "#/paths//form/post/requestBody/content/multipart/form-data: form field 'none' can only be null");
    }

    /**
     * A parameter of only null fails wherever it is (header, cookie, path, a {@code $ref} to a component parameter),
     * and through a single-entry {@code allOf}, as a map of null values ({@code deepObject}), and as a form field of
     * a form reached through {@code allOf: [$ref]}. The message names the operation.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            header    | {name: h, in: header, schema: {type: 'null'}}                          | /parameters/h: parameter 'h'
            cookie    | {name: c, in: cookie, schema: {type: 'null'}}                          | /parameters/c: parameter 'c'
            path      | {name: id, in: path, required: true, schema: {type: 'null'}}           | /parameters/id: parameter 'id'
            ref       | {$ref: '#/components/parameters/NullP'}                                | /parameters/#/components/parameters/NullP: parameter 'n'
            allOf     | {name: q, in: query, schema: {allOf: [{type: 'null'}]}}                 | /parameters/q: parameter 'q'
            deepObject | {name: f, in: query, style: deepObject, schema: {type: object, additionalProperties: {type: 'null'}}} | /parameters/f: parameter 'f'
            enum      | {name: e, in: query, schema: {enum: [null]}}                            | /parameters/e: parameter 'e'
            """)
    void everyParameterOfOnlyNullFailsGeneration(String name, String parameter, String message, @TempDir Path dir)
            throws IOException {
        String path = name.equals("path") ? "/things/{id}" : "/things";
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  '%s':
                    get:
                      operationId: getThings
                      parameters:
                        - %s
                      responses:
                        '204': {description: ok}
                components:
                  parameters:
                    NullP: {name: n, in: query, schema: {$ref: '#/components/schemas/OnlyNull'}}
                  schemas:
                    OnlyNull: {type: 'null'}
                """.formatted(path, parameter));
        assertFails(spec.toString(), dir.resolve("out"), "/get" + message + " can only be null");
    }

    @Test
    void aFormFieldOfOnlyNullBehindAllOfFailsGeneration(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /form:
                    post:
                      operationId: postForm
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                            schema:
                              allOf: [{$ref: '#/components/schemas/Form'}]
                      responses:
                        '204': {description: ok}
                components:
                  schemas:
                    Form:
                      type: object
                      properties:
                        none: {allOf: [{type: 'null'}]}
                """);
        assertFails(spec.toString(), dir.resolve("out"), "form field 'none' can only be null");
    }

    /** An unused component parameter of only null is not checked: only what an operation uses is. */
    @Test
    void anUnusedComponentParameterOfOnlyNullDoesNotFail() throws IOException {
        assertTrue(Files.exists(out.resolve("src/apis/default_api.rs")));
        assertFalse(Files.readString(out.resolve("src/apis/default_api.rs")).contains("unused"));
    }

    private static void assertFails(String spec, Path dir, String message) {
        Throwable e = assertThrows(RuntimeException.class,
                () -> generate(spec, dir, Map.of("packageName", "fixture")));
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains(message), messages.toString());
    }

    /** Runs src/test/rust/null_shapes.rs on the generated crate, after clippy on both. Needs cargo. */
    @Test
    void nullAndValuesRoundTrip() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        for (Path crate : List.of(out, helper, v30)) {
            String clippy = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            assertNotNull(clippy, "clippy did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                    && !clippy.contains("warning:"), clippy);
        }
        Path manifest = out.resolve("Cargo.toml");
        String toml = Files.readString(manifest);
        if (!toml.contains("[dev-dependencies]")) {
            Files.writeString(manifest, toml + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/null_shapes.rs"), out.resolve("tests/null_shapes.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(out, 900, "test", "--test", "null_shapes");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected\n" + needle + "\nin\n" + haystack);
    }

    /** What cargo printed, or {@code null} when it cannot run or does not finish in time. */
    private static String cargo(Path dir, long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            return null;
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output = Files.readString(log);
        Files.deleteIfExists(log);
        return output;
    }
}
