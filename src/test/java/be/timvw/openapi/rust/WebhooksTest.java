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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code openapi.webhooks} on src/test/resources/webhooks.yaml. openapi-generator writes the webhooks of a tag into
 * the API file of that tag after the path operations of the tag: without {@code webhookModule} the untagged webhooks
 * replaced the untagged path operations in {@code default_api.rs}. They now share the module.
 */
class WebhooksTest {

    private static final String SPEC = "src/test/resources/webhooks.yaml";

    @TempDir
    static Path merged;

    @TempDir
    static Path withResponses;

    @TempDir
    static Path apart;

    @BeforeAll
    static void generate() {
        generate(SPEC, merged, new LinkedHashMap<>());
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        generate(SPEC, withResponses, responses);
        Map<String, Object> module = new LinkedHashMap<>();
        module.put(TimvwRustCodegen.WEBHOOK_MODULE, "Hooks");
        generate(SPEC, apart, module);
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "webhooks_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    /** Regression (0.22.0): the untagged path operation was lost when an untagged webhook replaced default_api.rs. */
    @Test
    void untaggedWebhooksJoinTheUntaggedPathOperationsInDefaultApi() throws IOException {
        String api = read(merged, "src/apis/default_api.rs");
        assertContains(api, "pub async fn list_items(configuration: &configuration::Configuration, ) "
                + "-> Result<Vec<models::Item>, Error<ListItemsError>>");
        assertContains(api, "pub enum ListItemsError {");
        assertContains(api, "pub async fn item_created(configuration: &configuration::Configuration, "
                + "item: Option<&models::Item>) -> Result<(), Error<ItemCreatedError>>");
        assertContains(api, "pub enum ItemCreatedError {");
        assertTrue(api.indexOf("pub async fn list_items(") < api.indexOf("pub async fn item_created("), api);
        String doc = read(merged, "docs/DefaultApi.md");
        assertContains(doc, "## list_items");
        assertContains(doc, "## item_created");
    }

    /** A tagged webhook joins the path operations of its tag the same way. */
    @Test
    void aTaggedWebhookJoinsThePathOperationsOfItsTag() throws IOException {
        String api = read(merged, "src/apis/pets_api.rs");
        assertContains(api, "pub async fn get_pet(configuration: &configuration::Configuration, id: &str)");
        assertContains(api, "pub async fn pet_adopted(");
        String doc = read(merged, "docs/PetsApi.md");
        assertContains(doc, "## get_pet");
        assertContains(doc, "## pet_adopted");
        String mod = read(merged, "src/apis/mod.rs");
        assertContains(mod, "pub mod default_api;");
        assertContains(mod, "pub mod pets_api;");
    }

    /** The path operations keep what their own pass gave them; the webhooks get no {@code _response} twin. */
    @Test
    void responseFunctionsStayOnThePathOperations() throws IOException {
        String api = read(withResponses, "src/apis/default_api.rs");
        assertContains(api, "pub async fn list_items_response(");
        assertContains(api, "pub async fn item_created(");
        assertFalse(api.contains("item_created_response"), api);
    }

    /** With {@code webhookModule} the untagged webhook goes into its own module and default_api keeps only paths. */
    @Test
    void webhookModuleStillKeepsTheWebhooksApart() throws IOException {
        String api = read(apart, "src/apis/default_api.rs");
        assertContains(api, "pub async fn list_items(");
        assertFalse(api.contains("item_created"), api);
        String hooks = read(apart, "src/apis/hooks_api.rs");
        assertContains(hooks, "pub async fn item_created(");
        assertContains(read(apart, "src/apis/mod.rs"), "pub mod hooks_api;");
    }

    /**
     * A webhook that would take the name of a path method of its module fails generation, naming the option. The
     * spec validation already rejects two equal operationIds; a {@code _response} twin is a method name too.
     */
    @Test
    void aWebhookNamedLikeAPathMethodFails(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /items:
                    get:
                      operationId: listItems
                      responses:
                        "204": {description: ok}
                webhooks:
                  listed:
                    post:
                      operationId: listItemsResponse
                      responses:
                        "200": {description: ok}
                """);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(spec.toString(), dir.resolve("out"), props));
        String messages = "";
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages += t.getMessage() + "\n";
        }
        assertTrue(messages.contains("webhook list_items_response goes into the API module DefaultApi with "
                + "the path operations of its tag, where a path method is already named list_items_response"), messages);
        assertTrue(messages.contains("set webhookModule"), messages);
    }

    /** The crate with path operations and webhooks in one module builds and passes clippy. Needs cargo. */
    @Test
    void theMergedCrateCompiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(merged, 30, "--version") != null, "no cargo");
        for (Path crate : List.of(merged, withResponses)) {
            String clippy = cargo(crate, 900, "clippy", "--all-targets", "--", "-D", "warnings",
                    "-A", "clippy::needless_return");
            assertNotNull(clippy, "clippy did not finish");
            assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"),
                    clippy);
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
