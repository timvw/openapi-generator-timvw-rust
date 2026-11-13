package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenConfigLoader;
import org.openapitools.codegen.CodegenConstants;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimvwRustCodegenTest {

    private static TimvwRustCodegen codegen() {
        return new TimvwRustCodegen();
    }

    @Test
    void isDiscoverableByName() {
        CodegenConfig config = CodegenConfigLoader.forName("timvw-rust");
        assertInstanceOf(TimvwRustCodegen.class, config);
        assertEquals("timvw-rust", config.getName());
    }

    @Test
    void generatesTheOneConfiguration() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(CodegenConstants.PACKAGE_NAME, "petstore");
        c.processOpts();

        Map<String, Object> props = c.additionalProperties();
        assertEquals("reqwest", c.getLibrary());
        assertEquals("petstore", props.get(CodegenConstants.PACKAGE_NAME));
        assertEquals("petstore", props.get(RustCodegenBase.EXTERN_CRATE_NAME));
        assertEquals(true, props.get(CodegenConstants.HIDE_GENERATION_TIMESTAMP));
        assertTrue(c.isHideGenerationTimestamp());
        // The templates have no branches for the fixed options, so nothing passes them on.
        for (String fixed : TimvwRustCodegen.FIXED_VALUES.keySet()) {
            if (!CodegenConstants.HIDE_GENERATION_TIMESTAMP.equals(fixed)) {
                assertTrue(!props.containsKey(fixed), fixed);
            }
        }
    }

    /** The options timvw-rust took over from stock -g rust keep their names and defaults. */
    @Test
    void crateOptionsKeepTheirDefaults() {
        TimvwRustCodegen c = codegen();
        c.processOpts();
        Map<String, Object> props = c.additionalProperties();
        assertEquals("openapi", props.get(RustCodegenBase.PACKAGE_NAME));
        assertEquals("1.0.0", props.get(RustCodegenBase.PACKAGE_VERSION));
        assertEquals(true, props.get(RustCodegenBase.USE_CHRONO));
        assertEquals(false, props.get(RustCodegenBase.USE_SERDE_PATH_TO_ERROR));
        assertEquals(false, props.get(RustCodegenBase.SUPPORT_MULTIPLE_RESPONSES));
        assertEquals(List.of("native-tls"), props.get(RustCodegenBase.REQWEST_DEFAULT_FEATURES));
        java.util.Map<String, String> advertised = new java.util.HashMap<>();
        for (org.openapitools.codegen.CliOption o : c.cliOptions()) {
            advertised.put(o.getOpt(), o.getDefault());
        }
        assertEquals("openapi", advertised.get(RustCodegenBase.PACKAGE_NAME));
        assertEquals("1.0.0", advertised.get(RustCodegenBase.PACKAGE_VERSION));
        assertEquals("true", advertised.get(RustCodegenBase.USE_CHRONO));
        assertEquals("false", advertised.get(RustCodegenBase.USE_SERDE_PATH_TO_ERROR));
        assertEquals("false", advertised.get(RustCodegenBase.SUPPORT_MULTIPLE_RESPONSES));
        assertEquals("native-tls", advertised.get(RustCodegenBase.REQWEST_DEFAULT_FEATURES));
    }

    @Test
    void useChronoFalseRendersDatesAsStrings() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(RustCodegenBase.USE_CHRONO, "false");
        c.processOpts();
        assertEquals("String", c.typeMapping().get("date"));
        assertEquals("String", c.typeMapping().get("DateTime"));
    }

    @Test
    void reqwestDefaultFeaturesTakesAListOrACommaSeparatedString() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(RustCodegenBase.REQWEST_DEFAULT_FEATURES, "rustls, native-tls");
        c.processOpts();
        assertEquals(List.of("rustls", "native-tls"), c.additionalProperties().get(RustCodegenBase.REQWEST_DEFAULT_FEATURES));
        TimvwRustCodegen empty = codegen();
        empty.additionalProperties().put(RustCodegenBase.REQWEST_DEFAULT_FEATURES, "");
        empty.processOpts();
        assertEquals(List.of(), empty.additionalProperties().get(RustCodegenBase.REQWEST_DEFAULT_FEATURES));
    }

    /** A fixed option is rejected when set, also to the value timvw-rust uses: it is not configurable. */
    @ParameterizedTest(name = "{0}={1} is rejected")
    @CsvSource({
            "library,reqwest",
            "library,hyper",
            "supportAsync,true",
            "supportAsync,false",
            "supportMiddleware,true",
            "supportMiddleware,false",
            "useSingleRequestParameter,false",
            "useSingleRequestParameter,true",
            "preferUnsignedInt,false",
            "preferUnsignedInt,true",
            "bestFitInt,false",
            "bestFitInt,true",
            "enumNameSuffix,''",
            "enumNameSuffix,Enum",
            "hideGenerationTimestamp,true",
            "hideGenerationTimestamp,false",
    })
    void rejectsFixedOptionsWhenSet(String option, String value) {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(option, value);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'" + option + "'"), e.getMessage());
        assertTrue(e.getMessage().contains("not configurable"), e.getMessage());
    }

    /**
     * The former baseline options, and boxResponseError (decision 4), are always on: setting one, to any value, fails
     * like a fixed option.
     */
    @ParameterizedTest(name = "{0} is not configurable")
    @ValueSource(strings = {
            "boxOnlyCycles",
            "defaultOnlyWhereValid",
            "enumFromStr",
            "noStructDisplay",
            "integersAsI64",
            "qualifiedInlineNames",
            "modelUnions",
            "unionDispatch",
            "openEnums",
            "schemaDefaultDocs",
            "nullableUnionFields",
            "boxResponseError",
    })
    void rejectsTheFormerBaselineOptionsWhenSet(String option) {
        for (Object value : new Object[]{"true", "false", Boolean.TRUE, Boolean.FALSE}) {
            TimvwRustCodegen c = codegen();
            c.additionalProperties().put(option, value);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts, option + "=" + value);
            assertEquals("timvw-rust: option '" + option + "' is not configurable: timvw-rust always uses 'true'; "
                    + "remove it (it was set to '" + value + "')", e.getMessage());
        }
        assertTrue(TimvwRustCodegen.ALWAYS_ON.contains(option), option);
    }

    @Test
    void rejectsBooleanValuesOfFixedOptionsToo() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(RustClientCodegenConstants.SUPPORT_ASYNC, Boolean.TRUE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'supportAsync' is not configurable"), e.getMessage());
    }

    @ParameterizedTest(name = "--library {0} is rejected")
    @ValueSource(strings = {"hyper", "hyper0x", "reqwest-trait", "unknown"})
    void rejectsAnotherLibrarySetThroughTheCliSetter(String library) {
        TimvwRustCodegen c = codegen();
        c.setLibrary(library);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'library'"), e.getMessage());
        assertTrue(e.getMessage().contains("reqwest"), e.getMessage());
    }

    @Test
    void acceptsLibraryReqwestThroughTheCliSetter() {
        TimvwRustCodegen c = codegen();
        c.setLibrary("reqwest");
        c.processOpts();
        assertEquals("reqwest", c.getLibrary());
    }

    @ParameterizedTest(name = "{0} is rejected when set")
    @ValueSource(strings = {
            "avoidBoxedModels",
            "mockall",
            "topLevelApiClient",
            "withAWSV4Signature",
            "supportTokenSource",
            "useBonBuilder",
            "templateDir",
    })
    void rejectsOptionsWhenSet(String option) {
        for (String value : new String[]{"true", "false"}) {
            TimvwRustCodegen c = codegen();
            c.additionalProperties().put(option, value);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts, option + "=" + value);
            assertTrue(e.getMessage().contains("'" + option + "'"), e.getMessage());
            assertTrue(e.getMessage().contains("not supported"), e.getMessage());
        }
    }

    /** jsonValueSchemas was removed: a config that still sets it fails rather than silently changing the models. */
    @Test
    void rejectsTheRemovedJsonValueSchemas() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(TimvwRustCodegen.JSON_VALUE_SCHEMAS, List.of("ResponseStreamEvent"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("jsonValueSchemas was removed"), e.getMessage());
        // An empty value fails too: it is no less a configuration that still names the option.
        for (Object value : List.of(List.of(), "")) {
            TimvwRustCodegen empty = codegen();
            empty.additionalProperties().put(TimvwRustCodegen.JSON_VALUE_SCHEMAS, value);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, empty::processOpts);
            assertTrue(failure.getMessage().contains("jsonValueSchemas was removed"), failure.getMessage());
        }
    }

    /** Every rejected, fixed, always-on or removed name fails whatever its value, an empty one too. */
    @Test
    void everyRejectedNameFailsEvenWhenEmpty() {
        java.util.Set<String> names = new java.util.TreeSet<>(OptionsDocsTest.rejected());
        assertTrue(names.contains(TimvwRustCodegen.HTTP_STACK), names.toString());
        for (String name : names) {
            for (String value : List.of("", "true", "false")) {
                TimvwRustCodegen c = codegen();
                c.additionalProperties().put(name, value);
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts,
                        name + "=" + value);
                assertTrue(e.getMessage().contains(name), e.getMessage());
                assertFalse(e.getMessage().contains("unknown option"), e.getMessage());
            }
        }
    }

    @Test
    void advertisesNeitherRejectedNorFixedOptions() {
        TimvwRustCodegen c = codegen();
        java.util.Set<String> opts = new java.util.HashSet<>();
        for (org.openapitools.codegen.CliOption o : c.cliOptions()) {
            opts.add(o.getOpt());
        }
        for (String rejected : TimvwRustCodegen.REJECTED_OPTIONS) {
            assertTrue(!opts.contains(rejected), rejected + " should not be advertised");
        }
        for (String fixed : TimvwRustCodegen.FIXED_VALUES.keySet()) {
            assertTrue(!opts.contains(fixed), fixed + " should not be advertised");
        }
        for (String alwaysOn : TimvwRustCodegen.ALWAYS_ON) {
            assertTrue(!opts.contains(alwaysOn), alwaysOn + " should not be advertised");
        }
        assertTrue(opts.contains(TimvwRustCodegen.BON_BUILDERS), opts.toString());
    }

    @Test
    void bonBuildersIsOnByDefaultAndAdvertised() {
        TimvwRustCodegen c = codegen();
        c.processOpts();
        assertEquals(true, c.additionalProperties().get(TimvwRustCodegen.BON_BUILDERS));
        org.openapitools.codegen.CliOption opt = c.cliOptions().stream()
                .filter(o -> TimvwRustCodegen.BON_BUILDERS.equals(o.getOpt())).findFirst().orElseThrow();
        assertEquals("true", opt.getDefault());
    }

    @Test
    void bonBuildersCanBeSwitchedOff() {
        for (Object value : new Object[]{"false", Boolean.FALSE}) {
            TimvwRustCodegen c = codegen();
            c.additionalProperties().put(TimvwRustCodegen.BON_BUILDERS, value);
            c.processOpts();
            assertEquals(false, c.additionalProperties().get(TimvwRustCodegen.BON_BUILDERS), String.valueOf(value));
        }
    }

    @Test
    void registersTheRustdocLambda() {
        TimvwRustCodegen c = codegen();
        c.processOpts();
        assertInstanceOf(com.samskivert.mustache.Mustache.Lambda.class, c.additionalProperties().get(TimvwRustCodegen.RUSTDOC_LAMBDA));
    }

    @Test
    void rejectsACustomTemplateDir() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(CodegenConstants.TEMPLATE_DIR, "/tmp/templates");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'templateDir'"), e.getMessage());
    }

    /** Every template is the generator's own: none is looked up in the openapi-generator jar. */
    @ParameterizedTest(name = "openapi-generator {0}")
    @ValueSource(strings = {"7.27.0", "7.28.0", "8.0.0", "unset"})
    void rendersOnlyItsOwnTemplates(String version) {
        TimvwRustCodegen c = new TimvwRustCodegen(version);
        c.processOpts();
        assertEquals(RustCodegenBase.TEMPLATES, c.templateDir());
        assertEquals(RustCodegenBase.TEMPLATES, c.embeddedTemplateDir());
        List<String> templates = new java.util.ArrayList<>();
        templates.addAll(c.apiTemplateFiles().keySet());
        templates.addAll(c.modelTemplateFiles().keySet());
        templates.addAll(c.apiDocTemplateFiles().keySet());
        templates.addAll(c.modelDocTemplateFiles().keySet());
        c.supportingFiles().forEach(f -> templates.add(f.getTemplateFile()));
        templates.add("partial_header.mustache");
        templates.add("multipart_helper.mustache");
        templates.add("sse.mustache");
        for (String template : templates) {
            assertTrue(TimvwRustCodegen.class.getResource("/" + RustCodegenBase.TEMPLATES + "/" + template) != null,
                    template);
        }
    }

    @Test
    void isBuiltAgainstTheRunningOpenApiGenerator() {
        // ImplementationVersion comes from the openapi-generator jar's manifest, so this is the CI matrix check.
        assertEquals(org.openapitools.codegen.utils.ImplementationVersion.read(), TimvwRustCodegen.builtAgainst());
    }

    /**
     * Stock -g rust groups an operation's parameters into one struct for x-group-parameters; timvw-rust always passes
     * them as arguments, so an operation that asks for the struct fails generation instead of getting another API.
     */
    @Test
    void rejectsXGroupParameters(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path spec = dir.resolve("spec.yaml");
        java.nio.file.Files.writeString(spec, String.join("\n",
                "openapi: 3.0.3",
                "info: {title: t, version: '1'}",
                "paths:",
                "  /things:",
                "    get:",
                "      operationId: listThings",
                "      x-group-parameters: true",
                "      parameters:",
                "        - {name: limit, in: query, schema: {type: integer}}",
                "      responses:",
                "        '200': {description: ok}",
                ""));
        org.openapitools.codegen.ClientOptInput input = new org.openapitools.codegen.config.CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(spec.toString())
                .setOutputDir(dir.resolve("out").toString())
                .toClientOptInput();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new org.openapitools.codegen.DefaultGenerator().opts(input).generate());
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertTrue(String.valueOf(cause.getMessage()).contains("x-group-parameters"), String.valueOf(cause.getMessage()));
    }

    /** Local aliases so the test reads the same names the CLI user types. */
    private static final class RustClientCodegenConstants {
        static final String SUPPORT_ASYNC = TimvwRustCodegen.SUPPORT_ASYNC;
        static final String SUPPORT_MIDDLEWARE = TimvwRustCodegen.SUPPORT_MIDDLEWARE;
        static final String PREFER_UNSIGNED_INT = TimvwRustCodegen.PREFER_UNSIGNED_INT;
        static final String BEST_FIT_INT = TimvwRustCodegen.BEST_FIT_INT;
    }
}
