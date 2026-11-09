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
 * Parameter serialization on src/test/resources/param_serialization.yaml: path and header arrays of any item type,
 * deepObject models, delimited and nested query arrays, urlencoded form arrays and nullable fields, an array-of-uuid
 * body's name, and a model cycle through an inline union. What goes on the wire is in
 * src/test/rust/param_serialization.rs, which {@link #whatItDoesAtRunTime} runs with cargo against a local server.
 */
class ParamSerializationTest {

    private static final String SPEC = "src/test/resources/param_serialization.yaml";

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "param_serialization_fixture");
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

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void pathArraysEncodeEachItem() throws IOException {
        assertContains(api(), "names=p_path_names.iter().map(crate::apis::urlencode).collect::<Vec<String>>().join(\",\")");
        assertContains(api(), "nums=p_path_nums.iter().map(|p| crate::apis::urlencode(p.to_string()))"
                + ".collect::<Vec<String>>().join(\",\")");
        assertContains(Files.readString(out.resolve("src/apis/mod.rs")),
                "byte_serialize(s.as_ref().as_bytes()).collect::<String>().replace('+', \"%20\")");
    }

    @Test
    void aHeaderArrayOfIntegersJoinsTheirText() throws IOException {
        assertContains(api(), "req_builder.header(\"X-Nums\", param_value.iter().map(|p| p.to_string())"
                + ".collect::<Vec<String>>().join(\",\").to_string());");
    }

    @Test
    void aDeclaredDeepObjectModelIsSentAsDeepObject() throws IOException {
        assertContains(api(), "filter: Option<models::Filter>");
        assertContains(api(), "req_builder = req_builder.query(&crate::apis::parse_deep_object(\"filter\", "
                + "&serde_json::to_value(param_value)?));");
    }

    @Test
    void delimitedAndNestedQueryArrays() throws IOException {
        String api = api();
        assertContains(api, "req_builder = req_builder.query(&[(\"pipes\", items.join(\"|\"))]);");
        assertContains(api, "req_builder = req_builder.query(&[(\"spaces\", items.join(\" \"))]);");
        assertContains(api, "let items: Vec<String> = param_value.iter().map(serde_json::to_string)"
                + ".collect::<Result<_, _>>()?;\n        for item in items {\n"
                + "            req_builder = req_builder.query(&[(\"matrix\", item)]);");
        assertFalse(api.contains("into_iter()"), api);
    }

    @Test
    void urlencodedFormArraysRepeatAndANullableFieldIsSentEmpty() throws IOException {
        String api = api();
        assertContains(api, "let mut multipart_form_params: Vec<(&str, String)> = Vec::new();");
        assertContains(api, "for item in items {\n            multipart_form_params.push((\"tags\", item));");
        assertContains(api, "multipart_form_params.push((\"labels\", items.join(\",\")));");
        assertContains(api, "else {\n        multipart_form_params.push((\"note\", String::new()));");
    }

    @Test
    void anArrayOfUuidsBodyIsNamedAfterThem() throws IOException {
        assertContains(api(), "pub async fn post_ids(configuration: &configuration::Configuration, uuid: &[uuid::Uuid])");
    }

    @Test
    void aCycleThroughAnInlineUnionIsBoxed() throws IOException {
        assertContains(Files.readString(out.resolve("src/models/top.rs")), "pub child: Option<Box<models::TopChild>>,");
        assertContains(Files.readString(out.resolve("src/models/top_child.rs")), "Leaf(Box<models::Leaf>),");
        assertContains(Files.readString(out.resolve("src/models/leaf.rs")), "pub top: Box<models::Top>,");
    }

    /**
     * Runs src/test/rust/param_serialization.rs on the generated crate, and clippy with {@code -D warnings} on it
     * ({@code needless_return} in {@code ContentType::from} is fixed elsewhere). Needs cargo; without it there is
     * nothing to run.
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
        Files.copy(Path.of("src/test/rust/param_serialization.rs"), out.resolve("tests/param_serialization.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "param_serialization");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
        String clippy = cargo(out, 900, "clippy", "--all-targets", "--", "-D", "warnings", "-A", "clippy::needless_return");
        assertNotNull(clippy, "cargo did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("warning:"), clippy);
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
