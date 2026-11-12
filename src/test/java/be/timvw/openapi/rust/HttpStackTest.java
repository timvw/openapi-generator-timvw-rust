package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code httpStack}: {@code tower} (the default) sends through {@code timvw_rust_runtime::Client};
 * {@code reqwest-middleware} renders what 0.24 rendered. The spec has event streams, a streamed binary response,
 * multipart bodies with files and JSON bodies, so every template path that names the HTTP library is rendered; both
 * stacks build with cargo, with and without {@code multipartHelper}.
 */
class HttpStackTest {

    private static final String SPEC = "src/test/resources/event_streams.yaml";

    @TempDir
    Path out;

    private void generate(Path dir, Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "http_stack_fixture");
        props.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        props.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        props.putAll(extra);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path dir, String relative) throws IOException {
        return Files.readString(dir.resolve(relative));
    }

    private static String allApis(Path dir) throws IOException {
        StringBuilder text = new StringBuilder();
        try (var files = Files.list(dir.resolve("src/apis"))) {
            for (Path file : files.sorted().toList()) {
                text.append(Files.readString(file));
            }
        }
        return text.toString();
    }

    @Test
    void towerIsTheDefaultAndSendsThroughTheRuntime() throws IOException {
        generate(out, Map.of());
        String cargo = read(out, "Cargo.toml");
        assertTrue(cargo.contains("timvw-rust-runtime = { version = \"^" + TimvwRustCodegen.DEFAULT_RUNTIME_VERSION
                + "\", default-features = false"), cargo);
        assertTrue(cargo.contains("default = [\"native-tls\", \"retry\"]\n"
                + "native-tls = [\"timvw-rust-runtime/native-tls\"]\n"
                + "rustls = [\"timvw-rust-runtime/rustls\"]\n"
                + "retry = [\"timvw-rust-runtime/retry\"]\n"
                + "tracing = [\"timvw-rust-runtime/tracing\"]"), cargo);
        assertFalse(cargo.contains("reqwest"), cargo);

        String configuration = read(out, "src/apis/configuration.rs");
        assertTrue(configuration.contains("pub use timvw_rust_runtime::Client;"), configuration);
        assertTrue(configuration.contains("pub client: timvw_rust_runtime::Client,"), configuration);
        assertTrue(configuration.contains("client: timvw_rust_runtime::Client::default(),"), configuration);

        String mod = read(out, "src/apis/mod.rs");
        assertTrue(mod.contains("    Http(timvw_rust_runtime::Error),\n    Serde(serde_json::Error),"), mod);
        assertTrue(mod.contains("impl <T> From<timvw_rust_runtime::Error> for Error<T> {"), mod);
        assertFalse(mod.contains("Reqwest"), mod);

        String apis = allApis(out);
        assertTrue(apis.contains("configuration.client.request(timvw_rust_runtime::Method::POST, &uri_str);"), apis);
        assertTrue(apis.contains("let resp = configuration.client.execute(req).await?;"), apis);
        assertTrue(apis.contains("timvw_rust_runtime::multipart::Form::new()"), apis);
        assertTrue(apis.contains("pub fn from_response(response: timvw_rust_runtime::Response) -> Self {"), apis);
        assertFalse(apis.contains("reqwest::") || apis.contains("reqwest_middleware"), apis);
        assertFalse(read(out, "src/lib.rs").contains("reqwest"));
    }

    @Test
    void reqwestMiddlewareRendersWhat024Rendered() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.HTTP_STACK, TimvwRustCodegen.HTTP_STACK_REQWEST_MIDDLEWARE));
        String cargo = read(out, "Cargo.toml");
        assertTrue(cargo.contains("reqwest-middleware = { version = \"^0.5\""), cargo);
        assertTrue(cargo.contains("default = [\"native-tls\"]\nnative-tls = [\"reqwest/native-tls\"]\n"
                + "rustls = [\"reqwest/rustls\"]"), cargo);
        assertFalse(cargo.contains("timvw-rust-runtime"), cargo);

        String configuration = read(out, "src/apis/configuration.rs");
        assertTrue(configuration.contains("pub use reqwest_middleware::ClientWithMiddleware;"), configuration);
        assertTrue(configuration.contains("pub client: reqwest_middleware::ClientWithMiddleware,"), configuration);

        String mod = read(out, "src/apis/mod.rs");
        assertTrue(mod.contains("    Reqwest(reqwest::Error),\n    ReqwestMiddleware(reqwest_middleware::Error),\n"
                + "    Serde(serde_json::Error),"), mod);
        assertTrue(mod.contains("    pub status: reqwest::StatusCode,"), mod);
        assertTrue(read(out, "src/lib.rs").contains("extern crate reqwest;"));

        String apis = allApis(out);
        assertTrue(apis.contains("configuration.client.request(reqwest::Method::POST, &uri_str);"), apis);
        assertTrue(apis.contains("reqwest::multipart::Form::new()"), apis);
        assertFalse(apis.contains("timvw_rust_runtime"), apis);
    }

    @Test
    void theRuntimeVersionAndPathAreOptions() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.RUNTIME_VERSION, "0.3.1", TimvwRustCodegen.RUNTIME_PATH, "../runtime"));
        assertTrue(read(out, "Cargo.toml").contains(
                "timvw-rust-runtime = { version = \"^0.3.1\", default-features = false, path = \"../runtime\" }"));
    }

    @Test
    void anUnknownStackFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> generate(out, Map.of(TimvwRustCodegen.HTTP_STACK, "hyper")));
        assertTrue(e.getMessage().contains("'tower' or 'reqwest-middleware'"), e.getMessage());
    }

    /** The default runtime version is the major and minor of the runtime crate in this repository. */
    @Test
    void theDefaultRuntimeVersionIsTheRuntimeCrates() throws IOException {
        Matcher version = Pattern.compile("(?m)^version = \"(\\d+)\\.(\\d+)\\.\\d+\"")
                .matcher(Files.readString(Path.of("runtime/Cargo.toml")));
        assertTrue(version.find());
        String expected = "0".equals(version.group(1)) ? "0." + version.group(2) : version.group(1);
        assertEquals(expected, TimvwRustCodegen.DEFAULT_RUNTIME_VERSION);
    }

    /** Both stacks build, with and without multipartHelper. Needs cargo; without it there is nothing to build. */
    @ParameterizedTest
    @CsvSource({"tower,true", "tower,false", "reqwest-middleware,true", "reqwest-middleware,false"})
    void bothStacksBuild(String stack, boolean multipartHelper) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate(out, Map.of(TimvwRustCodegen.HTTP_STACK, stack,
                TimvwRustCodegen.MULTIPART_HELPER, String.valueOf(multipartHelper)));
        String output = cargo(out, 900, "build");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("Finished"), output);
        assertFalse(output.contains("warning: unused"), output);
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
