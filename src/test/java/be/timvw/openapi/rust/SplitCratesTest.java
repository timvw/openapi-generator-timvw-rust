package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code splitCrates}: the crate becomes a workspace of a facade, {@code -common}, a crate per crate of the groups and
 * {@code -untagged}. The spec has models shared by several groups (Pet, Problem), used by one group (User, Contact, a
 * discriminated union, under {@code /admin}), used by none (Archive, which uses User), and an untagged webhook that
 * {@code default_api} re-exports; {@code default_api} has operations of three crates.
 */
class SplitCratesTest {

    private static final String SPEC = "src/test/resources/split_crates.yaml";
    private static final String GROUPS = """
            admin: { paths: [/admin], crate: org }
            animals: { tags: [Pets] }
            store: { crate: org }
            """;

    @TempDir
    Path out;

    private void generate(Path dir, String groups, Map<String, Object> extra) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("api-features.yaml");
        Files.writeString(file, groups);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "split_fixture");
        props.put("packageVersion", "1.2.3");
        props.put(TimvwRustCodegen.SPLIT_CRATES, "true");
        props.put(TimvwRustCodegen.API_FEATURES_FILE, file.toString());
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        props.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        props.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        props.put(TimvwRustCodegen.WEBHOOK_MODULE, "Hooks");
        props.put(TimvwRustCodegen.WEBHOOK_MODULE_REEXPORT, "true");
        // "-" removes an option
        extra.forEach((k, v) -> {
            if ("-".equals(v)) {
                props.remove(k);
            } else {
                props.put(k, v);
            }
        });
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private void generate() throws IOException {
        generate(out, GROUPS, Map.of());
    }

    private String read(String relative) throws IOException {
        return Files.readString(out.resolve(relative));
    }

    @Test
    void theCrates() throws IOException {
        generate();
        String cargo = read("Cargo.toml");
        assertTrue(cargo.contains("[workspace]\nmembers = [\n    \"crates/common\",\n    \"crates/animals\",\n"
                + "    \"crates/default-api\",\n    \"crates/hooks\",\n    \"crates/org\",\n    \"crates/untagged\",\n]"),
                cargo);
        assertTrue(cargo.contains("split_fixture-common = { path = \"crates/common\", version = \"=1.2.3\", "
                + "default-features = false }"), cargo);
        assertTrue(cargo.contains("[workspace.package]\nversion = \"1.2.3\"\n"), cargo);
        assertTrue(cargo.contains("version.workspace = true"), cargo);
        // the facade's features are the single crate's, enabling the crates' features
        assertTrue(cargo.contains("admin = [\"admin-types\", \"_api\", \"split_fixture-org/admin\"]\n"
                + "admin-types = [\"dep:split_fixture-org\", \"split_fixture-org/admin-types\", "
                + "\"split_fixture-common/admin-types\"]\n"), cargo);
        assertTrue(cargo.contains("_api = [\"split_fixture-common/_api\"]"), cargo);
        assertTrue(cargo.contains("    \"dep:split_fixture-untagged\",\n    \"split_fixture-untagged/types\",\n"), cargo);
        assertTrue(cargo.contains("rustls = [\"split_fixture-common/rustls\"]"), cargo);
        assertFalse(cargo.contains("bon"), "the facade has no code of its own");
        assertFalse(read("src/lib.rs").contains("extern crate"));

        // where the models are
        assertTrue(Files.exists(out.resolve("crates/common/src/models/pet.rs")), "pets and store use it");
        assertTrue(Files.exists(out.resolve("crates/common/src/models/problem.rs")), "every group uses it");
        assertTrue(Files.exists(out.resolve("crates/org/src/models/user.rs")), "admin only");
        assertTrue(Files.exists(out.resolve("crates/org/src/models/order.rs")), "store only");
        assertTrue(Files.exists(out.resolve("crates/animals/src/models/pet_event.rs")));
        assertTrue(Files.exists(out.resolve("crates/untagged/src/models/archive.rs")));
        assertTrue(Files.exists(out.resolve("crates/hooks/src/models/adoption.rs")));
        assertFalse(Files.exists(out.resolve("src/models/pet.rs")));
        assertTrue(Files.exists(out.resolve("crates/common/src/models/union_dispatch.rs")));
        assertTrue(Files.exists(out.resolve("crates/common/src/apis/configuration.rs")));

        // default_api: a part per crate; the facade combines them
        String admin = read("crates/org/src/apis/default_api.rs");
        assertTrue(admin.contains("pub async fn list_users("), admin);
        assertFalse(admin.contains("pub async fn health("), admin);
        assertFalse(admin.contains(CrateSplit.MARKER), admin);
        assertTrue(read("crates/default-api/src/apis/default_api.rs").contains("pub async fn health("));
        assertTrue(read("crates/hooks/src/apis/default_api.rs").contains("pub use super::hooks_api::*;"));
        String facadeApis = read("src/apis/mod.rs");
        assertTrue(facadeApis.contains("pub mod default_api {\n"
                + "    #[cfg(feature = \"default-api\")]\n    pub use split_fixture_default_api::apis::default_api::*;\n"),
                facadeApis);
        assertTrue(facadeApis.contains("#[cfg(any(feature = \"admin\", feature = \"store\"))]\n"
                + "pub use split_fixture_org::apis::*;\n"), facadeApis);
        String facadeModels = read("src/models/mod.rs");
        assertTrue(facadeModels.contains("pub use split_fixture_common::models::*;\n"), facadeModels);
        assertTrue(facadeModels.contains("#[cfg(feature = \"types\")]\npub use split_fixture_untagged::models::*;\n"),
                facadeModels);

        // a crate re-exports common's modules, not inlined in its docs
        String orgModels = read("crates/org/src/models/mod.rs");
        assertTrue(orgModels.startsWith("// ") && orgModels.contains(
                "#[doc(no_inline)]\npub use split_fixture_common::models::*;\n"), orgModels);
        assertTrue(orgModels.contains("#[cfg(feature = \"admin-types\")]\npub mod user;\n"), orgModels);
        assertTrue(read("crates/untagged/src/models/mod.rs").contains(
                "#[doc(no_inline)]\npub use split_fixture_org::models::*;\n"));
        String orgCargo = read("crates/org/Cargo.toml");
        assertTrue(orgCargo.contains("admin-types = [") && orgCargo.contains("\"split_fixture-common/admin-types\"]"),
                orgCargo);
        assertTrue(orgCargo.contains("split_fixture-common = { workspace = true }"), orgCargo);
        assertTrue(read("crates/org/README.md").contains("An internal part of [`split_fixture`]"));

        // the helpers other crates call are public, and hidden
        String apis = read("crates/common/src/apis/mod.rs");
        assertTrue(apis.contains("#[doc(hidden)]\npub mod multipart_helper;"), apis);
        assertTrue(apis.contains("#[doc(hidden)]\npub enum ContentType {"), apis);
        assertFalse(apis.contains("pub mod default_api;"), apis);
        assertTrue(read("crates/common/src/models/mod.rs").contains("#[doc(hidden)]\npub mod union_dispatch;"));
        assertFalse(read("crates/common/src/models/union_dispatch.rs").contains("pub(crate)"));
        assertFalse(read("crates/common/src/models/mod.rs").contains("pub mod user;"));
    }

    @Test
    void theOptionsAreChecked() {
        assertFails("a", "common: { tags: [Pets] }\n", Map.of(), "the name of the shared crate");
        assertFails("b", "pets: { crate: x }\nnothing: { crate: x }\n", Map.of(), "'nothing' only names a crate");
        assertFails("c", "a: { tags: [Pets] }\nb: { paths: [/pets] }\n", Map.of(), "which are in the crates [a, b]");
        assertFails("d", GROUPS, Map.of(TimvwRustCodegen.TARGETS, "server",
                TimvwRustCodegen.MULTIPART_HELPER, "-", TimvwRustCodegen.TYPED_EVENT_STREAMS, "-",
                TimvwRustCodegen.RESPONSE_FUNCTIONS, "-", TimvwRustCodegen.CONTENT_TYPE_METHODS, "-",
                TimvwRustCodegen.WEBHOOK_MODULE, "-", TimvwRustCodegen.WEBHOOK_MODULE_REEXPORT, "-"), "splitCrates splits a client");
        assertFails("e", "pets: { tags: [Pets], crate: Pets }\n", Map.of(), "pets.crate: expected a crate name");
        assertFails("f", GROUPS, Map.of(TimvwRustCodegen.API_FEATURES, "false",
                TimvwRustCodegen.API_FEATURES_FILE, "-"), "splitCrates needs apiFeatures");
    }

    private void assertFails(String dir, String groups, Map<String, Object> extra, String message) {
        Throwable e = assertThrows(RuntimeException.class, () -> generate(out.resolve(dir), groups, extra));
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains(message), dir + ": " + messages);
    }

    @Test
    void referencesOutsideComments() {
        assertEquals(Set.of("User", "pet", "Role"), CrateSplit.references("""
                use crate::models;
                /// [`models::Doc`] is documented
                pub users: Vec<models::User>, // models::Comment
                #[serde(with = "models::pet")]
                use crate::models::{Role, unix_time as _};
                """.replace("unix_time as _", "Role")));
    }

    @Test
    void partsOfAModule() {
        Map<String, String> parts = CrateSplit.partition("""
                use super::Error;

                %sa
                pub fn one() {}

                %sb
                pub fn two() {}
                %sa
                pub fn three() {}
                """.formatted(CrateSplit.MARKER, CrateSplit.MARKER, CrateSplit.MARKER), "x");
        assertEquals(Map.of("a", "use super::Error;\n\npub fn one() {}\n\npub fn three() {}\n",
                "b", "use super::Error;\n\npub fn two() {}\n"), parts);
    }

    /**
     * The workspace builds with each feature alone and with all of them, without warnings, and code using the
     * facade's paths compiles. Needs cargo.
     */
    @Test
    void theWorkspaceBuilds() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate();
        Files.createDirectories(out.resolve("tests"));
        Files.writeString(out.resolve("tests/paths.rs"), """
                use split_fixture::apis::configuration::Configuration;
                use split_fixture::apis::{default_api, pets_api, store_api, Error, ResponseContent};
                use split_fixture::models::{self, Archive, Contact, Pet, Problem, Role, User};

                #[test]
                fn the_facade_has_every_path() {
                    let _ = Configuration::new();
                    let _ = default_api::list_users;
                    let _ = default_api::health;
                    let _ = default_api::pet_adopted;
                    let _ = pets_api::list_pets;
                    let _ = store_api::get_order;
                    let _: Option<Error<()>> = None;
                    let _: Option<ResponseContent<()>> = None;
                    let user: User = serde_json::from_str(
                        r#"{"name":"a","roles":["admin"],"contact":{"type":"email","address":"x@y"}}"#).unwrap();
                    assert!(matches!(user.contact, Some(Contact::Email(_))));
                    assert_eq!("member".parse::<Role>().unwrap(), Role::Member);
                    assert!("nobody".parse::<Role>().is_err());
                    let _: Option<Archive> = None;
                    let _: Option<Pet> = None;
                    let _: Option<Problem> = None;
                    assert!(models::validation::Validate::validate(&user).is_ok());
                    assert_eq!(std::any::type_name::<User>(), "split_fixture_org::models::user::User");
                }
                """);
        Files.writeString(out.resolve("Cargo.toml"), read("Cargo.toml").replace("[workspace]",
                "[dev-dependencies]\nserde_json = \"1\"\n\n[workspace]"));
        List<String> sets = List.of("", "animals-types", "rustls,animals", "rustls,admin", "store-types",
                "rustls,default-api", "rustls,hooks", "types", "rustls,full");
        for (String features : sets) {
            List<String> args = new ArrayList<>(List.of("check", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            String output = cargo(out, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished"), features + ":\n" + output);
            assertFalse(output.contains("warning"), features + ":\n" + output);
        }
        String output = cargo(out, 900, "clippy", "--workspace", "--all-features", "--all-targets", "--", "-D",
                "warnings");
        assertNotNull(output);
        assertTrue(output.contains("Finished"), output);
        output = cargo(out, 900, "test", "--workspace", "--all-features");
        assertNotNull(output);
        assertTrue(output.contains("test result: ok") && !output.contains("FAILED"), output);
        output = cargo(out, 900, "doc", "--workspace", "--no-deps", "--all-features");
        assertNotNull(output);
        assertTrue(output.contains("Finished") || output.contains("Generated"), output);
        // the facade's README has a bare URL, as the single crate's has; the crates have no warning
        assertFalse(output.contains("--> crates/") || output.contains("-common` (lib doc) generated"), output);
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
