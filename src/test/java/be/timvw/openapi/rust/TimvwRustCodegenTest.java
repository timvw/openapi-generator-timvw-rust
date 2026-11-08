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
    void appliesTheFixedValues() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(CodegenConstants.PACKAGE_NAME, "petstore");
        c.processOpts();

        Map<String, Object> props = c.additionalProperties();
        assertEquals("reqwest", c.getLibrary());
        assertEquals("petstore", props.get(CodegenConstants.PACKAGE_NAME));
        assertEquals("true", String.valueOf(props.get("reqwest")));
        assertEquals(true, props.get(RustClientCodegenConstants.SUPPORT_ASYNC));
        assertEquals(true, props.get(RustClientCodegenConstants.SUPPORT_MIDDLEWARE));
        assertEquals(false, props.get(CodegenConstants.USE_SINGLE_REQUEST_PARAMETER));
        assertEquals(false, props.get(RustClientCodegenConstants.PREFER_UNSIGNED_INT));
        assertEquals(false, props.get(RustClientCodegenConstants.BEST_FIT_INT));
        assertEquals("", props.get(CodegenConstants.ENUM_NAME_SUFFIX));
        assertEquals(true, props.get(CodegenConstants.HIDE_GENERATION_TIMESTAMP));
        assertTrue(c.isHideGenerationTimestamp());
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

    @Test
    void rejectsBooleanValuesOfFixedOptionsToo() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(RustClientCodegenConstants.SUPPORT_ASYNC, Boolean.TRUE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'supportAsync' is not configurable"), e.getMessage());
    }

    @Test
    void rejectsAnotherLibrarySetThroughTheCliSetter() {
        TimvwRustCodegen c = codegen();
        c.setLibrary("hyper");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'library'"), e.getMessage());
        assertTrue(e.getMessage().contains("reqwest"), e.getMessage());
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
        TimvwRustCodegen empty = codegen();
        empty.additionalProperties().put(TimvwRustCodegen.JSON_VALUE_SCHEMAS, List.of());
        empty.processOpts();
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

    @ParameterizedTest(name = "openapi-generator {0} uses {1}")
    @CsvSource({
            "7.27.0,og7.27.0",
            "7.27.1,og7.27.0",
    })
    void picksTheTemplateSetOfTheRunningOpenApiGenerator(String version, String set) {
        TimvwRustCodegen c = new TimvwRustCodegen(version);
        assertEquals("timvw-rust/" + set, c.templateDir());
        assertEquals("rust", c.embeddedTemplateDir());
        c.processOpts();
        assertEquals("timvw-rust/" + set, c.templateDir());
    }

    @ParameterizedTest(name = "openapi-generator {0} is rejected")
    @ValueSource(strings = {"7.14.0", "7.15.0", "7.25.0", "7.26.0", "8.0.0", "unset"})
    void failsOnAnOpenApiGeneratorWithoutTemplates(String version) {
        TimvwRustCodegen c = new TimvwRustCodegen(version);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains(version), e.getMessage());
        assertTrue(e.getMessage().contains("7.27"), e.getMessage());
    }

    @Test
    void theRunningOpenApiGeneratorIsSupported() {
        // ImplementationVersion comes from the openapi-generator jar's manifest, so this is the CI matrix check.
        assertTrue(TimvwRustCodegen.templateSetFor(org.openapitools.codegen.utils.ImplementationVersion.read()) != null,
                org.openapitools.codegen.utils.ImplementationVersion.read());
    }

    /** Local aliases so the test reads the same names the CLI user types. */
    private static final class RustClientCodegenConstants {
        static final String SUPPORT_ASYNC = TimvwRustCodegen.SUPPORT_ASYNC;
        static final String SUPPORT_MIDDLEWARE = TimvwRustCodegen.SUPPORT_MIDDLEWARE;
        static final String PREFER_UNSIGNED_INT = TimvwRustCodegen.PREFER_UNSIGNED_INT;
        static final String BEST_FIT_INT = TimvwRustCodegen.BEST_FIT_INT;
    }
}
