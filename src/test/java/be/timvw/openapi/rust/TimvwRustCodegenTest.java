package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenConfigLoader;
import org.openapitools.codegen.CodegenConstants;

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
    void acceptsTheSupportedConfiguration() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(CodegenConstants.PACKAGE_NAME, "petstore");
        c.additionalProperties().put(CodegenConstants.LIBRARY, "reqwest");
        c.additionalProperties().put(RustClientCodegenConstants.SUPPORT_ASYNC, "true");
        c.additionalProperties().put(RustClientCodegenConstants.SUPPORT_MIDDLEWARE, "true");
        c.additionalProperties().put(CodegenConstants.USE_SINGLE_REQUEST_PARAMETER, "false");
        c.additionalProperties().put(RustClientCodegenConstants.PREFER_UNSIGNED_INT, "false");
        c.additionalProperties().put(RustClientCodegenConstants.BEST_FIT_INT, "false");
        c.additionalProperties().put(CodegenConstants.ENUM_NAME_SUFFIX, "");
        c.additionalProperties().put(CodegenConstants.HIDE_GENERATION_TIMESTAMP, "true");
        c.processOpts();

        assertEquals("reqwest", c.getLibrary());
        assertEquals(true, c.additionalProperties().get(RustClientCodegenConstants.SUPPORT_ASYNC));
        assertEquals(true, c.additionalProperties().get(RustClientCodegenConstants.SUPPORT_MIDDLEWARE));
        assertEquals("petstore", c.additionalProperties().get(CodegenConstants.PACKAGE_NAME));
    }

    @Test
    void acceptsBooleanValuesAsWellAsStrings() {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(RustClientCodegenConstants.SUPPORT_ASYNC, Boolean.TRUE);
        c.additionalProperties().put(CodegenConstants.USE_SINGLE_REQUEST_PARAMETER, Boolean.FALSE);
        c.processOpts();
        assertEquals(true, c.additionalProperties().get(RustClientCodegenConstants.SUPPORT_ASYNC));
    }

    @Test
    void appliesSupportedValuesAsDefaults() {
        TimvwRustCodegen c = codegen();
        c.processOpts();

        Map<String, Object> props = c.additionalProperties();
        assertEquals("reqwest", c.getLibrary());
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

    @ParameterizedTest(name = "{0}={1} is rejected")
    @CsvSource({
            "library,hyper",
            "library,reqwest-trait",
            "supportAsync,false",
            "supportMiddleware,false",
            "useSingleRequestParameter,true",
            "preferUnsignedInt,true",
            "bestFitInt,true",
            "enumNameSuffix,Enum",
            "hideGenerationTimestamp,false",
    })
    void rejectsUnsupportedValues(String option, String value) {
        TimvwRustCodegen c = codegen();
        c.additionalProperties().put(option, value);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains("'" + option + "'"), e.getMessage());
        assertTrue(e.getMessage().contains("must be"), e.getMessage());
        assertTrue(e.getMessage().contains(value), e.getMessage());
    }

    @Test
    void rejectsLibrarySetThroughTheCliSetter() {
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

    @Test
    void advertisesOnlySupportedOptions() {
        TimvwRustCodegen c = codegen();
        java.util.Set<String> opts = new java.util.HashSet<>();
        for (org.openapitools.codegen.CliOption o : c.cliOptions()) {
            opts.add(o.getOpt());
            String supported = TimvwRustCodegen.SUPPORTED_VALUES.get(o.getOpt());
            if (supported != null) {
                assertEquals(supported, o.getDefault(), o.getOpt());
            }
            if (CodegenConstants.LIBRARY.equals(o.getOpt())) {
                assertEquals(java.util.Set.of("reqwest"), o.getEnum().keySet());
            }
        }
        for (String rejected : TimvwRustCodegen.REJECTED_OPTIONS) {
            assertTrue(!opts.contains(rejected), rejected + " should not be advertised");
        }
        assertTrue(opts.containsAll(TimvwRustCodegen.SUPPORTED_VALUES.keySet()), opts.toString());
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
            "7.15.0,og7.15.0",
            "7.15.3,og7.15.0",
            "7.25.0,og7.25.0",
            "7.25.1,og7.25.0",
    })
    void picksTheTemplateSetOfTheRunningOpenApiGenerator(String version, String set) {
        TimvwRustCodegen c = new TimvwRustCodegen(version);
        assertEquals("timvw-rust/" + set, c.templateDir());
        assertEquals("rust", c.embeddedTemplateDir());
        c.processOpts();
        assertEquals("timvw-rust/" + set, c.templateDir());
    }

    @ParameterizedTest(name = "openapi-generator {0} is rejected")
    @ValueSource(strings = {"7.14.0", "7.26.0", "8.0.0", "unset"})
    void failsOnAnOpenApiGeneratorWithoutTemplates(String version) {
        TimvwRustCodegen c = new TimvwRustCodegen(version);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
        assertTrue(e.getMessage().contains(version), e.getMessage());
        assertTrue(e.getMessage().contains("7.25"), e.getMessage());
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
