package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
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
 * What the server reads as the client writes it, on src/test/resources/server_wire.yaml: a path with a fixed query
 * ({@code /notes/{id}?beta=true}) next to the same path without one, deepObject query parameters, a query parameter of
 * a union, and multipart fields whose union has a file variant. src/test/rust/server_wire.rs runs the server generated
 * with {@code ["client", "server"]} and {@code multipartHelper} on a local port and calls it with the client of the
 * same crate; {@link #theClientCallsTheServer} runs it and clippy with cargo.
 */
class ServerWireTest {

    private static final String SPEC = "src/test/resources/server_wire.yaml";

    @TempDir
    Path out;

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "server_wire");
        props.putAll(options);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aFixedQueryIsRoutedOnItsPathAndPicksTheOperation() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = Files.readString(out.resolve("src/server.rs"));
        // the module does not route the operations that share a route; the router does, once
        assertFalse(server.contains("router.route(\"/notes/{p0}\", axum::routing::get(handle_"), server);
        assertFalse(server.contains("?beta=true\", axum"), server);
        assertContains(server, "    let router = router.route(\"/notes/{p0}\", axum::routing::get(route_1::<I>));\n");
        assertContains(server, "    let router = router.route(\"/notes/{p0}\", axum::routing::delete(handle_delete_note::<I>));\n");
        assertContains(server, """
                    {
                        if fixed_query(&query, &[("beta", "true")]) {
                            return axum::handler::Handler::call(beta_api::handle_beta_get_note::<I>, request, api).await;
                        }
                    }
                    {
                        if fixed_query(&query, &[]) {
                            return axum::handler::Handler::call(notes_api::handle_get_note::<I>, request, api).await;
                        }
                    }
                """);
        assertContains(server, "error_response(http::StatusCode::NOT_FOUND, error)");
    }

    @Test
    void aDeepObjectIsReadIntoItsTypeAndAUnionFromItsText() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertContains(server, "        pub metadata: Option<std::collections::HashMap<String, String>>,\n");
        assertContains(server, "        pub created: Option<models::Range>,\n");
        assertContains(server, "            metadata: super::query_deep(query, \"metadata\")?,\n");
        assertContains(server, "fn query_deep<T: serde::de::DeserializeOwned>(");
        assertContains(server, "impl<'de> serde::Deserializer<'de> for DeepValue {");
        assertContains(server, "            order: super::query_value(query, \"order\", |v| serde_json::from_value::"
                + "<models::ListNotesOrderParameter>(serde_json::Value::String(v.to_owned())).ok().or_else(|| "
                + "serde_json::from_str::<models::ListNotesOrderParameter>(v).ok()))?,\n");
    }

    @Test
    void aFileUnionIsAFileOrAValue() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = Files.readString(out.resolve("src/server.rs"));
        assertContains(server, """
                    pub enum AttachSource {
                        Reference(models::Reference),
                        Text(super::FilePart),
                    }
                """);
        assertContains(server, """
                    pub enum AttachFiles {
                        ArrayOfStrings(Vec<super::FilePart>),
                        Text(super::FilePart),
                    }
                """);
        assertContains(server, "            .filter(|p| p.file_name.is_some() && (p.name == \"files\" || p.name == "
                + "\"files[]\"))\n");
        assertContains(server, "            if files.len() > 1 || files.iter().any(|p| p.name != \"files\") {\n");
        assertContains(server, "            source: super::required(\"body\", \"source\", form_attach_source(parts)?)?,\n");
        assertContains(server, "fn part_value<T: serde::de::DeserializeOwned>(part: &FormPart) -> Option<T> {");
        assertFalse(server.contains("Not served"), server);
    }

    /**
     * Generates {@code ["client", "server"]} with {@code multipartHelper}, copies src/test/rust/server_wire.rs into its
     * tests/, runs it and clippy, and builds the server on its own. Needs cargo; without it there is nothing to run.
     */
    @Test
    void theClientCallsTheServer() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate(out, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.MULTIPART_HELPER, "true"));
        Path manifest = out.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1", "json"] }
                tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "time"] }
                """);
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/server_wire.rs"), out.resolve("tests/server_wire.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(out, 900, "test", "--test", "server_wire");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        String clippy = cargo(out, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "cargo did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);
        String server = cargo(out, 900, "clippy", "--no-default-features", "--features", "server", "--", "-D",
                "warnings");
        assertNotNull(server, "cargo did not finish");
        assertTrue(server.contains("Finished") && !server.contains("warning:"), server);
    }

    /**
     * With {@code apiFeatures}, a route shared by the groups {@code notes} and {@code beta} compiles with either group,
     * both or none, without a warning: the dispatch calls only the operations the features compile.
     */
    @Test
    void sharedRoutesBuildWithEachGroupAlone() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.API_FEATURES, "true"));
        for (String features : List.of("", "notes", "beta", "notes,beta")) {
            List<String> args = new ArrayList<>(List.of("clippy", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            args.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(out, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), "[" + features + "]:\n" + output);
        }
    }

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
