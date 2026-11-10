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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A list item or map value that can be null is an {@code Option} at any depth: {@code nullable: true} (3.0),
 * {@code type: [T, 'null']} and a {@code oneOf}/{@code anyOf} with a {@code null} branch (3.1), and a reference to a
 * nullable schema (directly or through a single-entry {@code allOf}). A parameter's and a form field's items stay
 * plain, and so does a free-form item ({@code serde_json::Value} holds null).
 */
class NullableItemsTest {

    private static final String MAP = "std::collections::HashMap<String, ";

    @TempDir
    static Path v30;

    @TempDir
    static Path v31;

    @BeforeAll
    static void generate() {
        generate("src/test/resources/nullable_items.yaml", v30);
        generate("src/test/resources/nullable_items_31.yaml", v31);
    }

    private static void generate(String spec, Path output) {
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(Map.of("packageName", "nullable_items_fixture"))
                .toClientOptInput()).generate();
    }

    @Test
    void nullableItemsAndValuesAreOptionsAtAnyDepth() throws IOException {
        String holder = Files.readString(v30.resolve("src/models/holder.rs"));
        assertContains(holder, "    pub map: Option<" + MAP + "Option<String>>>,\n");
        assertContains(holder, "    pub list: Option<Vec<Option<i64>>>,\n");
        // a reference to a nullable schema, as a field of one is an Option
        assertContains(holder, "    pub things: Option<Vec<Option<models::Thing>>>,\n");
        assertContains(holder, "    pub grid: Option<" + MAP + "Option<Vec<Option<String>>>>>,\n");
        assertContains(holder, "    pub rows: Option<Vec<Vec<Option<f64>>>>,\n");
        // an inline enum
        assertContains(holder, "    pub modes: Option<Vec<Option<Modes>>>,\n");
        assertContains(holder, "    pub mode_grid: Option<" + MAP + "Vec<Option<ModeGrid>>>>,\n");
        assertContains(holder, "    pub plain: Option<Vec<String>>,\n");
        // serde_json::Value holds null itself
        assertContains(holder, "    pub free: Option<" + MAP + "serde_json::Value>>,\n");
        assertContains(holder, "    pub free_list: Option<Vec<serde_json::Value>>,\n");
        // a single-entry allOf wrapping a nullable reference
        assertContains(holder, "    pub wrapped: Option<Vec<Option<models::Thing>>>,\n");
        // a union's array variants are named after their items, nullable ones too
        String uni = Files.readString(v30.resolve("src/models/uni.rs"));
        assertContains(uni, "    ArrayOfStrings(Vec<String>),\n");
        assertContains(uni, "    ArrayOfNullableStrings(Vec<Option<String>>),\n");
        // validate() looks inside the Some items only
        assertContains(holder, "                if let Some(value2) = item1 {\n"
                + "                    crate::models::validation::minimum(*value2, 2, ");
        assertContains(Files.readString(v30.resolve("docs/Holder.md")),
                "**modes** | Option<**Vec<Option<Modes>>**>");
    }

    @Test
    void theNullableFormsOf31AreOptionsToo() throws IOException {
        String holder = Files.readString(v31.resolve("src/models/holder.rs"));
        assertContains(holder, "    pub map: Option<" + MAP + "Option<String>>>,\n");
        assertContains(holder, "    pub list: Option<Vec<Option<i64>>>,\n");
        assertContains(holder, "    pub things: Option<Vec<Option<models::Thing>>>,\n");
        assertContains(holder, "    pub grid: Option<" + MAP + "Vec<Option<String>>>>,\n");
        assertContains(holder, "    pub modes: Option<Vec<Option<Modes>>>,\n");
        // anyOf [{}, null] is a serde_json::Value, which holds null itself
        assertContains(holder, "    pub free: Option<Vec<serde_json::Value>>,\n");
    }

    @Test
    void responsesAndBodiesFollowTheRuleParametersAndFormFieldsDoNot() throws IOException {
        String api = Files.readString(v30.resolve("src/apis/default_api.rs"));
        // nor in a urlencoded or multipart field, a file part included
        assertContains(api, "post_form(configuration: &configuration::Configuration, tags: Option<Vec<String>>, "
                + "files: Option<Vec<std::path::PathBuf>>)");
        assertContains(api, "put_form(configuration: &configuration::Configuration, tags: Option<Vec<String>>)");
        // null has no spelling in a query string
        assertContains(api, "ids: Option<Vec<String>>) -> Result<" + MAP + "Option<i64>>, Error<GetHolderError>>");
        assertContains(api, "thing: &[Option<models::Thing>]) -> Result<models::Holder, Error<PostHolderError>>");
    }

    @Test
    void nullItemsDecodeSerializeAndValidate() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(v30, 30, "--version") != null, "no cargo");
        for (Path crate : List.of(v30, v31)) {
            String clippy = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            assertNotNull(clippy, "clippy did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                    && !clippy.contains("warning:"), clippy);
        }
        Path manifest = v30.resolve("Cargo.toml");
        String toml = Files.readString(manifest);
        if (!toml.contains("[dev-dependencies]")) {
            Files.writeString(manifest, toml + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        for (Map.Entry<Path, String> crate : Map.of(v30, "nullable_items", v31, "nullable_items_31").entrySet()) {
            Files.createDirectories(crate.getKey().resolve("tests"));
            Files.copy(Path.of("src/test/rust/" + crate.getValue() + ".rs"),
                    crate.getKey().resolve("tests/" + crate.getValue() + ".rs"), StandardCopyOption.REPLACE_EXISTING);
            String test = cargo(crate.getKey(), 900, "test", "--test", crate.getValue());
            assertNotNull(test, "cargo did not finish");
            assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        }
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
        String result = Files.readString(log);
        Files.delete(log);
        return result;
    }
}
