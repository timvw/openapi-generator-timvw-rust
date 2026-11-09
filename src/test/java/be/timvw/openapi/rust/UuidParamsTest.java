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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code format: uuid} path, query and header parameters and form fields (decision 6) on
 * src/test/resources/uuid_params.yaml: they take {@code uuid::Uuid}, as a model property does, and send its hyphenated
 * {@code Display}. What goes on the wire is in src/test/rust/uuid_params.rs, which {@link #whatItDoesAtRunTime} runs
 * with cargo against a local server.
 */
class UuidParamsTest {

    private static final String SPEC = "src/test/resources/uuid_params.yaml";

    @TempDir
    static Path out;

    @TempDir
    static Path helper;

    @TempDir
    static Path noUuids;

    @TempDir
    static Path responseOnly;

    @TempDir
    static Path arrayParameterOnly;

    @TempDir
    static Path dateTimeErrorOnly;

    @BeforeAll
    static void generate() {
        generate(SPEC, out, false);
        generate(SPEC, helper, true);
        generate("src/test/resources/date_params.yaml", noUuids, false);
        generate("src/test/resources/uuid_response_only.yaml", responseOnly, false);
        generate("src/test/resources/uuid_array_param_only.yaml", arrayParameterOnly, false);
        generate("src/test/resources/date_time_error_only.yaml", dateTimeErrorOnly, false);
    }

    private static void generate(String spec, Path output, boolean multipartHelper) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "uuid_params_fixture");
        if (multipartHelper) {
            props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path output) throws IOException {
        return Files.readString(output.resolve("src/apis/default_api.rs"));
    }

    @Test
    void aUuidParameterIsAUuid() throws IOException {
        String api = api(out);
        assertTrue(api.contains("pub async fn get_item(configuration: &configuration::Configuration, id: uuid::Uuid, "
                + "ids: Vec<uuid::Uuid>, owner: uuid::Uuid, before: Option<uuid::Uuid>, tags: Vec<uuid::Uuid>, "
                + "x_request_id: uuid::Uuid, parent: Option<uuid::Uuid>, tags_csv: Option<Vec<uuid::Uuid>>, "
                + "x_trace_id: Option<uuid::Uuid>, x_span_ids: Option<Vec<uuid::Uuid>>)"), api);
        assertTrue(api.contains("pub async fn post_form(configuration: &configuration::Configuration, "
                + "id: uuid::Uuid, parent: Option<uuid::Uuid>, ids: Option<Vec<uuid::Uuid>>)"), api);
        assertTrue(api.contains("pub async fn post_upload(configuration: &configuration::Configuration, "
                + "id: uuid::Uuid, parent: Option<uuid::Uuid>)"), api);
        assertFalse(api.contains(": &str") || api.contains("Option<&str>"), api);
    }

    @Test
    void itIsSentWithDisplay() throws IOException {
        String api = api(out);
        assertTrue(api.contains("id=p_path_id, ids=p_path_ids.iter().map(|p| crate::apis::urlencode(p.to_string()))"
                + ".collect::<Vec<String>>().join(\",\")"), api);
        assertTrue(api.contains("req_builder.query(&[(\"owner\", &p_query_owner.to_string())]);"), api);
        assertTrue(api.contains("req_builder.query(&[(\"parent\", &param_value.to_string())]);"), api);
        assertTrue(api.contains("req_builder.header(\"X-Request-Id\", p_header_x_request_id.to_string());"), api);
        assertTrue(api.contains("req_builder.header(\"X-Span-Ids\", param_value.iter().map(|p| p.to_string())"
                + ".collect::<Vec<String>>().join(\",\").to_string());"), api);
        for (Path output : List.of(out, helper)) {
            String form = api(output);
            assertTrue(form.contains("let param_value = &p_form_id;\n"
                    + "        multipart_form_params.push((\"id\", param_value.to_string()));"), form);
            assertTrue(form.contains("multipart_form = multipart_form.text(\"id\", p_form_id.to_string());"), form);
            assertTrue(form.contains("multipart_form = multipart_form.text(\"parent\", param_value.to_string());"),
                    form);
        }
    }

    @Test
    void theCrateDependsOnUuidOnlyWhenTheApiUsesIt() throws IOException {
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        assertTrue(cargo.contains("uuid = { version = \"^1.8\", features = [\"serde\", \"v4\"] }"), cargo);
        String without = Files.readString(noUuids.resolve("Cargo.toml"));
        assertFalse(without.contains("\nuuid = "), without);
        // only a success response, or only an array parameter, has one
        for (Path output : List.of(responseOnly, arrayParameterOnly)) {
            String only = Files.readString(output.resolve("Cargo.toml"));
            assertTrue(only.contains("\nuuid = "), only);
        }
        assertTrue(api(responseOnly).contains("Result<Vec<uuid::Uuid>, Error<ListIdsError>>"), api(responseOnly));
    }

    /** chrono alike: a date-time only in an error response makes the crate depend on it. */
    @Test
    void theCrateDependsOnChronoWhenOnlyAResponseUsesIt() throws IOException {
        String cargo = Files.readString(dateTimeErrorOnly.resolve("Cargo.toml"));
        assertTrue(cargo.contains("\nchrono = "), cargo);
        assertTrue(api(dateTimeErrorOnly).contains("chrono::DateTime<chrono::FixedOffset>"), api(dateTimeErrorOnly));
    }

    /**
     * Runs src/test/rust/uuid_params.rs on the generated crate, and checks the crates with the helper, with a uuid only
     * in a response and with a date-time only in an error response compile. Needs cargo; without it there is nothing
     * to run.
     */
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
        Files.copy(Path.of("src/test/rust/uuid_params.rs"), out.resolve("tests/uuid_params.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "uuid_params");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);

        for (Path crate : List.of(helper, responseOnly, dateTimeErrorOnly)) {
            String check = cargo(crate, 900, "check");
            assertNotNull(check, "cargo did not finish");
            assertTrue(check.contains("Finished") && !check.contains("error:") && !check.contains("error["), check);
        }
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
