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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code targets} and the axum server (slices 1 and 2 of docs/design/targets-and-server.md) on
 * src/test/resources/server.yaml. src/test/rust/server.rs runs the server generated with {@code ["client", "server"]}
 * on a local port and calls it with the client of the same crate; {@link #theClientCallsTheServer} runs it and clippy
 * with cargo.
 */
class ServerTest {

    private static final String SPEC = "src/test/resources/server.yaml";

    @TempDir
    Path out;

    private static void generate(Path dir, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "server_fixture");
        props.putAll(options);
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

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    /** Every file of a generated crate, by its path relative to the crate. */
    private static Map<String, String> files(Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(dir.relativize(p).toString(), Files.readString(p));
            }
        }
        return files;
    }

    // ---------------------------------------------------------------- the option

    @Test
    void theDefaultIsTheClient() throws IOException {
        generate(out.resolve("unset"), Map.of());
        generate(out.resolve("client"), Map.of(TimvwRustCodegen.TARGETS, List.of("client")));
        generate(out.resolve("models-client"), Map.of(TimvwRustCodegen.TARGETS, "models|client"));
        Map<String, String> unset = files(out.resolve("unset"));
        assertEquals(unset, files(out.resolve("client")));
        assertEquals(unset, files(out.resolve("models-client")));
        assertFalse(unset.containsKey("src/server.rs"));
        assertFalse(unset.containsKey("src/headers.rs"));
        assertFalse(unset.get("Cargo.toml").contains("axum"));
    }

    @Test
    void anInvalidListFails() {
        for (Object value : List.of("all", List.of("client", "all"), "client|mock", List.of(), " ")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> generate(out, Map.of(TimvwRustCodegen.TARGETS, value)), String.valueOf(value));
            assertContains(e.getMessage(), "option 'targets' is invalid");
        }
        IllegalArgumentException all = assertThrows(IllegalArgumentException.class,
                () -> generate(out, Map.of(TimvwRustCodegen.TARGETS, "all")));
        assertContains(all.getMessage(), "there is no 'all'");
    }

    @Test
    void anOptionOfAnotherTargetFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.RESPONSE_FUNCTIONS, "true")));
        assertEquals("timvw-rust: option 'responseFunctions' applies to the client, and 'client' is not in targets "
                + "[server]; remove the option or add 'client' to targets", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.TARGETS, "models", TimvwRustCodegen.WEBHOOK_MODULE, "webhooks")));
        assertContains(e.getMessage(), "option 'webhookModule' applies to the client");
        e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.SERVER_FRAMEWORK, "axum")));
        assertEquals("timvw-rust: option 'serverFramework' applies to the server, and 'server' is not in targets "
                + "[client]; remove the option or add 'server' to targets", e.getMessage());
        // the options of the models apply to every target
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.VALIDATE_METHODS, "false",
                TimvwRustCodegen.API_FEATURES, "true", RustCodegenBase.USE_CHRONO, "true"));
    }

    @Test
    void theServerOptionsTakeTheirOneValue() {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_FRAMEWORK, "axum",
                TimvwRustCodegen.SERVER_VALIDATION, "false"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_FRAMEWORK, "actix")));
        assertContains(e.getMessage(), "the server is only rendered for 'axum'");
        e = assertThrows(IllegalArgumentException.class, () -> generate(out,
                Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.SERVER_VALIDATION, "true")));
        assertContains(e.getMessage(), "option 'serverValidation' is not supported yet");
    }

    // ---------------------------------------------------------------- what each target renders

    @Test
    void theModelsAlone() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "models"));
        Map<String, String> files = files(out);
        assertTrue(files.containsKey("src/models/pet.rs"));
        assertFalse(files.keySet().stream().anyMatch(f -> f.startsWith("src/apis/") || f.startsWith("docs/Pets")),
                files.keySet().toString());
        assertFalse(files.containsKey("src/server.rs"));
        String cargo = files.get("Cargo.toml");
        for (String dependency : List.of("timvw-rust-runtime =", "reqwest", "axum", "http =", "native-tls", "retry")) {
            assertFalse(cargo.contains(dependency), dependency + " in:\n" + cargo);
        }
        assertContains(cargo, "[features]\ndefault = []\n");
        String lib = files.get("src/lib.rs");
        assertFalse(lib.contains("pub mod apis;"), lib);
        assertContains(lib, "pub mod models;\n");
        assertContains(files.get("README.md"), "# Rust API models for server_fixture");
    }

    @Test
    void theServerAlone() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, List.of("server")));
        Map<String, String> files = files(out);
        assertFalse(files.keySet().stream().anyMatch(f -> f.startsWith("src/apis/")), files.keySet().toString());
        String cargo = files.get("Cargo.toml");
        assertFalse(cargo.contains("timvw-rust-runtime ="), cargo);
        assertContains(cargo, "axum = { version = \"^0.8\", default-features = false, features = [\"multipart\"] }\n"
                + "futures-util = { version = \"^0.3\", default-features = false, features = [\"std\"] }\n"
                + "http = { version = \"^1\" }\n");
        assertContains(cargo, "[features]\ndefault = []\n");
        assertContains(files.get("src/lib.rs"), "pub mod models;\npub mod headers;\npub mod server;\n");
        assertContains(files.get("README.md"), "# Rust API server for server_fixture");
        assertContains(files.get("README.md"), "- `search_pets` (**POST** /pets/search): its request body is "
                + "'application/x-www-form-urlencoded'");
    }

    @Test
    void aTraitPerApiModuleWithAnAsyncFunctionPerOperation() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "pub use self::pets_api::PetsApi;\n");
        assertContains(server, "    pub trait PetsApi: Send + Sync {\n");
        assertContains(server, "        /// `GET /pets`: Lists the pets.\n"
                + "        fn list_pets(\n            &self,\n            request: ListPetsRequest,\n"
                + "        ) -> impl std::future::Future<Output = Result<ListPetsResponse, super::ServerError>> + Send;\n");
        assertContains(server, "pub fn router<I>(api: I) -> axum::Router\nwhere\n"
                + "    I: owners_api::OwnersApi + pets_api::PetsApi + ErrorHandler + 'static,\n");
        assertContains(server, "pub trait ErrorHandler {\n");
    }

    @Test
    void aRequestStructPerOperation() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    pub struct ListPetsRequest {\n"
                + "        /// `limit`, a query parameter: How many pets to return.\n"
                + "        pub limit: Option<i32>,\n"
                + "        /// `kind`, a query parameter\n"
                + "        pub kind: Option<models::Kind>,\n"
                + "        /// `tags`, a query parameter: Repeated, one pair per tag.\n"
                + "        pub tags: Option<Vec<String>>,\n"
                + "        /// `ids`, a query parameter: Comma-separated.\n"
                + "        pub ids: Option<Vec<i64>>,\n"
                + "        /// The request's headers, with its header and cookie parameters and credentials.\n"
                + "        pub headers: http::HeaderMap,\n    }\n");
        assertContains(server, "    pub struct GetPetRequest {\n        /// `petId`, a path parameter\n"
                + "        pub pet_id: i64,\n        /// `verbose`, a query parameter\n        pub verbose: bool,\n");
        assertContains(server, "        /// The request body\n        pub body: models::NewPet,\n");
        assertContains(server, "        /// The request body\n        pub body: Option<models::NewPet>,\n");
        // decoding: by position in the route, repeated or split
        assertContains(server, "let router = router.route(\"/pets/{p0}\", axum::routing::get(handle_get_pet::<I>));");
        assertContains(server, "\"/owners/{p0}/pets/{p1}\"");
        assertContains(server, "pet_id: super::path_value(path, \"p0\", \"petId\", |v| v.parse::<i64>().ok())?,");
        assertContains(server, "verbose: super::required(\"query\", \"verbose\", super::query_value(query, "
                + "\"verbose\", |v| v.parse::<bool>().ok())?)?,");
        assertContains(server, "tags: super::query_list(query, \"tags\", None, |v| Some(v.to_owned()))?,");
        assertContains(server, "ids: super::query_list(query, \"ids\", Some(\",\"), |v| v.parse::<i64>().ok())?,");
        assertContains(server, "body: super::json_body(&body)?,");
        assertContains(server, "body: super::optional_json_body(&body)?,");
    }

    @Test
    void aResponseEnumPerOperationWithTypedHeaders() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    pub enum ListPetsResponse {\n        /// `200`: the pets\n"
                + "        Status200 { body: Vec<models::Pet>, headers: crate::headers::ListPetsResponseHeaders },\n"
                + "        /// `4XX`: a client error\n"
                + "        Status4XX { status: http::StatusCode, body: models::Error, headers: "
                + "crate::headers::ListPetsErrorHeaders },\n    }\n");
        assertContains(server, "        /// `201`: created\n        Status201(models::Pet),\n");
        assertContains(server, "        /// `204`: deleted\n        Status204,\n        /// `404`: no such pet\n"
                + "        Status404,\n");
        assertContains(server, "        Default { status: http::StatusCode, body: models::Error },\n");
        assertContains(server, "ListPetsResponse::Status200 { body, headers } => super::json_response("
                + "super::status(200), headers.to_header_map(), &body),");
        String headers = read(out, "src/headers.rs");
        assertContains(headers, "pub struct ListPetsResponseHeaders {\n    /// `X-Total`\n    pub x_total: Option<i64>,\n");
        assertContains(headers, "pub fn write_to(&self, headers: &mut http::HeaderMap) {");
        assertContains(headers, "            for v in value {\n                items.push(json_text(v));\n");
        assertContains(headers, "            let text = v.to_rfc3339();\n            put(headers, \"x-next\", text);");
    }

    @Test
    void whatTheServerCannotServeIsLeftOutWithAComment() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    // Not served: `search_pets` (POST /pets/search): its request body is "
                + "'application/x-www-form-urlencoded'.\n");
        assertFalse(server.contains("fn search_pets("), server);
        assertFalse(server.contains("SearchPetsRequest"), server);
    }

    @Test
    void anEventStreamIsATypedStreamOfItsEvents() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    #[derive(Debug)]\n    pub enum OwnerEventsResponse {\n        /// `200`: a stream of pets\n"
                + "        Status200(super::EventStream<models::Pet>),\n    }\n");
        assertContains(server, "OwnerEventsResponse::Status200(body) => super::event_stream_response(super::status(200), "
                + "http::HeaderMap::new(), body),");
        assertContains(server, "pub struct EventStream<T> {");
        assertContains(server, "line.extend_from_slice(b\"\\n\\n\");");
        assertContains(server, "b\"data: [DONE]\\n\\n\"");
    }

    @Test
    void aStatusWithJsonAndAStreamHasAVariantForEach() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    pub enum PetFeedResponse {\n"
                + "        /// `200`, `application/json`: the pets, as a list or as a stream\n"
                + "        Status200(Vec<models::Pet>),\n"
                + "        /// `200`, `text/event-stream`: the pets, as a list or as a stream\n"
                + "        Status200Stream(super::EventStream<models::Pet>),\n    }\n");
    }

    @Test
    void binaryAndTextResponses() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "        Status200 { body: super::Binary, headers: crate::headers::GetPhotoResponseHeaders },\n"
                + "        /// `404`: no photo\n        Status404(String),\n");
        assertContains(server, "GetPhotoResponse::Status200 { body, headers } => super::binary_response(super::status(200), "
                + "headers.to_header_map(), body, \"image/png\"),");
        assertContains(server, "GetPhotoResponse::Status404(body) => super::text_response(super::status(404), "
                + "http::HeaderMap::new(), body, \"text/plain\"),");
        assertContains(server, "        Status200(String),\n");
        assertContains(server, "pub struct Binary {");
        // a body read once: the enum is not Clone
        assertContains(server, "    #[derive(Debug)]\n    pub enum GetPhotoResponse {");
        assertContains(server, "    #[derive(Clone, Debug)]\n    pub enum GetPetNameResponse {");
    }

    @Test
    void binaryTextAndMultipartRequestBodies() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "server"));
        String server = read(out, "src/server.rs");
        assertContains(server, "    pub struct PutPhotoRequest {\n        /// `petId`, a path parameter\n        pub pet_id: i64,\n"
                + "        /// The request body\n        pub body: axum::body::Bytes,\n");
        assertContains(server, "            body,\n            headers,\n");
        assertContains(server, "    pub struct RenamePetRequest {\n        /// `petId`, a path parameter\n        pub pet_id: i64,\n"
                + "        /// The request body\n        pub body: String,\n");
        assertContains(server, "body: super::text_body(&body)?,");
        assertContains(server, "        /// The request body\n        pub body: UploadPhotoForm,\n");
        assertContains(server, "    pub struct UploadPhotoForm {\n        /// `file`\n        pub file: super::FilePart,\n"
                + "        /// `caption`\n        pub caption: Option<String>,\n        /// `width`\n"
                + "        pub width: Option<i64>,\n        /// `tags`\n        pub tags: Option<Vec<String>>,\n"
                + "        /// `extras`\n        pub extras: Option<Vec<super::FilePart>>,\n        /// `owner`\n"
                + "        pub owner: Option<models::NewPet>,\n    }\n");
        assertContains(server, "            file: super::required(\"body\", \"file\", super::form_file(parts, \"file\"))?,\n"
                + "            caption: super::form_value(parts, \"caption\", |v| Some(v.to_owned()))?,\n"
                + "            width: super::form_value(parts, \"width\", |v| v.parse::<i64>().ok())?,\n"
                + "            tags: super::form_list(parts, \"tags\", |v| Some(v.to_owned()))?,\n"
                + "            extras: super::form_files(parts, \"extras\"),\n"
                + "            owner: super::form_json(parts, \"owner\")?,\n");
        assertContains(server, "        let parts = match super::multipart_parts(request).await {");
        assertContains(server, "pub struct FilePart {");
    }

    @Test
    void aJsonOnlyServerNeedsNoMoreDependencies() throws IOException {
        Path spec = out.resolve("json.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info:
                  title: Json
                  version: 1.0.0
                paths:
                  /ping:
                    get:
                      operationId: ping
                      responses:
                        "200":
                          description: pong
                          content:
                            application/json:
                              schema:
                                type: string
                """);
        Path dir = out.resolve("crate");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "json_fixture");
        props.put(TimvwRustCodegen.TARGETS, "server");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(spec.toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
        String cargo = read(dir, "Cargo.toml");
        assertContains(cargo, "axum = { version = \"^0.8\", default-features = false }\nhttp = { version = \"^1\" }\n");
        String server = read(dir, "src/server.rs");
        assertFalse(server.contains("pub struct EventStream") || server.contains("pub struct FilePart"), server);
    }

    @Test
    void bothTargetsAreCargoFeatures() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.RESPONSE_FUNCTIONS, "true"));
        String cargo = read(out, "Cargo.toml");
        assertContains(cargo, ", optional = true }\naxum = { version = \"^0.8\", default-features = false, "
                + "features = [\"multipart\"], optional = true }\nfutures-util = { version = \"^0.3\", "
                + "default-features = false, features = [\"std\"], optional = true }\n"
                + "http = { version = \"^1\", optional = true }\n");
        assertContains(cargo, "default = [\"native-tls\", \"retry\", \"client\", \"server\"]\n"
                + "native-tls = [\"timvw-rust-runtime?/native-tls\"]\n");
        // chrono: only the typed headers (X-Next) use it, which the models do not compile
        assertContains(cargo, "chrono = { version = \"^0.4\",  features = [\"serde\"], optional = true }\n");
        assertContains(cargo, "client = [\"dep:timvw-rust-runtime\", \"dep:http\", \"dep:chrono\"]\n"
                + "server = [\"dep:axum\", \"dep:http\", \"dep:futures-util\", \"dep:chrono\"]\n");
        String lib = read(out, "src/lib.rs");
        assertContains(lib, "#[cfg(feature = \"client\")]\npub mod apis;\npub mod models;\n"
                + "#[cfg(any(feature = \"client\", feature = \"server\"))]\npub mod headers;\n"
                + "#[cfg(feature = \"server\")]\npub mod server;\n");
        // the client re-exports the shared header structs
        String api = read(out, "src/apis/pets_api.rs");
        assertContains(api, "pub use crate::headers::ListPetsResponseHeaders;\n");
        assertContains(api, "pub use crate::headers::ListPetsErrorHeaders;\n");
        assertFalse(api.contains("pub struct ListPetsResponseHeaders"), api);
        assertContains(read(out, "README.md"), "# Rust API client and server for server_fixture");
    }

    @Test
    void apiFeaturesGateTheServerByGroup() throws IOException {
        generate(out, Map.of(TimvwRustCodegen.TARGETS, "client|server", TimvwRustCodegen.API_FEATURES, "true"));
        String cargo = read(out, "Cargo.toml");
        assertContains(cargo, "_api = []\n");
        assertContains(cargo, "pets = [\"pets-types\", \"_api\"]\n");
        assertContains(read(out, "src/lib.rs"), "#[cfg(all(feature = \"client\", feature = \"_api\"))]\npub mod apis;");
        String server = read(out, "src/server.rs");
        assertContains(server, "        #[cfg(feature = \"pets\")]\n        fn list_pets(\n");
        assertContains(server, "        #[cfg(feature = \"pets\")]\n        let router = router.route(\"/pets\"");
        assertContains(server, "    #[cfg(feature = \"pets\")]\n    #[derive(Clone, Debug)]\n    pub struct ListPetsRequest {");
        assertContains(read(out, "src/headers.rs"), "#[cfg(feature = \"pets\")]\n#[derive(Clone, Debug, Default, "
                + "PartialEq)]\npub struct ListPetsResponseHeaders {");
    }

    // ---------------------------------------------------------------- with cargo

    /**
     * Runs src/test/rust/server.rs (the client calls the server of the same crate) and clippy on the crate with both
     * targets, and builds each target on its own. Needs cargo; without it there is nothing to run.
     */
    @Test
    void theClientCallsTheServer() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        generate(out, Map.of(TimvwRustCodegen.TARGETS, List.of("client", "server"),
                TimvwRustCodegen.RESPONSE_FUNCTIONS, "true", TimvwRustCodegen.TYPED_EVENT_STREAMS, "true",
                TimvwRustCodegen.CONTENT_TYPE_METHODS, "true"));
        Path manifest = out.resolve("Cargo.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [dev-dependencies]
                axum = { version = "0.8", default-features = false, features = ["tokio", "http1", "json"] }
                futures-util = "0.3"
                tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "time"] }
                """);
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/server.rs"), out.resolve("tests/server.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(out, 900, "test", "--test", "server");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        String clippy = cargo(out, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);
        for (String features : List.of("server", "client,rustls", "")) {
            List<String> args = new ArrayList<>(List.of("clippy", "--no-default-features"));
            if (!features.isEmpty()) {
                args.addAll(List.of("--features", features));
            }
            args.addAll(List.of("--", "-D", "warnings"));
            String output = cargo(out, 900, args.toArray(String[]::new));
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), features + ":\n" + output);
        }
        String tree = cargo(out, 300, "tree", "--no-default-features", "--features", "server", "-e", "normal");
        assertNotNull(tree);
        assertFalse(tree.contains("timvw-rust-runtime") || tree.contains("reqwest"), tree);
    }

    /** The server alone, the models alone, and the server with API features build without a warning. */
    @Test
    void eachTargetBuildsAlone() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Map<String, Map<String, Object>> crates = Map.of(
                "server", Map.of(TimvwRustCodegen.TARGETS, "server"),
                "models", Map.of(TimvwRustCodegen.TARGETS, "models"),
                "gated", Map.of(TimvwRustCodegen.TARGETS, "server", TimvwRustCodegen.API_FEATURES, "true"));
        for (Map.Entry<String, Map<String, Object>> crate : crates.entrySet()) {
            Map<String, Object> options = crate.getValue();
            Path dir = out.resolve(crate.getKey());
            generate(dir, options);
            String output = cargo(dir, 900, "clippy", "--", "-D", "warnings");
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished") && !output.contains("warning:"), options + ":\n" + output);
            String tree = cargo(dir, 300, "tree", "-e", "normal");
            assertNotNull(tree);
            assertFalse(tree.contains("timvw-rust-runtime") || tree.contains("reqwest"), tree);
        }
        String pets = cargo(out.resolve("gated"), 900, "clippy", "--no-default-features", "--features",
                "pets", "--", "-D", "warnings");
        assertNotNull(pets, "cargo did not finish");
        assertTrue(pets.contains("Finished") && !pets.contains("warning:"), pets);
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
