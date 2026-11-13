package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Yaml;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * {@code apiFeatures}: a Cargo feature per tag, or per group of {@code apiFeaturesFile}, with a {@code -types} twin;
 * the models of a group are what its operations reach through {@code $ref}. The spec has two tags, untagged
 * operations under {@code /admin} and {@code /health}, models reached through a discriminated {@code oneOf},
 * {@code allOf}, arrays and maps, a model every tag needs and one none does.
 */
class ApiFeaturesTest {

    private static final String SPEC = "src/test/resources/api_features.yaml";

    @TempDir
    Path out;

    private void generate(Path dir, Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "api_features_fixture");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        props.putAll(extra);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private Path groups(String yaml) throws IOException {
        Path file = out.resolve("apiFeatures.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private Map<String, Object> withFile(String yaml) throws IOException {
        return Map.of(TimvwRustCodegen.API_FEATURES_FILE, groups(yaml).toString());
    }

    private static String read(Path dir, String relative) throws IOException {
        return Files.readString(dir.resolve(relative));
    }

    /** The cfg line before {@code pub mod <module>;} in a mod.rs, or {@code null} when there is none. */
    private static String cfgOf(String mod, String module) {
        List<String> lines = mod.lines().toList();
        int at = lines.indexOf("pub mod " + module + ";");
        assertTrue(at >= 0, module + " is not declared:\n" + mod);
        String before = at > 0 ? lines.get(at - 1) : "";
        return before.startsWith("#[cfg(") ? before : null;
    }

    @Test
    void offByDefault() throws IOException {
        generate(out, Map.of());
        assertFalse(read(out, "Cargo.toml").contains("full"));
        assertFalse(read(out, "Cargo.toml").contains("optional"));
        for (String file : List.of("src/lib.rs", "src/apis/mod.rs", "src/models/mod.rs", "src/apis/default_api.rs")) {
            assertFalse(read(out, file).contains("#[cfg("), file);
        }
    }

    @Test
    void oneFeaturePerTag() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.API_FEATURES, "true"));
        String cargo = read(out, "Cargo.toml");
        assertTrue(cargo.contains("timvw-rust-runtime = { version = \"^" + TimvwRustCodegen.DEFAULT_RUNTIME_VERSION + "\", default-features = false"), cargo);
        assertTrue(cargo.contains(", optional = true }"), cargo);
        assertTrue(cargo.contains("default = [\"native-tls\", \"retry\", \"full\"]\n"
                + "native-tls = [\"timvw-rust-runtime?/native-tls\"]\n"), cargo);
        assertTrue(cargo.contains("full = [\n    \"default-api\",\n    \"pets\",\n    \"store\",\n    \"types\",\n]\n"
                + "types = [\n    \"default-api-types\",\n    \"pets-types\",\n    \"store-types\",\n]\n"
                + "_api = [\"dep:timvw-rust-runtime\""), cargo);
        assertTrue(cargo.contains("# untagged operations\ndefault-api = [\"default-api-types\", \"_api\"]\n"
                + "default-api-types = []\n# tag Pets\npets = [\"pets-types\", \"_api\"]\npets-types = []\n"), cargo);

        assertTrue(read(out, "src/lib.rs").contains("#[cfg(feature = \"_api\")]\npub mod apis;\npub mod models;"));
        String apis = read(out, "src/apis/mod.rs");
        assertEquals("#[cfg(feature = \"pets\")]", cfgOf(apis, "pets_api"));
        assertEquals("#[cfg(feature = \"default-api\")]", cfgOf(apis, "default_api"));
        assertTrue(apis.contains("#[allow(dead_code)] // the API features enabled may not use every helper\n"
                + "pub(crate) mod multipart_helper;"), apis);
        assertFalse(read(out, "src/apis/default_api.rs").contains("#[cfg("), "one feature: the module's cfg says it");

        String models = read(out, "src/models/mod.rs");
        assertEquals("#[cfg(feature = \"store-types\")]", cfgOf(models, "order"));
        // Pet (a discriminated oneOf), its variants (allOf of PetBase) and Toy: listed by Pets, reached by an Order
        for (String m : List.of("pet", "cat", "dog", "pet_base", "toy")) {
            assertEquals("#[cfg(any(feature = \"pets-types\", feature = \"store-types\"))]", cfgOf(models, m), m);
        }
        // through a map value and its array items, through a query parameter
        for (String m : List.of("user", "role", "audit_kind", "health")) {
            assertEquals("#[cfg(feature = \"default-api-types\")]", cfgOf(models, m), m);
        }
        assertEquals(null, cfgOf(models, "problem"), "every feature needs it");
        assertEquals("#[cfg(feature = \"types\")]", cfgOf(models, "unused"), "no feature needs it");
        assertTrue(models.contains("#[cfg(feature = \"types\")]\npub mod unused;\n#[cfg(feature = \"types\")]\n"
                + "pub use self::unused::Unused;\n"), models);
        assertTrue(models.contains("#[allow(dead_code)]\npub(crate) mod union_dispatch;"), models);
        assertTrue(models.contains("#[allow(dead_code)]\npub mod parse_enum_error;"), models);

        assertTrue(read(out, "README.md").contains("`pets` | tag Pets\n"));
    }

    /** A group taking part of a module by path prefix gates that module's functions one by one. */
    @Test
    void groupsFromAFile() throws IOException {
        generate(out, withFile("admin: { paths: [/admin] }\nanimals:\n  tags: [Pets]\n"));
        String cargo = read(out, "Cargo.toml");
        assertTrue(cargo.contains("full = [\n    \"admin\",\n    \"animals\",\n    \"default-api\",\n    \"store\",\n"),
                cargo);
        assertTrue(cargo.contains("# paths under `/admin`\nadmin = [\"admin-types\", \"_api\"]\n"), cargo);
        assertTrue(cargo.contains("# untagged operations (operations no group selects)\ndefault-api = "), cargo);
        assertTrue(cargo.contains("# tag Store\nstore = "), cargo);
        assertFalse(cargo.contains("pets"), "the group took the tag's feature");

        String apis = read(out, "src/apis/mod.rs");
        assertEquals("#[cfg(feature = \"animals\")]", cfgOf(apis, "pets_api"));
        assertEquals("#[cfg(any(feature = \"admin\", feature = \"default-api\"))]", cfgOf(apis, "default_api"));
        String module = read(out, "src/apis/default_api.rs");
        assertTrue(module.contains("#[cfg(feature = \"admin\")]\n/// struct for typed errors of method [`list_users`]"),
                module);
        assertTrue(module.contains("#[cfg(feature = \"default-api\")]\n/// struct for typed errors of method [`health`]"),
                module);
        assertTrue(module.contains("#[cfg(feature = \"admin\")]\n#[bon::builder]\npub async fn get_audit("), module);
        assertTrue(module.contains("#[cfg(feature = \"default-api\")]\n#[bon::builder]\npub async fn health("), module);

        String models = read(out, "src/models/mod.rs");
        assertEquals("#[cfg(any(feature = \"animals-types\", feature = \"default-api-types\", feature = \"store-types\"))]",
                cfgOf(models, "problem"), "admin does not need it");
        assertEquals("#[cfg(feature = \"admin-types\")]", cfgOf(models, "user"));
        assertEquals("#[cfg(feature = \"default-api-types\")]", cfgOf(models, "health"));
    }

    /** An operation two groups select is in both: an operation of a tag group and of a path group. */
    @Test
    void anOperationInTwoGroupsIsInBoth() throws IOException {
        generate(out, withFile("orders: { paths: [/store] }\nshop: { tags: [Store, Pets] }\n"));
        String apis = read(out, "src/apis/mod.rs");
        assertEquals("#[cfg(any(feature = \"orders\", feature = \"shop\"))]", cfgOf(apis, "store_api"));
        assertEquals("#[cfg(feature = \"shop\")]", cfgOf(apis, "pets_api"));
        assertFalse(read(out, "src/apis/store_api.rs").contains("#[cfg("));
        assertEquals("#[cfg(any(feature = \"orders-types\", feature = \"shop-types\"))]",
                cfgOf(read(out, "src/models/mod.rs"), "order"));
    }

    @Test
    void theFileIsChecked() throws IOException {
        assertFails("'Pet' selects tag", "pets: { tags: [Pet] }\n", "no operation has");
        assertFails("no operation", "admin: { paths: [/administration] }\n", "under which there is no operation");
        // /admin takes part of default_api; the rest keeps the feature default-api, which the group may not be named
        assertFails("default-api", "default-api: { paths: [/admin] }\n", "two features would be named 'default-api'");
        // a group named after a tag it does not cover
        assertFails("store", "store: { paths: [/admin] }\n", "two features would be named 'store'");
        assertFails("full", "full: { tags: [Pets] }\n", "already has");
        assertFails("name", "Pets: { tags: [Pets] }\n", "is not a feature name");
        assertFails("empty", "pets: {}\n", "selects nothing");
        assertFails("key", "pets: { tag: [Pets] }\n", "unknown key 'tag'");
        assertFails("slash", "admin: { paths: [admin] }\n", "does not start with '/'");
        assertFails("-types", "pets: { tags: [Pets] }\npets-types: { tags: [Store] }\n",
                "two features would be named 'pets-types'");
        // a group may take the name of the tag it covers
        generate(out.resolve("ok"), withFile("store: { tags: [Store] }\n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out.resolve("off"),
                Map.of(TimvwRustCodegen.API_FEATURES, "false", TimvwRustCodegen.API_FEATURES_FILE,
                        groups("pets: { tags: [Pets] }").toString())));
        assertTrue(e.getMessage().contains("apiFeatures is false"), e.getMessage());
    }

    private void assertFails(String what, String yaml, String message) throws IOException {
        Map<String, Object> props = withFile(yaml);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> generate(out.resolve("failing"), props), what);
        assertTrue(e.getMessage().contains(message), what + ": " + e.getMessage());
    }

    @Test
    void defaultFeatureNames() {
        assertEquals("vector-stores", ApiFeatures.defaultFeature("vector_stores_api"));
        assertEquals("default-api", ApiFeatures.defaultFeature("default_api"));
        assertEquals("types-api", ApiFeatures.defaultFeature("types_api"));
        assertEquals("fine-tuning", ApiFeatures.defaultFeature("fine_tuning_api"));
    }

    @Test
    void pathPrefixesEndAtASegment() {
        assertTrue(ApiFeatures.underPrefix("/organization", "/organization"));
        assertTrue(ApiFeatures.underPrefix("/organization/projects/{id}", "/organization"));
        assertTrue(ApiFeatures.underPrefix("/organization/projects", "/organization/"));
        assertFalse(ApiFeatures.underPrefix("/organizations", "/organization"));
    }

    @Test
    void referencesIncludeDiscriminatorMappings() throws IOException {
        JsonNode schema = Yaml.mapper().readTree("""
                oneOf:
                  - $ref: '#/components/schemas/Cat'
                discriminator:
                  propertyName: kind
                  mapping:
                    cat: '#/components/schemas/Cat'
                    dog: Dog
                properties:
                  owner:
                    items:
                      $ref: '#/components/responses/Owner'
                """);
        Set<String> refs = new HashSet<>();
        ApiFeatures.Refs.collect(schema, refs);
        assertEquals(Set.of("#/components/schemas/Cat", "#/components/schemas/Dog", "#/components/responses/Owner"),
                refs);
    }

    @Test
    void theFileFormat() throws IOException {
        List<ApiFeatures.Group> groups = ApiFeatures.parse(Yaml.mapper().readTree("""
                administration:
                  tags: [Audit Logs, Projects]
                  paths: [/organization]
                audio: { tags: [Audio] }
                """), "test");
        assertEquals(List.of(new ApiFeatures.Group("administration", List.of("Audit Logs", "Projects"),
                List.of("/organization")), new ApiFeatures.Group("audio", List.of("Audio"), List.of())), groups);
    }

    /**
     * Every feature compiles alone, a {@code -types} feature without the HTTP client. Needs cargo; without it there is
     * nothing to build.
     */
    @Test
    void eachFeatureBuildsAlone() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate(out, Map.of(TimvwRustCodegen.API_FEATURES_FILE,
                groups("admin: { paths: [/admin] }\nanimals: { tags: [Pets] }\n").toString()));
        List<String> sets = List.of("animals-types", "rustls,animals", "rustls,admin", "rustls,default-api",
                "store-types", "types", "rustls,full", "");
        for (String features : sets) {
            List<String> args = new ArrayList<>(List.of("check", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            String output = cargo(out, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished"), features + ":\n" + output);
            assertFalse(output.contains("warning:"), features + ":\n" + output);
        }
        String tree = cargo(out, 300, "tree", "--no-default-features", "--features", "animals-types", "-e", "normal");
        assertNotNull(tree);
        assertTrue(tree.contains("serde"), tree);
        assertFalse(tree.contains("timvw-rust-runtime") || tree.contains("reqwest") || tree.contains("tokio"), tree);
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
