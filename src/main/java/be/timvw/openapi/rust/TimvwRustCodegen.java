package be.timvw.openapi.rust;

import com.samskivert.mustache.Mustache;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.InlineModelResolverAccess;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.languages.RustClientCodegen;
import org.openapitools.codegen.utils.ImplementationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code timvw-rust}: a Rust client generator built on top of the stock {@link RustClientCodegen}.
 *
 * <p>The generator renders what {@code -g rust} renders, with these differences (Milestone 1):</p>
 * <ul>
 *   <li>it accepts exactly one configuration; every option that would change the shape of the generated crate
 *       is pinned or rejected, so that the public API is fully determined by the spec and the generator version;</li>
 *   <li>{@code bonBuilders} (default on) derives {@code bon::Builder} on model structs and puts
 *       {@code #[bon::builder]} on every API function;</li>
 *   <li>{@code src/lib.rs} includes {@code README.md} as the crate-level documentation;</li>
 *   <li>bare URLs in doc comments are wrapped in {@code <...>}, which rustdoc requires;</li>
 *   <li>{@code parse_deep_object} returns a plain key/value pair for non-object values instead of panicking;</li>
 *   <li>{@code type: integer} with a float format is read as {@code type: number};</li>
 *   <li>{@code titleNaming=false} keeps schema titles out of type naming (task 2.0): openapi-generator 7.2x merges
 *       titled component schemas of the same shape, replaces inline schemas by a titled twin and names inline array
 *       items after the array's title; with the option off, names stay derived from schema keys and property paths.
 *       The generator runs the inline-model resolver itself so that the titles can be removed first;</li>
 *   <li>a {@code oneOf} whose branches render to the same variant (two primitive branches with the same title) keeps
 *       one variant, and an {@code anyOf} with no typed branch ({@code anyOf: [{}, null]}) is {@code serde_json::Value}
 *       instead of a reference to a model that is never written (Milestone 2.1);</li>
 *   <li>a required nullable model field is built with {@code name.map(Box::new)} in {@code new()} rather than an
 *       if-let/else expression (Milestone 2.2).</li>
 * </ul>
 *
 * <p>The stock templates differ per openapi-generator minor version, so the overrides are kept per version under
 * {@code timvw-rust/og<major.minor>.0/} and chosen at run time; files that are not overridden fall back to the stock
 * {@code rust} templates of the running openapi-generator. {@code tools/sync-templates.py} regenerates the sets.</p>
 */
public class TimvwRustCodegen extends RustClientCodegen {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimvwRustCodegen.class);

    public static final String GENERATOR_NAME = "timvw-rust";

    /** Our own option: derive {@code bon::Builder} on model structs and {@code #[bon::builder]} on API functions. */
    public static final String BON_BUILDERS = "bonBuilders";

    /** Our own option: let schema titles name, merge and replace types (stock openapi-generator 7.2x behaviour). */
    public static final String TITLE_NAMING = "titleNaming";

    /** Mustache lambda that templates wrap around doc-comment text. */
    static final String RUSTDOC_LAMBDA = "rustdoc";

    static final String STOCK_TEMPLATE_DIR = "rust";
    static final String TEMPLATE_ROOT = "timvw-rust";

    /** openapi-generator major.minor version to the template set compiled for it. */
    static final Map<String, String> TEMPLATE_SETS = templateSets();

    /** Options that must have exactly one value. Applied as defaults when unset. */
    static final Map<String, String> SUPPORTED_VALUES = supportedValues();

    /** Options that must not be set at all, whatever the value. */
    static final List<String> REJECTED_OPTIONS = Arrays.asList(
            AVOID_BOXED_MODELS,
            MOCKALL,
            TOP_LEVEL_API_CLIENT,
            CodegenConstants.WITH_AWSV4_SIGNATURE_COMMENT,
            SUPPORT_TOKEN_SOURCE,
            BON_BUILDER,
            CodegenConstants.TEMPLATE_DIR);

    private final String openApiGeneratorVersion;
    private boolean titleNaming = true;

    private static Map<String, String> templateSets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("7.15", "og7.15.0");
        m.put("7.25", "og7.25.0");
        return m;
    }

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
        this(ImplementationVersion.read());
    }

    /** @param openApiGeneratorVersion the openapi-generator version the generator runs in, e.g. {@code 7.25.0} */
    TimvwRustCodegen(String openApiGeneratorVersion) {
        super();
        this.openApiGeneratorVersion = openApiGeneratorVersion;
        outputFolder = "generated-code/" + GENERATOR_NAME;

        // Our overrides first, then the stock rust templates of the running openapi-generator.
        embeddedTemplateDir = STOCK_TEMPLATE_DIR;
        String set = templateSetFor(openApiGeneratorVersion);
        templateDir = set == null ? STOCK_TEMPLATE_DIR : TEMPLATE_ROOT + "/" + set;

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
        cliOptions.add(CliOption.newBoolean(BON_BUILDERS,
                "Derive bon::Builder on model structs and put #[bon::builder] on API functions", true));
        cliOptions.add(CliOption.newBoolean(TITLE_NAMING,
                "Let schema titles take part in type naming: titled component schemas of the same shape are merged, "
                        + "inline schemas matching a titled component are replaced by it, and an inline array's title "
                        + "names its item type. Set to false to derive names from schema keys and property paths only",
                true));
    }

    /** The template set for an openapi-generator version, or {@code null} when none was compiled for it. */
    static String templateSetFor(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        return TEMPLATE_SETS.get(parts[0] + "." + parts[1]);
    }

    @Override
    public String getName() {
        return GENERATOR_NAME;
    }

    /** The model name the stock generator gives a composed schema without typed branches ({@code anyOf<>}). */
    static final String UNTYPED_COMPOSED = "AnyOfLessThanGreaterThan";
    static final String UNTYPED_COMPOSED_TYPE = "models::" + UNTYPED_COMPOSED;
    static final String JSON_VALUE = "serde_json::Value";

    /**
     * Drops {@code oneOf} variants that duplicate an earlier one in name and type. The stock generator names a
     * primitive branch after its title, so {@code oneOf: [{type: string, title: Text}, {type: string, enum: [..],
     * title: Text}]} renders two {@code Text(String)} variants, which does not compile.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public CodegenModel fromModel(String name, Schema model) {
        CodegenModel mdl = super.fromModel(name, model);
        if (mdl.getComposedSchemas() != null && mdl.getComposedSchemas().getOneOf() != null) {
            List<CodegenProperty> kept = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (CodegenProperty variant : mdl.getComposedSchemas().getOneOf()) {
                if (seen.add(variant.name + "(" + variant.dataType + ")")) {
                    kept.add(variant);
                } else {
                    LOGGER.warn("{}: {}: dropped duplicate oneOf variant {}({})", GENERATOR_NAME, mdl.classname,
                            variant.name, variant.dataType);
                }
            }
            if (kept.size() != mdl.getComposedSchemas().getOneOf().size()) {
                mdl.getComposedSchemas().setOneOf(kept);
            }
        }
        return mdl;
    }

    /**
     * Types a property whose schema is a composed schema without typed branches ({@code anyOf: [{}, null]}) as
     * {@code serde_json::Value}. The stock generator derives the model name {@code AnyOfLessThanGreaterThan} from
     * {@code anyOf<>} and references it, but never writes it. The property keeps its other flags, so a required
     * nullable field stays {@code Option<Box<serde_json::Value>>} as before. Every property list of the model holds
     * its own copies, so all of them are visited.
     */
    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        objs = super.postProcessModels(objs);
        for (ModelMap model : objs.getModels()) {
            CodegenModel cm = model.getModel();
            for (List<CodegenProperty> vars : Arrays.asList(cm.vars, cm.allVars, cm.requiredVars, cm.optionalVars,
                    cm.readOnlyVars, cm.readWriteVars, cm.parentVars, cm.nonNullableVars)) {
                if (vars == null) {
                    continue;
                }
                for (CodegenProperty property : vars) {
                    if (retypeUntypedComposed(property) && vars == cm.vars) {
                        LOGGER.warn("{}: {}.{}: untyped anyOf is rendered as {}", GENERATOR_NAME, cm.classname,
                                property.baseName, JSON_VALUE);
                    }
                }
            }
        }
        return objs;
    }

    private static boolean retypeUntypedComposed(CodegenProperty property) {
        if (property.dataType == null || !property.dataType.contains(UNTYPED_COMPOSED_TYPE)) {
            return false;
        }
        property.dataType = property.dataType.replace(UNTYPED_COMPOSED_TYPE, JSON_VALUE);
        if (property.datatypeWithEnum != null) {
            property.datatypeWithEnum = property.datatypeWithEnum.replace(UNTYPED_COMPOSED_TYPE, JSON_VALUE);
        }
        if (UNTYPED_COMPOSED.equals(property.complexType)) {
            property.complexType = null;
        }
        return true;
    }

    /**
     * Validates the configuration before delegating to the stock generator.
     *
     * @throws IllegalArgumentException naming the offending option and the supported value
     */
    @Override
    public void processOpts() {
        if (templateSetFor(openApiGeneratorVersion) == null) {
            throw new IllegalArgumentException(String.format(
                    "%s: openapi-generator %s is not supported; templates exist for %s",
                    GENERATOR_NAME, openApiGeneratorVersion, TEMPLATE_SETS.keySet()));
        }

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

        boolean bonBuilders = !additionalProperties.containsKey(BON_BUILDERS)
                || convertPropertyToBooleanAndWriteBack(BON_BUILDERS);
        additionalProperties.put(BON_BUILDERS, bonBuilders);

        titleNaming = !additionalProperties.containsKey(TITLE_NAMING)
                || convertPropertyToBooleanAndWriteBack(TITLE_NAMING);
        additionalProperties.put(TITLE_NAMING, titleNaming);

        additionalProperties.put(RUSTDOC_LAMBDA,
                (Mustache.Lambda) (fragment, out) -> out.write(RustDoc.wrapBareUrls(fragment.execute())));
    }

    /**
     * The inline-model resolver is run by {@link #preprocessOpenAPI} instead of by the generator, so that the spec can
     * be adjusted before inline schemas are named and merged.
     */
    @Override
    public boolean getUseInlineModelResolver() {
        return false;
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        if (!titleNaming) {
            List<String> stripped = TitleNaming.stripNamingTitles(openAPI);
            LOGGER.info("{}: {}=false, removed the title from {} component and array schemas before naming",
                    GENERATOR_NAME, TITLE_NAMING, stripped.size());
        }
        if (super.getUseInlineModelResolver()) {
            InlineModelResolverAccess.flatten(openAPI, inlineSchemaNameMapping(), inlineSchemaOption());
        }
        super.preprocessOpenAPI(openAPI);
        for (String where : SpecNormalizer.integerWithFloatFormatToNumber(openAPI)) {
            LOGGER.warn("{}: read 'type: integer' with a float format as 'type: number' at {}", GENERATOR_NAME, where);
        }
    }
}
