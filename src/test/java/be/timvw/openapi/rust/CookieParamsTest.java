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
 * Cookie parameters (decision 3) on src/test/resources/cookie_params.yaml: the ones that are set go out in one
 * {@code Cookie} header, together with an api key sent as a cookie, each value percent-encoded where it is not a
 * cookie-octet. What goes on the wire is in src/test/rust/cookie_params.rs, which {@link #whatItDoesAtRunTime} runs
 * with cargo against a local server.
 */
class CookieParamsTest {

    private static final String SPEC = "src/test/resources/cookie_params.yaml";

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "cookie_params_fixture");
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

    /** The text of one generated function, from its signature to the next function or the end of the file. */
    private static String function(String name) throws IOException {
        String text = api();
        int start = text.indexOf("pub async fn " + name + "(");
        assertTrue(start >= 0, name + " not in:\n" + text);
        int end = text.indexOf("pub async fn ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    @Test
    void theArgumentsKeepTheirTypes() throws IOException {
        assertTrue(api().contains("pub async fn get_items(configuration: &configuration::Configuration, session: &str, "
                + "theme: Option<&str>, at: Option<chrono::DateTime<chrono::FixedOffset>>, id: Option<uuid::Uuid>, "
                + "tags: Option<Vec<String>>, ids: Option<Vec<String>>)"), api());
    }

    @Test
    void eachCookieIsAPairOfOneHeader() throws IOException {
        String get = function("get_items");
        for (String push : List.of(
                "cookies.push(format!(\"session={}\", crate::apis::cookie_encode(param_value)));",
                "cookies.push(format!(\"at={}\", crate::apis::cookie_encode(param_value.to_rfc3339())));",
                "cookies.push(format!(\"id={}\", crate::apis::cookie_encode(param_value.to_string())));",
                "cookies.push(format!(\"tags={}\", crate::apis::cookie_encode(item)));",
                "cookies.push(format!(\"ids={}\", items.iter().map(crate::apis::cookie_encode)"
                        + ".collect::<Vec<String>>().join(\",\")));",
                "cookies.push(format!(\"api_token={}\", crate::apis::cookie_encode(value)));",
                "req_builder = req_builder.header(timvw_rust_runtime::header::COOKIE, cookies.join(\"; \"));")) {
            assertTrue(get.contains(push), push + " not in:\n" + get);
        }
        assertTrue(function("get_keyed").contains("api_token={}"), function("get_keyed"));
        assertFalse(function("get_plain").contains("COOKIE"), function("get_plain"));
        assertTrue(Files.readString(out.resolve("src/apis/mod.rs")).contains("pub fn cookie_encode<T: AsRef<str>>("));
    }

    /** A map has no {@code Display}: as a cookie and as a header it is sent as its JSON. */
    @Test
    void aMapIsSentAsItsJson() throws IOException {
        String get = function("get_maps");
        assertTrue(get.contains("cookies.push(format!(\"prefs={}\", "
                + "crate::apis::cookie_encode(serde_json::to_string(param_value)?)));"), get);
        assertTrue(get.contains("req_builder.header(\"X-Labels\", serde_json::to_string(&param_value)?);"), get);
    }

    /** A crate whose operations send no cookie has no {@code cookie_encode}. */
    @Test
    void withoutCookiesThereIsNoEncoder(@TempDir Path none) throws IOException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "no_cookies_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/uuid_params.yaml").toAbsolutePath().toString())
                .setOutputDir(none.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
        assertFalse(Files.readString(none.resolve("src/apis/mod.rs")).contains("cookie_encode"));
        assertFalse(Files.readString(none.resolve("src/apis/default_api.rs")).contains("COOKIE"));
    }

    /** Runs src/test/rust/cookie_params.rs on the generated crate. Needs cargo; without it there is nothing to run. */
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
        Files.copy(Path.of("src/test/rust/cookie_params.rs"), out.resolve("tests/cookie_params.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "cookie_params");
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
