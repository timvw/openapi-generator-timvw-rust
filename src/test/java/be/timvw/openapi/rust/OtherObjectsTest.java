package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The objects of docs/mapping that render little or nothing: operation summary and description, Header, Callback,
 * Link, Example, Tag, External Documentation, XML and OAuth Flows, rendered without options from
 * {@code src/test/resources/other_objects.yaml}. Each marker in the spec (TAGDESC, HEADERDESC, ...) is looked up in
 * the generated Rust ({@code src/}) and in the generated Markdown ({@code docs/}, {@code README.md}).
 */
class OtherObjectsTest {

    @TempDir
    static Path out;

    private static String rust;
    private static String markdown;

    @BeforeAll
    static void generate() throws IOException {
        Map<String, Object> props = new LinkedHashMap<>(SchemaMappingTest.BASELINE);
        props.put("packageName", "other_objects");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/other_objects.yaml").toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
        rust = concat(out.resolve("src"), ".rs");
        markdown = concat(out.resolve("docs"), ".md") + Files.readString(out.resolve("README.md"));
    }

    private static String concat(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(suffix)).sorted().map(f -> {
                try {
                    return Files.readString(f);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).collect(Collectors.joining("\n"));
        }
    }

    private static void nowhere(String marker) {
        assertFalse(rust.contains(marker), marker + " in the generated Rust");
        assertFalse(markdown.contains(marker), marker + " in the generated Markdown");
    }

    @Test
    void anOperationsDescriptionDocumentsItsFunctionAndItsSummaryOnlyTheMarkdown() throws IOException {
        String api = Files.readString(out.resolve("src/apis/hooks_api.rs"));
        assertTrue(api.contains("/// OPDESC Registers a callback.\n#[bon::builder]\npub async fn subscribe("), api);
        assertFalse(rust.contains("SUMMARYTEXT"));
        assertTrue(Files.readString(out.resolve("docs/HooksApi.md")).contains("SUMMARYTEXT Subscribe."));
    }

    @Test
    void declaredResponseHeadersGetNoTypedAccessorWithoutResponseFunctions() {
        nowhere("X-Rate-Limit");
        nowhere("HEADERDESC");
    }

    @Test
    void aCallbackHasNoFunctionButItsSchemasAreModels() throws IOException {
        assertFalse(rust.contains("on_event_callback"));
        assertFalse(rust.contains("onEvent"));
        assertTrue(Files.exists(out.resolve("src/models/callback_event.rs")));
        assertTrue(Files.readString(out.resolve("src/models/callback_event.rs")).contains("pub struct CallbackEvent {"));
    }

    @Test
    void linksAreIgnored() {
        assertFalse(rust.contains("$response.body"));
        assertFalse(rust.contains("GetSubscription\""));
        assertFalse(rust.contains("fn get_subscription_link"));
    }

    @Test
    void examplesAreIgnored() {
        for (String marker : new String[] {"EXAMPLEVALUE", "PARAMEXAMPLE", "SCHEMAEXAMPLE", "PROPEXAMPLE"}) {
            nowhere(marker);
        }
    }

    @Test
    void aTagNamesItsModuleAndNothingElseOfItIsRendered() {
        assertTrue(rust.contains("pub mod hooks_api;"));
        nowhere("TAGDESC");
        nowhere("tag-docs");
    }

    @Test
    void externalDocumentationIsIgnored() {
        for (String marker : new String[] {"example.com/docs", "op-docs", "schema-docs", "tag-docs"}) {
            nowhere(marker);
        }
    }

    @Test
    void xmlIsIgnored() {
        nowhere("subscription-xml");
        assertFalse(rust.contains("quick_xml") || rust.contains("serde_xml"));
    }

    @Test
    void oauthFlowsAndScopesAreIgnored() {
        nowhere("SCOPEDESC");
        nowhere("example.com/authorize");
        nowhere("example.com/token");
        assertTrue(rust.contains("pub oauth_access_token: Option<SecretString>,"));
    }
}
