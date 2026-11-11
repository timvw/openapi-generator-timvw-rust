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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code modelNamePrefix}, {@code modelNameSuffix} and {@code modelNameMappings} name the type, while the file keeps
 * the schema's name ({@code ApiPet} in {@code api_pet.rs}, {@code Animal} in {@code pet.rs}). The steps that read the
 * written models find each by its type's file: 0.22.0 looked for {@code api_api_pet.rs} and {@code animal.rs} and
 * failed generation whenever a struct was a JSON parameter or body.
 */
class ModelNamesTest {

    @TempDir
    static Path prefix;

    @TempDir
    static Path suffix;

    @TempDir
    static Path mapped;

    @TempDir
    static Path all;

    @BeforeAll
    static void generate() {
        generate(prefix, "Api", null, Map.of());
        generate(suffix, null, "Dto", Map.of());
        generate(mapped, null, null, Map.of("Filter", "SearchFilter", "Pet", "Animal"));
        generate(all, "Api", "Dto", Map.of("Filter", "SearchFilter"));
    }

    private static void generate(Path output, String namePrefix, String nameSuffix, Map<String, String> mappings) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/model_names.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(new java.util.HashMap<>(Map.of("packageName", "model_names",
                        TimvwRustCodegen.DEPRECATED_ATTRIBUTES, "true")));
        if (namePrefix != null) {
            configurator.setModelNamePrefix(namePrefix);
        }
        if (nameSuffix != null) {
            configurator.setModelNameSuffix(nameSuffix);
        }
        mappings.forEach(configurator::addModelNameMapping);
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static String read(Path output, String file) throws IOException {
        return Files.readString(output.resolve(file));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aPrefixNamesTheTypeAndItsFileOnce() throws IOException {
        assertContains(read(prefix, "src/models/api_filter.rs"), "pub struct ApiFilter {");
        assertContains(read(prefix, "src/models/mod.rs"), "pub use self::api_filter::ApiFilter;");
        String api = read(prefix, "src/apis/default_api.rs");
        assertContains(api, "req_builder.query(&[(\"filter\", &serde_json::to_string(param_value)?)]);");
        assertContains(api, "req_builder.header(\"X-Filter\", serde_json::to_string(&param_value)?);");
        assertContains(api, ".json(p_body_api_pet)");
    }

    @Test
    void aSuffixNamesTheTypeAndItsFileOnce() throws IOException {
        assertContains(read(suffix, "src/models/filter_dto.rs"), "pub struct FilterDto {");
        assertContains(read(suffix, "src/apis/default_api.rs"),
                "req_builder.query(&[(\"filter\", &serde_json::to_string(param_value)?)]);");
    }

    @Test
    void aMappedNameKeepsTheSchemasFile() throws IOException {
        assertContains(read(mapped, "src/models/filter.rs"), "pub struct SearchFilter {");
        assertContains(read(mapped, "src/models/pet.rs"), "pub struct Animal {");
        assertContains(read(mapped, "src/models/mod.rs"), "pub use self::filter::SearchFilter;");
        assertContains(read(mapped, "src/apis/default_api.rs"), "x_filter: Option<models::SearchFilter>");
        assertContains(read(all, "src/models/api_filter_dto.rs"), "pub struct SearchFilter {");
        assertContains(read(all, "src/models/api_pet_dto.rs"), "pub struct ApiPetDto {");
    }

    @Test
    void deprecatedModelsAreMarkedUnderTheirFinalName() throws IOException {
        // 0.22.0 looked for the file of the type name and silently skipped the attribute
        assertContains(read(prefix, "src/models/api_old.rs"), "#[deprecated]\n");
        assertContains(read(suffix, "src/models/old_dto.rs"), "#[deprecated]\n");
        assertContains(read(mapped, "src/models/pet.rs"), "#[deprecated]\n");
        assertContains(read(all, "src/models/api_pet_dto.rs"), "#[deprecated]\n");
    }

    @Test
    void enumsGetFromStrUnderTheirFinalName() throws IOException {
        assertContains(read(all, "src/models/api_kind_dto.rs"), "impl ::std::str::FromStr for ApiKindDto {");
    }

    @Test
    void unionVariantsAndHoistedEnumsGetThePrefixAndSuffixOnce() throws IOException {
        String loose = read(all, "src/models/api_loose_dto.rs");
        assertContains(loose, "    TextVariant(models::ApiLooseTextVariantEnumDto),\n");
        assertContains(loose, "    Text(String),\n");
        assertContains(loose, "    SearchFilter(models::SearchFilter),\n");
        assertTrue(Files.exists(all.resolve("src/models/api_loose_text_variant_enum_dto.rs")));
        String shape = read(all, "src/models/api_shape_dto.rs");
        assertContains(shape, "    Circle(models::ApiCircleDto),\n");
        assertFalse(shape.contains("ApiApi") || loose.contains("ApiApi"), loose + shape);
    }

    @Test
    void theSafetyNetComparesTheFinalTypeNameWithItsFile(@TempDir Path root) throws IOException {
        Path models = Files.createDirectories(root.resolve("src/models"));
        Map<String, String> files = Map.of("ApiFilterDto", "api_filter_dto", "SearchFilter", "filter");
        Files.writeString(models.resolve("api_filter_dto.rs"), "pub struct ApiFilterDto {\n}\n");
        Files.writeString(models.resolve("filter.rs"), "pub struct SearchFilter {\n}\n");
        TimvwRustCodegen.checkStructJsonModels(models, Set.of("ApiFilterDto", "SearchFilter"), files);

        // a union under a prefix still fails
        Files.writeString(models.resolve("api_filter_dto.rs"),
                "#[serde(untagged)]\npub enum ApiFilterDto {\n    Text(String),\n}\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TimvwRustCodegen.checkStructJsonModels(models, Set.of("ApiFilterDto"), files));
        assertContains(e.getMessage(), "[ApiFilterDto]");
        // and so does a type without a written model
        e = assertThrows(IllegalStateException.class,
                () -> TimvwRustCodegen.checkStructJsonModels(models, Set.of("Missing"), files));
        assertContains(e.getMessage(), "[Missing]");
    }

    @Test
    void theFilesAreTheModelsClassFilenames() {
        org.openapitools.codegen.CodegenModel cm = new org.openapitools.codegen.CodegenModel();
        cm.classname = "ApiPetDto";
        cm.classFilename = "api_pet_dto";
        org.openapitools.codegen.model.ModelMap mm = new org.openapitools.codegen.model.ModelMap();
        mm.setModel(cm);
        org.openapitools.codegen.model.ModelsMap models = new org.openapitools.codegen.model.ModelsMap();
        models.setModels(List.of(mm));
        assertEquals(Map.of("ApiPetDto", "api_pet_dto"), TimvwRustCodegen.modelFiles(Map.of("Pet", models)));
    }

    /** The crates with a prefix and suffix, and with mappings, build without clippy warnings. Needs cargo. */
    @Test
    void theCratesCompile() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(all, 30, "--version") != null, "no cargo");
        for (Path crate : List.of(all, mapped)) {
            String output = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings");
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("error:") && !output.contains("error["),
                    output);
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
