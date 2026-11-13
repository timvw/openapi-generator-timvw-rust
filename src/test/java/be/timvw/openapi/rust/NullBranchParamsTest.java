package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameters and form fields whose schema is an inline {@code oneOf}/{@code anyOf} of one schema and
 * {@code type: null} (OpenAPI 3.1), on src/test/resources/null_branch_params.yaml: each takes that schema's type and
 * goes out as a parameter of that schema does. What goes on the wire is in src/test/rust/null_branch_params.rs,
 * which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class NullBranchParamsTest {

    private static final String SPEC = "src/test/resources/null_branch_params.yaml";

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "null_branch_params_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api() throws IOException {
        return Files.readString(out.resolve("src/apis/default_api.rs"));
    }

    /** The signature line of a function. */
    private static String signature(String function) throws IOException {
        String api = api();
        int start = api.indexOf("pub async fn " + function + "(");
        assertTrue(start >= 0, api);
        return api.substring(start, api.indexOf('\n', start));
    }

    /** Each parameter takes the type the plain reference takes, not {@code &str}. */
    @Test
    void aParameterTakesTheReferencedType() throws IOException {
        String thing = signature("get_thing");
        for (String argument : List.of("status: Option<models::Status>", "any_status: Option<models::Status>",
                "since: Option<chrono::DateTime<chrono::FixedOffset>>", "count: Option<i64>",
                "mixed: Option<models::Mixed>", "filter: Option<&str>", "owner: Option<uuid::Uuid>",
                "x_status: Option<models::Status>", "session: Option<models::Status>",
                "x_trace: Option<uuid::Uuid>")) {
            assertTrue(thing.contains(argument), argument + " in " + thing);
            String name = argument.substring(0, argument.indexOf(':'));
            if (!name.equals("any_status") && !name.equals("owner") && !name.equals("x_trace")) {
                assertTrue(signature("get_plain").contains(argument), argument + " in get_plain");
            }
        }
    }

    /**
     * A required one stays nullable, as a {@code type: [T, null]} parameter and one of a nullable union are: an
     * {@code Option}, sent when it is {@code Some}. A path parameter always has a value: the type itself.
     */
    @Test
    void aRequiredOneIsAnOptionExceptInThePath() throws IOException {
        String thing = signature("get_thing");
        assertTrue(thing.contains("id: uuid::Uuid,"), thing);
        assertTrue(thing.contains("required_status: Option<models::Status>,"), thing);
        assertTrue(thing.contains("limit: Option<i64>,"), thing);
        assertTrue(thing.contains("text: Option<&str>,"), thing);
        assertTrue(api().contains("if let Some(ref param_value) = p_query_required_status {"), api());
    }

    /** The bytes sent are the plain reference's: the same lines, with the parameters of get_thing. */
    @Test
    void itIsSentAsThePlainReferenceIs() throws IOException {
        String api = api();
        assertTrue(api.contains("format!(\"{}/things/{id}\", configuration.base_path, id=p_path_id);"), api);
        assertTrue(api.contains("req_builder = req_builder.query(&[(\"status\", &param_value.to_string())]);"), api);
        assertTrue(api.contains("req_builder = req_builder.query(&[(\"since\", &param_value.to_rfc3339())]);"), api);
        assertTrue(api.contains("req_builder.header(\"X-Status\", param_value.to_string());"), api);
        assertFalse(api.contains("x-rust-null-branch"), api);
    }

    /** A form field is typed as well: before, a uuid or date-time one was {@code models::uuid::Uuid}. */
    @Test
    void aFormFieldTakesTheReferencedType() throws IOException {
        for (String function : List.of("post_form", "post_multipart")) {
            String form = signature(function);
            assertTrue(form.contains("since: Option<chrono::DateTime<chrono::FixedOffset>>, status: "
                    + "Option<models::Status>, id: Option<uuid::Uuid>, "), form);
        }
        assertTrue(signature("post_thing_form").contains("owner: Option<uuid::Uuid>, options: Option<&timvw_rust_runtime::RequestOptions>)"), api());
        assertFalse(api().contains("models::uuid") || api().contains("models::chrono"), api());
        String docs = Files.readString(out.resolve("docs/DefaultApi.md"));
        assertTrue(docs.contains("| The status to set, if any. |"), docs);
    }

    /**
     * A plain {@code allOf: [$ref]} form field takes the referenced type as well: a uuid did not compile
     * ({@code models::uuid::Uuid}), and a multipart date-time is sent in RFC 3339.
     */
    @Test
    void aPlainAllOfFormFieldTakesTheReferencedType() throws IOException {
        assertTrue(signature("post_form").contains("plain_id: Option<uuid::Uuid>, options: Option<&timvw_rust_runtime::RequestOptions>)"), signature("post_form"));
        String multipart = signature("post_multipart");
        assertTrue(multipart.contains("plain_since: Option<chrono::DateTime<chrono::FixedOffset>>, options: Option<&timvw_rust_runtime::RequestOptions>)"), multipart);
        assertTrue(api().contains("multipart_form.text(\"plain_since\", param_value.to_rfc3339());"), api());
    }

    /**
     * A branch that only wraps a reference in a one-element {@code allOf}, {@code oneOf} or {@code anyOf} gives that
     * reference, in the query and the path; the wrapper's description documents the parameter.
     */
    @Test
    void aWrappedReferenceIsUnwrapped() throws IOException {
        String thing = signature("get_thing");
        assertTrue(thing.contains("wrapped_all: Option<models::Status>, wrapped_one: Option<models::Status>,"), thing);
        assertTrue(signature("get_wrapped").contains("a: uuid::Uuid, b: models::Status, c: &str, options: Option<&timvw_rust_runtime::RequestOptions>)"),
                signature("get_wrapped"));
        String docs = Files.readString(out.resolve("docs/DefaultApi.md"));
        assertTrue(docs.contains("**wrapped_one** | Option<[**Status**](Status.md)> | The status, wrapped in a "
                + "one-branch oneOf. |"), docs);
    }

    /** A path parameter is never an {@code Option}, a {@code type: [string, null]} one included: no unwrap. */
    @Test
    void aNullablePathParameterIsItsType() throws IOException {
        String api = api();
        assertTrue(api.contains("c=crate::apis::urlencode(p_path_c));"), api);
        assertFalse(api.contains("p_path_c.unwrap()"), api);
    }

    /** A required nullable header left {@code None} is not sent, as in the query and a cookie (it was sent empty). */
    @Test
    void aRequiredNullableHeaderLeftNoneIsNotSent() throws IOException {
        String api = api();
        assertTrue(api.contains("if let Some(param_value) = p_header_x_required {"), api);
        assertFalse(api.contains("req_builder.header(\"X-Required\", \"\")"), api);
    }

    /** The component the form uses keeps its nullable field: only the form field is unwrapped. */
    @Test
    void theModelKeepsItsNullableField() throws IOException {
        String thing = Files.readString(out.resolve("src/models/thing.rs"));
        assertTrue(thing.contains("pub owner: Option<Option<uuid::Uuid>>,"), thing);
    }

    @Test
    void theNonNullBranch() {
        io.swagger.v3.oas.models.media.Schema<?> ref = new io.swagger.v3.oas.models.media.Schema<>()
                .$ref("#/components/schemas/Status");
        io.swagger.v3.oas.models.media.Schema<?> nul = new io.swagger.v3.oas.models.media.Schema<>();
        nul.addType("null");
        io.swagger.v3.oas.models.media.Schema<?> text = new io.swagger.v3.oas.models.media.Schema<>();
        text.addType("string");
        assertEquals(ref, SpecRules.nonNullBranch(new io.swagger.v3.oas.models.media.Schema<>()
                .oneOf(List.of(ref, nul))));
        assertEquals(ref, SpecRules.nonNullBranch(new io.swagger.v3.oas.models.media.Schema<>()
                .anyOf(List.of(nul, ref))));
        // two non-null branches are a union, no null branch is no nullable one
        assertEquals(null, SpecRules.nonNullBranch(new io.swagger.v3.oas.models.media.Schema<>()
                .oneOf(List.of(ref, text, nul))));
        assertEquals(null, SpecRules.nonNullBranch(new io.swagger.v3.oas.models.media.Schema<>()
                .oneOf(List.of(ref))));
        // a one-element wrapper gives its reference, however deep; anything else stays as it is
        assertEquals(ref, SpecRules.peel(new io.swagger.v3.oas.models.media.Schema<>().allOf(List.of(
                new io.swagger.v3.oas.models.media.Schema<>().description("wrapped").oneOf(List.of(ref))))));
        io.swagger.v3.oas.models.media.Schema<?> two = new io.swagger.v3.oas.models.media.Schema<>()
                .oneOf(List.of(ref, text));
        assertEquals(two, SpecRules.peel(two));
        io.swagger.v3.oas.models.media.Schema<?> inline = new io.swagger.v3.oas.models.media.Schema<>()
                .allOf(List.of(text));
        assertEquals(inline, SpecRules.peel(inline));
    }

    /** Runs src/test/rust/null_branch_params.rs on the generated crate. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path manifest = out.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/null_branch_params.rs"), out.resolve("tests/null_branch_params.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "null_branch_params");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
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
        Files.delete(log);
        return output;
    }
}
