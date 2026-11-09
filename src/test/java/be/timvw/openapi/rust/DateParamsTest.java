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
 * {@code date} and {@code date-time} path, query and header parameters (decision 11) on
 * src/test/resources/date_params.yaml: a {@code date-time} is sent in RFC 3339 ({@code to_rfc3339}), a {@code date}
 * as {@code YYYY-MM-DD}; form fields (urlencoded and multipart text parts) alike. What goes on the wire is in
 * src/test/rust/date_params.rs, which {@link #whatItDoesAtRunTime}
 * runs with cargo against a local server.
 */
class DateParamsTest {

    private static final String SPEC = "src/test/resources/date_params.yaml";

    @TempDir
    static Path chrono;

    @TempDir
    static Path strings;

    @TempDir
    static Path helper;

    @BeforeAll
    static void generate() {
        generate(chrono, true, false);
        generate(strings, false, false);
        generate(helper, true, true);
    }

    private static void generate(Path output, boolean useChrono, boolean multipartHelper) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "date_params_fixture");
        props.put(RustCodegenBase.USE_CHRONO, String.valueOf(useChrono));
        if (multipartHelper) {
            props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path output) throws IOException {
        return Files.readString(output.resolve("src/apis/default_api.rs"));
    }

    @Test
    void aDateTimeIsSentInRfc3339() throws IOException {
        String api = api(chrono);
        assertTrue(api.contains("at=crate::apis::urlencode(p_path_at.to_rfc3339())"), api);
        assertTrue(api.contains("ats=p_path_ats.iter().map(|p| crate::apis::urlencode(p.to_rfc3339()))"
                + ".collect::<Vec<String>>().join(\",\")"), api);
        assertTrue(api.contains("req_builder.query(&[(\"from\", &p_query_from.to_rfc3339())]);"), api);
        assertTrue(api.contains("req_builder.query(&[(\"until\", &param_value.to_rfc3339())]);"), api);
        assertTrue(api.contains("req_builder.query(&[(\"before\", &param_value.to_rfc3339())]);"), api);
        assertTrue(api.contains("(\"times\".to_owned(), p.to_rfc3339())"), api);
        assertTrue(api.contains("(\"timesCsv\", &param_value.into_iter().map(|p| p.to_rfc3339())"), api);
        assertTrue(api.contains("req_builder.header(\"X-At\", p_header_x_at.to_rfc3339());"), api);
        assertTrue(api.contains("req_builder.header(\"X-Until\", param_value.to_rfc3339());"), api);
        assertTrue(api.contains("req_builder.header(\"X-Times\", param_value.iter().map(|p| p.to_rfc3339())"
                + ".collect::<Vec<String>>().join(\",\").to_string());"), api);
    }

    @Test
    void aDateTimeFormFieldIsSentInRfc3339() throws IOException {
        for (Path output : List.of(chrono, helper)) {
            String api = api(output);
            assertTrue(api.contains("multipart_form_params.insert(\"at\", p_form_at.to_rfc3339());"), api);
            assertTrue(api.contains("multipart_form_params.insert(\"until\", param_value.to_rfc3339());"), api);
            assertTrue(api.contains("multipart_form_params.insert(\"times\", param_value.into_iter()"
                    + ".map(|p| p.to_rfc3339()).collect::<Vec<String>>().join(\",\").to_string());"), api);
            assertTrue(api.contains("multipart_form_params.insert(\"day\", p_form_day.to_string());"), api);
            assertTrue(api.contains("multipart_form = multipart_form.text(\"at\", p_form_at.to_rfc3339());"), api);
            assertTrue(api.contains("multipart_form = multipart_form.text(\"until\", param_value.to_rfc3339());"),
                    api);
            assertTrue(api.contains("multipart_form = multipart_form.text(\"day\", p_form_day.to_string());"), api);
        }
    }

    @Test
    void aDateIsSentWithDisplay() throws IOException {
        String api = api(chrono);
        assertTrue(api.contains("day=p_path_day,"), api);
        assertTrue(api.contains("days=p_path_days.iter().map(|p| p.to_string()).collect::<Vec<String>>().join(\",\")"),
                api);
        assertTrue(api.contains("req_builder.query(&[(\"onDay\", &p_query_on_day.to_string())]);"), api);
        assertTrue(api.contains("(\"dates\".to_owned(), p.to_string())"), api);
        assertTrue(api.contains("req_builder.header(\"X-Day\", param_value.to_string());"), api);
    }

    @Test
    void withoutChronoTheyAreStringsSentAsTheyAre() throws IOException {
        String api = api(strings);
        assertFalse(api.contains("to_rfc3339"), api);
        assertTrue(api.contains("at=p_path_at,"), api);
        assertTrue(api.contains("ats=p_path_ats.join(\",\")"), api);
        assertTrue(api.contains("req_builder.query(&[(\"from\", &p_query_from.to_string())]);"), api);
        assertTrue(api.contains("req_builder.header(\"X-At\", p_header_x_at.to_string());"), api);
        assertTrue(api.contains("multipart_form_params.insert(\"at\", p_form_at.to_string());"), api);
        assertTrue(api.contains("multipart_form = multipart_form.text(\"at\", p_form_at.to_string());"), api);
    }

    /**
     * Runs src/test/rust/date_params.rs on the crate with chrono, and checks the crate without it compiles. Needs
     * cargo; without it there is nothing to run.
     */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(chrono, 30, "--version") != null, "no cargo");
        Path manifest = chrono.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(chrono.resolve("tests"));
        Files.copy(Path.of("src/test/rust/date_params.rs"), chrono.resolve("tests/date_params.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(chrono, 900, "test", "--test", "date_params");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);

        String check = cargo(strings, 900, "check");
        assertNotNull(check, "cargo did not finish");
        assertTrue(check.contains("Finished") && !check.contains("error:") && !check.contains("error["), check);
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
