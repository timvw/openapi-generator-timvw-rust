package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.DefaultCodegen;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.config.GlobalSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An additional property that is not an option fails generation, naming the closest option. */
class UnknownOptionsTest {

    private static final String SPEC = String.join("\n",
            "openapi: 3.0.3",
            "info: {title: t, version: '1'}",
            "paths:",
            "  /things/{id}:",
            "    get:",
            "      operationId: getThing",
            "      parameters:",
            "        - {name: id, in: path, required: true, schema: {type: string}}",
            "      responses:",
            "        '200':",
            "          description: ok",
            "          content:",
            "            application/json:",
            "              schema: {$ref: '#/components/schemas/Thing'}",
            "components:",
            "  schemas:",
            "    Thing:",
            "      type: object",
            "      properties:",
            "        name: {type: string}",
            "");

    private static IllegalArgumentException failsWith(Map<String, Object> props) {
        TimvwRustCodegen c = new TimvwRustCodegen();
        c.additionalProperties().putAll(props);
        return assertThrows(IllegalArgumentException.class, c::processOpts);
    }

    @Test
    void anUnknownOptionFailsAndNamesTheClosestOption() {
        assertEquals("timvw-rust: unknown option 'bonBuilder' (did you mean 'bonBuilders'?); remove it or correct its "
                        + "name (config-help -g timvw-rust lists the options)",
                failsWith(Map.of("bonBuilder", "true")).getMessage());
    }

    @Test
    void theClosestOptionIgnoresCase() {
        assertTrue(failsWith(Map.of("TypedEventStreams", "true")).getMessage()
                .contains("'TypedEventStreams' (did you mean 'typedEventStreams'?)"));
        assertTrue(failsWith(Map.of("packagename", "x")).getMessage()
                .contains("'packagename' (did you mean 'packageName'?)"));
    }

    @Test
    void anOptionFarFromEveryOptionIsNamedWithoutASuggestion() {
        assertEquals("timvw-rust: unknown option 'frobnicate'; remove it or correct its name (config-help -g "
                + "timvw-rust lists the options)", failsWith(Map.of("frobnicate", "1")).getMessage());
    }

    @Test
    void everyUnknownOptionIsNamedInOneError() {
        assertEquals("timvw-rust: unknown options 'bonBuilder' (did you mean 'bonBuilders'?), 'frobnicate', "
                        + "'multipartHelpr' (did you mean 'multipartHelper'?); remove them or correct their names "
                        + "(config-help -g timvw-rust lists the options)",
                failsWith(Map.of("multipartHelpr", "true", "frobnicate", "1", "bonBuilder", "true",
                        TimvwRustCodegen.TYPED_EVENT_STREAMS, "true")).getMessage());
    }

    /** The options removed or fixed keep their own message, and are never suggested. */
    @Test
    void notConfigurableAndUnsupportedOptionsKeepTheirMessage() {
        Map<String, String> expected = Map.of(
                "boxOnlyCycles", "'boxOnlyCycles' is not configurable",
                "supportAsync", "'supportAsync' is not configurable",
                "mockall", "'mockall' is not supported");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String message = failsWith(Map.of(entry.getKey(), "true", "frobnicate", "1")).getMessage();
            assertTrue(message.contains(entry.getValue()), message);
        }
    }

    @Test
    void notConfigurableAndUnsupportedOptionsAreNeverSuggested() {
        List<String> notOptions = new ArrayList<>(TimvwRustCodegen.ALWAYS_ON);
        notOptions.addAll(TimvwRustCodegen.FIXED_VALUES.keySet());
        notOptions.addAll(TimvwRustCodegen.REJECTED_OPTIONS);
        notOptions.add(TimvwRustCodegen.JSON_VALUE_SCHEMAS);
        notOptions.add(TimvwRustCodegen.LEGACY_MODEL_REWRITES);
        List<CliOption> advertised = new TimvwRustCodegen().cliOptions();
        for (String option : notOptions) {
            // one edit away: a misspelling of the option
            String misspelt = option.substring(0, option.length() - 1);
            String closest = UnknownOptions.closest(misspelt, advertised);
            assertFalse(notOptions.contains(closest), misspelt + " -> " + closest);
            String message = failsWith(Map.of(misspelt, "true")).getMessage();
            assertTrue(message.startsWith("timvw-rust: unknown option '" + misspelt + "'"), message);
            assertFalse(message.contains("'" + option + "'"), message);
        }
        assertNull(UnknownOptions.closest("boxOnlyCycle", advertised));
    }

    /** openapi-generator's general options: DefaultCodegen's cliOptions and the other keys its processOpts reads. */
    @Test
    void generalOptionsAreAccepted() {
        TimvwRustCodegen c = new TimvwRustCodegen();
        for (CliOption option : new DefaultCodegen().cliOptions()) {
            c.additionalProperties().put(option.getOpt(), "false");
        }
        c.additionalProperties().put("sortParamsByRequiredFlag", "true");
        c.additionalProperties().put("removeOperationIdPrefix", "false");
        c.additionalProperties().put("removeOperationIdPrefixDelimiter", "_");
        c.additionalProperties().put("removeOperationIdPrefixCount", "1");
        c.additionalProperties().put("apiNamePrefix", "");
        c.additionalProperties().put("enablePostProcessFile", "false");
        c.additionalProperties().put("skipOperationExample", "true");
        c.additionalProperties().put("docExtension", "md");
        assertDoesNotThrow(c::processOpts);
        assertDoesNotThrow(() -> UnknownOptions.check("timvw-rust", UnknownOptions.DEFAULT_CODEGEN_KEYS,
                List.of(), List.of()));
        assertDoesNotThrow(() -> UnknownOptions.check("timvw-rust", UnknownOptions.TEMPLATE_KEYS, List.of(),
                List.of()));
    }

    /**
     * The keys openapi-generator puts in the map itself, through CodegenConfigurator and DefaultGenerator as the CLI
     * (and the Maven plugin) runs them.
     */
    @Test
    void keysOpenApiGeneratorSetsItselfAreAccepted(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, SPEC);
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(spec.toString())
                .setOutputDir(dir.resolve("out").toString())
                .setGitHost("gitlab.com")
                .setGitUserId("someone")
                .setGitRepoId("things")
                .setReleaseNote("first")
                .setHttpUserAgent("things/1")
                .setPackageName("things")
                .setApiPackage("apis")
                .setModelPackage("models")
                .setInvokerPackage("invoker")
                .setGroupId("group")
                .setArtifactId("artifact")
                .setArtifactVersion("1.0.0")
                .setApiNameSuffix("Api")
                .setModelNamePrefix("")
                .setModelNameSuffix("")
                .setEnablePostProcessFile(false)
                .setSkipOperationExample(true)
                .setRemoveOperationIdPrefix(false)
                .addGlobalProperty("apiTests", "false")
                .addGlobalProperty("modelTests", "false")
                .addAdditionalProperty("generatorVersion", "0.0.0");
        try {
            ClientOptInput input = configurator.toClientOptInput();
            Set<String> before = Set.copyOf(input.getConfig().additionalProperties().keySet());
            assertTrue(before.containsAll(List.of("gitUserId", "releaseNote", "httpUserAgent", "artifactId",
                    "lambda", "openbrace")), before.toString());
            new DefaultGenerator().opts(input).generate();
            assertTrue(input.getConfig().additionalProperties().containsKey("excludeTests"));
            assertTrue(Files.exists(dir.resolve("out/src/lib.rs")));
        } finally {
            GlobalSettings.reset();
        }
    }

    /** A key of a {@code -c} config file lands in the same map, so a misspelt one fails too. */
    @Test
    void aConfigFileWithAnUnknownKeyFails(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("spec.yaml");
        Files.writeString(spec, SPEC);
        Path config = dir.resolve("config.json");
        Files.writeString(config, "{\"packageName\": \"things\", \"gitUserId\": \"someone\", "
                + "\"typedEventStream\": true}");
        CodegenConfigurator configurator = CodegenConfigurator.fromFile(config.toString())
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(spec.toString())
                .setOutputDir(dir.resolve("out").toString());
        ClientOptInput input = configurator.toClientOptInput();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new DefaultGenerator().opts(input).generate());
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertEquals("timvw-rust: unknown option 'typedEventStream' (did you mean 'typedEventStreams'?); remove it "
                + "or correct its name (config-help -g timvw-rust lists the options)", cause.getMessage());
    }

    /** The keys both clients set (without the options decision 12 removed) are all options. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // openai: scripts/generator-config.json
            "packageName,packageVersion,useChrono,titleNaming,multipartHelper,boxResponseError,apiLintAllows,"
                    + "userAgentFromPackage,webhookModule,webhookModuleReexport,resetQueryParameterStyle,"
                    + "typedEventStreams,contentTypeMethods,preprocessedSpecFile,stringIdSchemas,stringIdProperties,"
                    + "stringProperties,fileProperties,deepObjectQueryParameters",
            // langfuse: scripts/generate-openapi-client.sh
            "packageName,packageVersion,typedEnumParameters,deprecatedAttributes",
    })
    void theClientsOptionsAreAccepted(String keys) {
        List<CliOption> advertised = new TimvwRustCodegen().cliOptions();
        assertDoesNotThrow(() -> UnknownOptions.check("timvw-rust", List.of(keys.split(",")), advertised,
                List.of()));
    }

    @Test
    void editDistance() {
        assertEquals(0, UnknownOptions.distance("abc", "abc"));
        assertEquals(1, UnknownOptions.distance("abc", "ab"));
        assertEquals(1, UnknownOptions.distance("abc", "abd"));
        assertEquals(3, UnknownOptions.distance("", "abc"));
        assertEquals(2, UnknownOptions.distance("ab", "ba"));
    }
}
