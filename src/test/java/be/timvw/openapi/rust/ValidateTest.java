package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decisions 9 and 8: every model implements the generated {@code models::validation::Validate} trait, which checks the
 * validation keywords of its schema (see {@link ValidateRules}). What it does at run time is in
 * src/test/rust/validate.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class ValidateTest {

    private static final String SPEC = "src/test/resources/validate.yaml";

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @TempDir
    static Path v31;

    @BeforeAll
    static void generate() {
        generate(SPEC, on, Map.of());
        generate(SPEC, off, Map.of(TimvwRustCodegen.VALIDATE_METHODS, "false"));
        generate("src/test/resources/validate31.yaml", v31, Map.of());
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "validate_fixture");
        props.put(TimvwRustCodegen.BON_BUILDERS, "false");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void everyModelImplementsTheTrait() throws IOException {
        for (String model : List.of("request", "message", "point", "content", "shape", "circle", "square", "node",
                "holder", "validate", "validation_error")) {
            assertContains(read(on, model), "impl crate::models::validation::Validate for ");
        }
        String point = read(on, "point");
        assertContains(point, "impl crate::models::validation::Validate for Point {\n"
                + "    fn validate(&self) -> Result<(), crate::models::validation::ValidationError> {\n"
                + "        Ok(())\n    }\n}");
        assertContains(Files.readString(on.resolve("src/models/mod.rs")), "pub mod validation;");
        assertFalse(Files.readString(on.resolve("src/lib.rs")).contains("validation"));
        String validation = Files.readString(on.resolve("src/models/validation.rs"));
        assertContains(validation, "pub trait Validate {\n");
        assertContains(validation, "pub struct ValidationError {");
        assertContains(validation, "impl std::error::Error for ValidationError {}");
    }

    @Test
    void stringsNumbersListsAndMapsAreChecked() throws IOException {
        String request = read(on, "request");
        assertContains(request, "crate::models::validation::items(self.messages.len(), Some(1), Some(3))"
                + ".map_err(|e| e.at(\"messages\"))?;");
        assertContains(request, "crate::models::validation::exclusive_maximum(self.count, 100).map_err(|e| e.at(\"count\"))?;");
        assertContains(request, "if let Some(Some(value0)) = &self.temperature {\n"
                + "            crate::models::validation::minimum(*value0, 0.0).map_err(|e| e.at(\"temperature\"))?;");
        assertContains(request, "crate::models::validation::multiple_of(*value0, 0.1)");
        assertContains(request, "crate::models::validation::unique_items(value0).map_err(|e| e.at(\"tags\"))?;");
        assertContains(request, "crate::models::validation::length(item1, None, Some(5))"
                + ".map_err(|e| e.at_index(index1).at(\"tags\"))?;");
        assertContains(request, "crate::models::validation::properties(value0.len(), Some(1), Some(2))");
        assertContains(request, ".map_err(|e| e.at_key(key1).at(\"metadata\"))?;");
        assertContains(read(on, "message"), "crate::models::validation::length(&self.content, Some(1), Some(10))"
                + ".map_err(|e| e.at(\"content\"))?;");
    }

    @Test
    void nestedModelsAreChecked() throws IOException {
        String request = read(on, "request");
        // holder has no constraints of its own, but holds a message
        assertContains(request, "crate::models::validation::Validate::validate(&self.holder).map_err(|e| e.at(\"holder\"))?;");
        assertContains(read(on, "holder"), "crate::models::validation::Validate::validate(&self.inner)");
        // a model whose validate() cannot fail is not called
        assertFalse(read(on, "holder").contains("&self.plain"), read(on, "holder"));
        assertContains(read(on, "node"), "crate::models::validation::Validate::validate(&self.next)");
    }

    @Test
    void unionsCheckTheActiveVariant() throws IOException {
        String content = read(on, "content");
        assertContains(content, "match self {\n            Self::Text(value) => {\n"
                + "                crate::models::validation::length(value, None, Some(4))?;");
        assertContains(content, "Self::ArrayOfMessages(value) => {\n"
                + "                crate::models::validation::Validate::validate(value)?;");
        // only Circle has something to check, and Unknown catches the rest
        assertContains(read(on, "shape"), "if let Self::Circle(value) = self {");
    }

    @Test
    void whatHasNothingToCheckIsSkipped() throws IOException {
        String request = read(on, "request");
        assertFalse(request.contains("self.payload"), request);
        assertFalse(request.contains("self.points.len()"), request);
        assertFalse(request.contains("self.lookahead"), request);
        // bounds every value of the type meets
        assertFalse(request.contains("self.seed"), request);
        assertFalse(request.contains("self.small"), request);
        assertContains(request, "crate::models::validation::items(value0.len(), Some(1), None).map_err(|e| e.at(\"colors\"))?;");
    }

    @Test
    void aPatternIsMatchedWithARegexCompiledOnce() throws IOException {
        String message = read(on, "message");
        assertContains(message, "crate::models::validation::pattern(value0, &PATTERN_0, r\"^[a-z]+\\d*$\")");
        assertContains(message, "static PATTERN_0: std::sync::LazyLock<Option<regex::Regex>> = "
                + "std::sync::LazyLock::new(|| regex::Regex::new(r\"^[a-z]+\\d*$\").ok());");
        assertContains(Files.readString(on.resolve("Cargo.toml")), "regex = ");
        assertContains(Files.readString(on.resolve("src/models/validation.rs")), "pub fn pattern(");
    }

    @Test
    void withoutAPatternThereIsNoRegexDependency() throws IOException {
        assertFalse(Files.readString(v31.resolve("Cargo.toml")).contains("regex"));
        assertFalse(Files.readString(v31.resolve("src/models/validation.rs")).contains("regex::"));
    }

    @Test
    void openApi31ExclusiveBoundsAreNumbers() throws IOException {
        String range = read(v31, "range");
        assertContains(range, "crate::models::validation::exclusive_minimum(self.low, 0.0).map_err(|e| e.at(\"low\"))?;");
        assertContains(range, "crate::models::validation::exclusive_maximum(self.low, 10.0).map_err(|e| e.at(\"low\"))?;");
        assertContains(range, "crate::models::validation::exclusive_minimum(*value0, 1).map_err(|e| e.at(\"count\"))?;");
        assertContains(range, "if let Some(Some(value0)) = &self.label {\n"
                + "            crate::models::validation::length(value0, None, Some(3))");
    }

    @Test
    void constraintsAreDocumented() throws IOException {
        String request = read(on, "request");
        assertContains(request, "/// Validation: `minItems: 1`, `maxItems: 3`.\n    #[serde(rename = \"messages\")]");
        assertContains(request, "/// Validation: `minimum: 1`, `exclusiveMaximum: 100`, `multipleOf: 5`.");
        assertContains(request, "/// Validation: `uniqueItems: true`; each item: `maxLength: 5`.");
        assertContains(request, "/// Validation: `minProperties: 1`, `maxProperties: 2`; each value: `maxLength: 3`.");
        assertContains(request, "/// Validation: each item: `minItems: 1`; each item of an item: `minLength: 1`.");
        assertFalse(read(on, "holder").contains("/// Validation"), read(on, "holder"));
        assertContains(read(off, "request"), "/// Validation: `minItems: 1`, `maxItems: 3`.");
    }

    @Test
    void theOptionTurnsTheGenerationOff() throws IOException {
        assertFalse(Files.exists(off.resolve("src/models/validation.rs")));
        assertFalse(Files.readString(off.resolve("src/models/mod.rs")).contains("pub mod validation;"));
        assertFalse(Files.readString(off.resolve("Cargo.toml")).contains("regex"));
        try (Stream<Path> files = Files.walk(off.resolve("src"))) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String text = Files.readString(file);
                assertFalse(text.contains("models::validation"), file.toString());
                assertFalse(text.contains("PATTERN_"), file.toString());
                assertFalse(text.contains("regex::"), file.toString());
                assertFalse(text.matches("(?s).*impl [^\\n]*Validate for .*"), file.toString());
            }
        }
        // the generated code is the same as with the option on, without the trait
        assertTrue(Files.readString(on.resolve("src/models/request.rs")).contains("impl crate::models::validation::Validate for Request"));
    }

    @Test
    void aSchemaWithTheNameOfTheModuleFailsGenerationUnlessTheOptionIsOff(@TempDir Path output, @TempDir Path offOutput)
            throws IOException {
        String message = assertThrows(IllegalArgumentException.class,
                () -> generate("src/test/resources/validate_reserved.yaml", output, Map.of())).getMessage();
        assertContains(message, "the schema Validation would be the module models::validation");
        generate("src/test/resources/validate_reserved.yaml", offOutput,
                Map.of(TimvwRustCodegen.VALIDATE_METHODS, "false"));
        assertContains(read(offOutput, "validation"), "pub struct Validation {");
    }

    @Test
    void literals() {
        assertEquals("0.0", ValidateRules.floatLiteral(new BigDecimal("0"), false));
        assertEquals("2.0", ValidateRules.floatLiteral(new BigDecimal("2.00"), false));
        assertEquals("0.1", ValidateRules.floatLiteral(new BigDecimal("0.10"), true));
        assertEquals("100.0", ValidateRules.floatLiteral(new BigDecimal("1E+2"), false));
        assertEquals("1.0e39", ValidateRules.floatLiteral(new BigDecimal("1.0e39"), false));
        assertEquals(null, ValidateRules.floatLiteral(new BigDecimal("1.0e39"), true));
        assertEquals(null, ValidateRules.floatLiteral(new BigDecimal("1e400"), false));
        assertEquals("0.3", ValidateRules.floatLiteral(new BigDecimal("0.30000000000000000001"), false));
        assertEquals("r\"a\"", ValidateRules.rawString("a"));
        assertEquals("r#\"a\"b\"#", ValidateRules.rawString("a\"b"));
        assertEquals("\"a\\\"b\"", ValidateRules.quote("a\"b"));
    }

    /** Runs src/test/rust/validate.rs on the generated models. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime(@TempDir Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        Files.writeString(crate.resolve("Cargo.toml"), """
                [package]
                name = "validate_fixture"
                version = "0.0.0"
                edition = "2021"

                [dependencies]
                regex = "1"
                serde = { version = "1", features = ["derive"] }
                serde_json = "1"
                serde_repr = "0.1"
                serde_with = { version = "3", default-features = false, features = ["base64", "std", "macros"] }
                """);
        Files.createDirectories(crate.resolve("src/models"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("src/lib.rs"), "pub mod models;\n");
        try (Stream<Path> files = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, crate.resolve("src/models").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/rust/validate.rs"), crate.resolve("tests/validate.rs"));
        String output = cargo(crate, 600, "test", "--test", "validate");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
        // clippy denies a regex that does not compile (invalid_regex): every pattern the generator kept compiles
        String clippy = cargo(crate, 600, "clippy", "--all-targets", "--", "-D", "warnings");
        Assumptions.assumeTrue(clippy != null && !clippy.contains("no such command"), "no clippy");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error"), clippy);
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
