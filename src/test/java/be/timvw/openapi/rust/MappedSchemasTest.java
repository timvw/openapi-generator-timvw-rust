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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --schema-mappings Filter=crate::custom::Filter} uses a type of the crate's own in place of the schema: every
 * reference names the mapped path as it is, no model or doc is written for the schema, and a parameter of the type is
 * sent as a generated type of the schema's shape would be. Up to 0.23.0 every reference named {@code models::Filter},
 * which was not written, so the crate did not compile. Rendered from {@code src/test/resources/mapped_schemas.yaml};
 * {@link #whatItDoesAtRunTime} adds the hand-written module (src/test/rust/mapped_schemas_custom.rs) and runs
 * src/test/rust/mapped_schemas.rs against a local server.
 */
class MappedSchemasTest {

    private static final Map<String, String> MAPPINGS = Map.of(
            "Filter", "crate::custom::Filter",
            "Circle", "crate::custom::Circle",
            "Token", "crate::custom::Token");

    @TempDir
    static Path out;

    @TempDir
    static Path named;

    @BeforeAll
    static void generate() {
        generate(out, false);
        generate(named, true);
    }

    private static void generate(Path output, boolean names) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/mapped_schemas.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(new java.util.HashMap<>(Map.of("packageName", "mapped_schemas")));
        MAPPINGS.forEach(configurator::addSchemaMapping);
        if (names) {
            configurator.setModelNamePrefix("Api");
            configurator.addModelNameMapping("Filter", "SearchFilter");
        }
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static String read(Path output, String file) throws IOException {
        return Files.readString(output.resolve(file));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void everyReferenceIsTheMappedPath() throws IOException {
        String result = read(out, "src/models/result.rs");
        assertContains(result, "pub filter: crate::custom::Filter,");
        assertContains(result, "pub filters: Option<Vec<crate::custom::Filter>>,");
        assertContains(result, "pub by_name: Option<std::collections::HashMap<String, crate::custom::Filter>>,");
        assertContains(result, "pub next: Option<crate::custom::Token>,");
        assertContains(result, "pub mode: Option<models::Mode>,");
        assertContains(read(out, "src/models/choice.rs"), "    Filter(crate::custom::Filter),\n");
        assertContains(read(out, "src/models/shape.rs"), "    Circle(crate::custom::Circle),\n");
        String api = read(out, "src/apis/default_api.rs");
        assertContains(api, "pub async fn search(configuration: &configuration::Configuration, "
                + "filter2: &crate::custom::Filter, filter: Option<crate::custom::Filter>, "
                + "x_filter: Option<crate::custom::Filter>, filters: Option<Vec<crate::custom::Filter>>, "
                + "options: Option<&timvw_rust_runtime::RequestOptions>) "
                + "-> Result<crate::custom::Filter, Error<SearchError>>");
        assertContains(api, "pub async fn put_token(configuration: &configuration::Configuration, "
                + "body: &crate::custom::Token, tokens: Option<Vec<crate::custom::Token>>, "
                + "options: Option<&timvw_rust_runtime::RequestOptions>) "
                + "-> Result<crate::custom::Token, Error<PutTokenError>>");
        assertContains(api, "-> Result<Vec<crate::custom::Filter>, Error<ChooseError>>");
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".rs") || p.toString().endsWith(".md"))
                    .toList()) {
                String text = Files.readString(file);
                for (String schema : MAPPINGS.keySet()) {
                    assertFalse(text.contains("models::" + schema + ">") || text.contains("models::" + schema + ")")
                            || text.contains("models::" + schema + ",") || text.contains("models::" + schema + "*"),
                            file + " names models::" + schema + ":\n" + text);
                }
            }
        }
    }

    @Test
    void noModelOrDocIsWrittenForAMappedSchema() throws IOException {
        for (String file : List.of("src/models/filter.rs", "src/models/circle.rs", "src/models/token.rs",
                "docs/Filter.md", "docs/Circle.md", "docs/Token.md")) {
            assertFalse(Files.exists(out.resolve(file)), file);
        }
        String mod = read(out, "src/models/mod.rs");
        assertFalse(mod.contains("filter") || mod.contains("circle") || mod.contains("token"), mod);
        assertContains(mod, "pub use self::square::Square;");
        try (Stream<Path> files = Files.walk(out)) {
            for (Path md : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                String text = Files.readString(md);
                assertFalse(text.contains("Filter.md") || text.contains("Circle.md") || text.contains("Token.md"),
                        md + ":\n" + text);
            }
        }
        // the types are shown without a link
        assertContains(read(out, "docs/Result.md"), "**filter** | **crate::custom::Filter** |");
        assertContains(read(out, "docs/DefaultApi.md"), "**x_filter** | Option<**crate::custom::Filter**> |");
    }

    /**
     * A type in place of an object schema is sent as its JSON wherever a generated struct would be: a header, an array
     * query parameter's items. One in place of a string schema is sent with {@code Display}, and its body is not
     * {@code &str}.
     */
    @Test
    void parametersAreSentAsTheShapeOfTheMappedSchemaSays() throws IOException {
        String api = read(out, "src/apis/default_api.rs");
        assertContains(api, "req_builder.query(&[(\"filter\", &serde_json::to_string(param_value)?)]);");
        assertContains(api, "req_builder.header(\"X-Filter\", serde_json::to_string(&param_value)?);");
        assertContains(api, "let items: Vec<String> = param_value.iter().map(serde_json::to_string)"
                + ".collect::<Result<_, _>>()?;");
        assertContains(api, "token=crate::apis::urlencode(p_path_token.to_string())");
        assertContains(api, "req_builder.query(&[(\"after\", &param_value.to_string())]);");
        assertContains(api, "req_builder.header(\"X-Token\", param_value.to_string());");
        assertContains(api, "let items: Vec<String> = param_value.iter().map(|p| p.to_string()).collect();");
        assertContains(api, "req_builder = req_builder.json(p_body_body);");
    }

    /** A discriminated union selects the mapped variant by its tag value, as it would a generated struct. */
    @Test
    void aTaggedUnionDispatchesToTheMappedVariant() throws IOException {
        String shape = read(out, "src/models/shape.rs");
        assertContains(shape,
                "Tag::Value(\"circle\") => dispatch::decode(value, UNION, \"Circle\").map(Self::Circle),");
        assertContains(shape, "Tag::NotObject => Err(dispatch::not_an_object(&value, UNION, TAG)),");
    }

    /** The schema mapping wins over a model name prefix and a model name mapping of the same schema. */
    @Test
    void theMappedPathIsUsedAsItIsWithAPrefixOrNameMapping() throws IOException {
        assertContains(read(named, "src/models/api_result.rs"), "pub filter: crate::custom::Filter,");
        assertContains(read(named, "src/models/api_choice.rs"), "    Filter(crate::custom::Filter),\n");
        assertContains(read(named, "src/models/api_shape.rs"), "    Circle(crate::custom::Circle),\n");
        assertContains(read(named, "src/apis/default_api.rs"), "x_filter: Option<crate::custom::Filter>");
        assertFalse(Files.exists(named.resolve("src/models/filter.rs")));
        assertFalse(read(named, "src/models/mod.rs").contains("SearchFilter"));
    }

    /**
     * The crate, with src/test/rust/mapped_schemas_custom.rs as its module {@code custom}, builds without clippy
     * warnings and sends and reads the mapped types as src/test/rust/mapped_schemas.rs expects. Needs cargo.
     */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        for (Path crate : List.of(out, named)) {
            Files.copy(Path.of("src/test/rust/mapped_schemas_custom.rs"), crate.resolve("src/custom.rs"),
                    StandardCopyOption.REPLACE_EXISTING);
            String lib = Files.readString(crate.resolve("src/lib.rs"));
            if (!lib.contains("pub mod custom;")) {
                Files.writeString(crate.resolve("src/lib.rs"), lib + "pub mod custom;\n");
            }
        }
        // the custom module names models::Mode, which the prefix renames
        Files.writeString(named.resolve("src/custom.rs"),
                Files.readString(named.resolve("src/custom.rs")).replace("models::Mode", "models::ApiMode"));
        Path manifest = out.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/mapped_schemas.rs"), out.resolve("tests/mapped_schemas.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        for (Path crate : List.of(out, named)) {
            String clippy = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            assertNotNull(clippy, "cargo did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error["),
                    clippy);
        }
        String output = cargo(out, 900, "test", "--test", "mapped_schemas");
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
