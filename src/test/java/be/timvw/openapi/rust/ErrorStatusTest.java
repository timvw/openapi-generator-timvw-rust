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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error enum's variant comes from the status (decision 5, {@link ErrorStatus}), on
 * src/test/resources/error_status.yaml. What it decodes at run time is in src/test/rust/error_status.rs, which
 * {@link #whatItDecodesAtRunTime} runs with cargo against a local server.
 */
class ErrorStatusTest {

    private static final String SPEC = "src/test/resources/error_status.yaml";

    @TempDir
    static Path output;

    /** The same spec with {@code responseFunctions}: every operation also has its {@code _response} twin. */
    @TempDir
    static Path responseFunctions;

    /** The same spec with {@code supportMultipleResponses}, whose successes are a {@code ResponseContent} too. */
    @TempDir
    static Path multiple;

    @BeforeAll
    static void generate() {
        generate(output, false);
        generate(multiple, true);
        generate(responseFunctions, Map.of(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true"));
    }

    private static void generate(Path dir, boolean supportMultipleResponses) {
        generate(dir, supportMultipleResponses
                ? Map.of(RustCodegenBase.SUPPORT_MULTIPLE_RESPONSES, "true") : Map.of());
    }

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "error_status_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    /** With supportMultipleResponses a success is a ResponseContent with the response's headers; the crate builds. */
    @Test
    void aSuccessOfSupportMultipleResponsesHasTheHeaders() throws IOException, InterruptedException {
        String api = Files.readString(multiple.resolve("src/apis/default_api.rs"));
        assertContains(api, """
                        let headers = resp.headers().clone();
                        let content = resp.text().await?;
                        let entity: Option<GetThingSuccess> = serde_json::from_str(&content).ok();
                        Ok(ResponseContent { status, content, entity, headers })
                """);
        Assumptions.assumeTrue(cargo(multiple, 30, "--version") != null, "no cargo");
        String build = cargo(multiple, 900, "build");
        assertNotNull(build, "cargo did not finish");
        assertTrue(build.contains("Finished") && !build.contains("error:") && !build.contains("error["), build);
    }

    private static String api() throws IOException {
        return Files.readString(output.resolve("src/apis/default_api.rs"));
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected\n" + needle + "\nin\n" + haystack);
    }

    @Test
    void theStatusPicksTheVariantExactCodesBeforeRangesThenDefault() throws IOException {
        assertContains(api(), """
                        let entity: Option<GetThingError> = match status.as_u16() {
                            400 => serde_json::from_str(&content).ok().map(GetThingError::Status400),
                            404 => serde_json::from_str(&content).ok().map(GetThingError::Status404),
                            409 => Some(GetThingError::Status409()),
                            code if (400..500).contains(&code) => serde_json::from_str(&content).ok().map(GetThingError::Status4XX),
                            _ => serde_json::from_str(&content).ok().map(GetThingError::DefaultResponse),
                        }
                        .or_else(|| serde_json::from_str(&content).ok().map(GetThingError::UnknownValue));
                """);
    }

    @Test
    void withoutDefaultAnUnlistedStatusIsUnknownValue() throws IOException {
        assertContains(api(), """
                        let entity: Option<GetPlainError> = match status.as_u16() {
                            400 => serde_json::from_str(&content).ok().map(GetPlainError::Status400),
                            code if (500..600).contains(&code) => serde_json::from_str(&content).ok().map(GetPlainError::Status5XX),
                            _ => None,
                        }
                        .or_else(|| serde_json::from_str(&content).ok().map(GetPlainError::UnknownValue));
                """);
        assertContains(api(), "        let entity: Option<GetBareError> = "
                + "serde_json::from_str(&content).ok().map(GetBareError::UnknownValue);\n");
    }

    @Test
    void aTextErrorHoldsTheBodyAsItIs() throws IOException {
        assertContains(api(), """
                        let entity: Option<GetTextError> = match status.as_u16() {
                            400 => Some(GetTextError::Status400(content.clone())),
                            _ => Some(GetTextError::DefaultResponse(content.clone())),
                        }
                """);
    }

    /**
     * The {@code _response} twin of responseFunctions has the same error branch: the status picks the variant and a
     * text error holds the body. The crate passes clippy with no lint allowed.
     */
    @Test
    void theResponseFunctionTwinPicksTheVariantTheSameWay() throws IOException, InterruptedException {
        String api = Files.readString(responseFunctions.resolve("src/apis/default_api.rs"));
        int twin = api.indexOf("pub async fn get_text_response(");
        assertTrue(twin >= 0, api);
        assertContains(api.substring(twin), """
                        let entity: Option<GetTextError> = match status.as_u16() {
                            400 => Some(GetTextError::Status400(content.clone())),
                            _ => Some(GetTextError::DefaultResponse(content.clone())),
                        }
                """);
        assertContains(api.substring(api.indexOf("pub async fn get_thing_response(")),
                "404 => serde_json::from_str(&content).ok().map(GetThingError::Status404),");
        Assumptions.assumeTrue(cargo(responseFunctions, 30, "--version") != null, "no cargo");
        String clippy = cargo(responseFunctions, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"),
                clippy);
    }

    @Test
    void theErrorEnumKeepsItsShape() throws IOException {
        assertContains(api(), "#[serde(untagged)]\npub enum GetThingError {\n    Status400(models::Problem),\n"
                + "    Status404(models::Problem),\n    Status409(),\n    Status4XX(models::Problem),\n"
                + "    DefaultResponse(models::Fallback),\n    UnknownValue(serde_json::Value),\n}");
    }

    /** Runs src/test/rust/error_status.rs on the generated crate. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDecodesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(output, 30, "--version") != null, "no cargo");
        Path manifest = output.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(output.resolve("tests"));
        Files.copy(Path.of("src/test/rust/error_status.rs"), output.resolve("tests/error_status.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String result = cargo(output, 900, "test", "--test", "error_status");
        assertNotNull(result, "cargo did not finish");
        assertTrue(result.contains("test result: ok.") && !result.contains(" 0 passed"), result);
        // A range is a guard, so an exact code before its range is no overlap for clippy. No lint is allowed: a
        // crate generated without options passes clippy.
        String clippy = cargo(output, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        Assumptions.assumeTrue(clippy == null || !clippy.contains("no such command"), "no clippy");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"),
                clippy);
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
        String result = Files.readString(log);
        Files.delete(log);
        return result;
    }
}
