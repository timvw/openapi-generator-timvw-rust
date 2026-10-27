package be.timvw.openapi.rust;

import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.languages.RustClientCodegen;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code timvw-rust}: a Rust client generator built on top of the stock {@link RustClientCodegen}.
 *
 * <p>Milestone 0 ("plumbing"): the generator renders exactly what {@code -g rust} renders, using the
 * stock templates, but it only accepts one configuration. Every option that would change the shape of the
 * generated crate is either pinned to a single supported value or rejected outright, so that the generated
 * public API is fully determined by the input spec and the generator version.</p>
 */
public class TimvwRustCodegen extends RustClientCodegen {

    public static final String GENERATOR_NAME = "timvw-rust";

    /** Options that must have exactly one value. Applied as defaults when unset. */
    static final Map<String, String> SUPPORTED_VALUES = supportedValues();

    /** Options that must not be set at all, whatever the value. */
    static final List<String> REJECTED_OPTIONS = Arrays.asList(
            AVOID_BOXED_MODELS,
            MOCKALL,
            TOP_LEVEL_API_CLIENT,
            CodegenConstants.WITH_AWSV4_SIGNATURE_COMMENT,
            SUPPORT_TOKEN_SOURCE,
            BON_BUILDER);

    private static Map<String, String> supportedValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CodegenConstants.LIBRARY, REQWEST_LIBRARY);
        m.put(SUPPORT_ASYNC, "true");
        m.put(SUPPORT_MIDDLEWARE, "true");
        m.put(CodegenConstants.USE_SINGLE_REQUEST_PARAMETER, "false");
        m.put(PREFER_UNSIGNED_INT, "false");
        m.put(BEST_FIT_INT, "false");
        m.put(CodegenConstants.ENUM_NAME_SUFFIX, "");
        m.put(CodegenConstants.HIDE_GENERATION_TIMESTAMP, "true");
        return m;
    }

    public TimvwRustCodegen() {
        super();
        // Reuse the stock rust templates bundled in openapi-generator; nothing is overridden yet.
        embeddedTemplateDir = templateDir = "rust";
        outputFolder = "generated-code/" + GENERATOR_NAME;

        // Advertise only what we accept: drop rejected options from `config-help`, and show the pinned
        // value as the default of every option that has exactly one supported value.
        cliOptions.removeIf(o -> REJECTED_OPTIONS.contains(o.getOpt()));
        for (CliOption o : cliOptions) {
            String supported = SUPPORTED_VALUES.get(o.getOpt());
            if (supported != null) {
                o.setDefault(supported);
                if (CodegenConstants.LIBRARY.equals(o.getOpt())) {
                    Map<String, String> onlyReqwest = new LinkedHashMap<>();
                    onlyReqwest.put(REQWEST_LIBRARY, supportedLibraries.get(REQWEST_LIBRARY));
                    o.setEnum(onlyReqwest);
                }
            }
        }
    }

    @Override
    public String getName() {
        return GENERATOR_NAME;
    }

    @Override
    public String getHelp() {
        return "Generates a Rust client library (reqwest, async, middleware) with a single supported configuration.";
    }

    /**
     * Validates the configuration before delegating to the stock generator.
     *
     * @throws IllegalArgumentException naming the offending option and the supported value
     */
    @Override
    public void processOpts() {
        // The CLI applies `library` through setLibrary() rather than additionalProperties, so look at both.
        if (!additionalProperties.containsKey(CodegenConstants.LIBRARY) && getLibrary() != null) {
            additionalProperties.put(CodegenConstants.LIBRARY, getLibrary());
        }

        for (String option : REJECTED_OPTIONS) {
            if (additionalProperties.containsKey(option)) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not supported; remove it (it was set to '%s')",
                        GENERATOR_NAME, option, additionalProperties.get(option)));
            }
        }

        for (Map.Entry<String, String> entry : SUPPORTED_VALUES.entrySet()) {
            String option = entry.getKey();
            String supported = entry.getValue();
            Object raw = additionalProperties.get(option);
            if (raw == null) {
                additionalProperties.put(option, supported);
                continue;
            }
            String actual = String.valueOf(raw);
            if (!supported.equals(actual)) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' must be '%s' (got '%s')",
                        GENERATOR_NAME, option, supported.isEmpty() ? "" : supported, actual));
            }
        }

        setLibrary(REQWEST_LIBRARY);
        super.processOpts();
    }
}
