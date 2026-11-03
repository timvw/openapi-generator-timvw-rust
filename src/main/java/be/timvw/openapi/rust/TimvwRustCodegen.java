package be.timvw.openapi.rust;

import com.samskivert.mustache.Mustache;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;
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
 *       if-let/else expression (Milestone 2.2);</li>
 *   <li>spec rules run before naming (Milestone 2.3, see {@link SpecRules}): generic repairs of constructs the stock
 *       generator mishandles, and the options {@code webhookModule}, {@code webhookModuleReexport},
 *       {@code stringIdSchemas}, {@code stringIdProperties}, {@code stringProperties}, {@code jsonValueSchemas},
 *       {@code deepObjectQueryParameters} and {@code resetQueryParameterStyle} for decisions a client takes about its spec;</li>
 *   <li>{@code boxResponseError}, {@code apiLintAllows} and {@code userAgentFromPackage} shape the API modules
 *       (Milestone 2.6);</li>
 *   <li>{@code multipartHelper} sends multipart bodies through a generated helper module (see {@link MultipartRules}),
 *       and {@code fileProperties} declares properties to be files (Milestone 2.5);</li>
 *   <li>{@code legacyModelRewrites} runs transitional Java ports of openai-client-base's model scripts over the
 *       rendered crate (Milestone 2.4, see {@code be.timvw.openapi.rust.legacy});</li>
 *   <li>{@code boxOnlyCycles} boxes a model reference only where the model graph needs it to break a cycle
 *       (Milestone 3.1, see {@link BoxRules}).</li>
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

    /** Tag given to webhook operations, so they are generated into their own API module. */
    public static final String WEBHOOK_MODULE = "webhookModule";
    /** {@code default_api} re-exports the webhook module (for clients that had webhooks there before). */
    public static final String WEBHOOK_MODULE_REEXPORT = "webhookModuleReexport";
    /** Component schemas rendered as {@code String}. */
    public static final String STRING_ID_SCHEMAS = "stringIdSchemas";
    /** Property names rendered as {@code String} wherever an id schema or a union of them types them. */
    public static final String STRING_ID_PROPERTIES = "stringIdProperties";
    /** {@code Schema.property} entries rendered as a bare {@code String}. */
    public static final String STRING_PROPERTIES = "stringProperties";
    /** Component schemas rendered as {@code serde_json::Value}. */
    public static final String JSON_VALUE_SCHEMAS = "jsonValueSchemas";
    /** {@code <path>?<name>} query parameters kept as objects sent in deepObject style. */
    public static final String DEEP_OBJECT_QUERY_PARAMETERS = "deepObjectQueryParameters";
    /** Query parameters get the default style (form, exploded), whatever the spec says. */
    public static final String RESET_QUERY_PARAMETER_STYLE = "resetQueryParameterStyle";
    /** Output-relative path the spec is written to after the spec rules ran (JSON; for scripts and debugging). */
    public static final String PREPROCESSED_SPEC_FILE = "preprocessedSpecFile";
    /** {@code Error::ResponseError} holds {@code Box<ResponseContent<T>>} (clippy {@code result_large_err}). */
    public static final String BOX_RESPONSE_ERROR = "boxResponseError";
    /** Lints allowed at the top of every API module and {@code configuration.rs}, as {@code #![allow(..)]}. */
    public static final String API_LINT_ALLOWS = "apiLintAllows";
    /** The default user agent is {@code <packageName>/<crate version>}. */
    public static final String USER_AGENT_FROM_PACKAGE = "userAgentFromPackage";
    /** Run the transitional model rewrites of Milestone 2.4 over the rendered crate (openai-client-base). */
    public static final String LEGACY_MODEL_REWRITES = "legacyModelRewrites";
    /** Multipart bodies go through a generated {@code src/apis/multipart_helper.rs} (Milestone 2.5). */
    public static final String MULTIPART_HELPER = "multipartHelper";
    /** {@code Schema.property} entries rendered as a file ({@code type: string, format: binary}). */
    public static final String FILE_PROPERTIES = "fileProperties";
    /** Box a model reference only where it closes a cycle of the model graph (Milestone 3.1, see {@link BoxRules}). */
    public static final String BOX_ONLY_CYCLES = "boxOnlyCycles";
    /** Key under which the api template finds whether its module uses the multipart helper. */
    static final String MULTIPART_HELPER_IMPORT = "multipartHelperImport";
    /** Set on a parameter whose dataType already carries {@code models::}. */
    static final String TYPE_QUALIFIED = "x-rust-type-qualified";
    /** Key under which the api template finds the module {@code default_api} re-exports. */
    static final String WEBHOOK_REEXPORT_KEY = "webhookReexport";
    /** Key under which the api mod template finds the webhook module files to declare. */
    static final String WEBHOOK_MODULES_KEY = "webhookModules";

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
    private String webhookModule;
    private boolean webhookModuleReexport;
    private List<String> stringIdSchemas = new ArrayList<>();
    private List<String> stringIdProperties = new ArrayList<>();
    private List<String> stringProperties = new ArrayList<>();
    private List<String> jsonValueSchemas = new ArrayList<>();
    private List<String> deepObjectQueryParameters = new ArrayList<>();
    private String preprocessedSpecFile;
    private boolean resetQueryParameterStyle;
    private boolean multipartHelper;
    private boolean legacyModelRewrites;
    private String preprocessedSpecJson;
    private List<String> fileProperties = new ArrayList<>();
    private boolean boxOnlyCycles;
    private Map<String, Integer> boxComponents = Map.of();

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
        cliOptions.add(CliOption.newString(WEBHOOK_MODULE,
                "Tag given to every webhook operation, so that webhooks are generated into their own API module "
                        + "instead of default_api (where a webhook replaces the untagged path operations). "
                        + "A path operation that already carries the tag fails generation"));
        cliOptions.add(CliOption.newBoolean(WEBHOOK_MODULE_REEXPORT,
                "With webhookModule: default_api re-exports the webhook module, keeping the paths of clients that "
                        + "had webhooks there", false));
        cliOptions.add(CliOption.newString(STRING_ID_SCHEMAS,
                "Component schemas rendered as String (model and voice id unions, say). Entries are separated by "
                        + "'|', ';' or whitespace on the command line, or given as a JSON list in a config file"));
        cliOptions.add(CliOption.newString(STRING_ID_PROPERTIES,
                "Property names (name or name=fallback_description) rendered as String wherever they reference a "
                        + "stringIdSchemas schema or are a union, directly or through allOf"));
        cliOptions.add(CliOption.newString(STRING_PROPERTIES,
                "Schema.property entries rendered as a bare String, directly or in an allOf component"));
        cliOptions.add(CliOption.newString(JSON_VALUE_SCHEMAS,
                "Component schemas rendered as a free-form object (serde_json::Value)"));
        cliOptions.add(CliOption.newBoolean(RESET_QUERY_PARAMETER_STYLE,
                "Give every query parameter not listed in deepObjectQueryParameters the default style (form, "
                        + "exploded), so arrays are sent as repeated parameters whatever the spec says", false));
        cliOptions.add(CliOption.newBoolean(LEGACY_MODEL_REWRITES,
                "Run the transitional rewrites that reproduce openai-client-base's scripted model output (union "
                        + "enums, Default and Display impls, imports); they go in Milestone 3", false));
        cliOptions.add(CliOption.newBoolean(MULTIPART_HELPER,
                "Send multipart bodies through a generated src/apis/multipart_helper.rs: files are read into a part "
                        + "named after the file, optional arrays send one part per item and optional objects one JSON "
                        + "part, and an encoding contentType of a scalar part is applied", false));
        cliOptions.add(CliOption.newString(FILE_PROPERTIES,
                "Schema.property entries rendered as a file (type: string, format: binary)"));
        cliOptions.add(CliOption.newBoolean(BOX_RESPONSE_ERROR,
                "Error::ResponseError holds Box<ResponseContent<T>>, keeping every Result small (clippy "
                        + "result_large_err)", false));
        cliOptions.add(CliOption.newString(API_LINT_ALLOWS,
                "Lints allowed at the top of every API module and configuration.rs, written as "
                        + "#![allow(<value>)], e.g. 'clippy::needless_return, clippy::into_iter_on_ref'"));
        cliOptions.add(CliOption.newBoolean(USER_AGENT_FROM_PACKAGE,
                "Default user agent <packageName>/<crate version> (CARGO_PKG_VERSION) instead of "
                        + "OpenAPI-Generator/<spec version>/rust; httpUserAgent still wins", false));
        cliOptions.add(CliOption.newString(PREPROCESSED_SPEC_FILE,
                "Write the spec as it stands after the spec rules (before inline schemas are named) to this "
                        + "output-relative path, as JSON"));
        cliOptions.add(CliOption.newString(DEEP_OBJECT_QUERY_PARAMETERS,
                "<path>?<name> query parameters kept as objects and sent in deepObject style; every other "
                        + "object-typed query parameter is sent as a string"));
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
    /** Marks every model property with {@link BoxRules#BOX}, which the model template reads. */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        objs = super.postProcessAllModels(objs);
        boxComponents = BoxRules.mark(objs, boxOnlyCycles);
        return objs;
    }

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

        Object module = additionalProperties.get(WEBHOOK_MODULE);
        webhookModule = module == null || String.valueOf(module).isEmpty() ? null : String.valueOf(module);
        webhookModuleReexport = additionalProperties.containsKey(WEBHOOK_MODULE_REEXPORT)
                && convertPropertyToBooleanAndWriteBack(WEBHOOK_MODULE_REEXPORT);
        if (webhookModuleReexport && webhookModule == null) {
            throw new IllegalArgumentException(String.format("%s: %s needs %s", GENERATOR_NAME,
                    WEBHOOK_MODULE_REEXPORT, WEBHOOK_MODULE));
        }
        stringIdSchemas = SpecRules.entries(additionalProperties.get(STRING_ID_SCHEMAS));
        stringIdProperties = SpecRules.entries(additionalProperties.get(STRING_ID_PROPERTIES));
        stringProperties = SpecRules.entries(additionalProperties.get(STRING_PROPERTIES));
        jsonValueSchemas = SpecRules.entries(additionalProperties.get(JSON_VALUE_SCHEMAS));
        deepObjectQueryParameters = SpecRules.entries(additionalProperties.get(DEEP_OBJECT_QUERY_PARAMETERS));
        resetQueryParameterStyle = additionalProperties.containsKey(RESET_QUERY_PARAMETER_STYLE)
                && convertPropertyToBooleanAndWriteBack(RESET_QUERY_PARAMETER_STYLE);
        legacyModelRewrites = additionalProperties.containsKey(LEGACY_MODEL_REWRITES)
                && convertPropertyToBooleanAndWriteBack(LEGACY_MODEL_REWRITES);
        multipartHelper = additionalProperties.containsKey(MULTIPART_HELPER)
                && convertPropertyToBooleanAndWriteBack(MULTIPART_HELPER);
        additionalProperties.put(MULTIPART_HELPER, multipartHelper);
        if (multipartHelper) {
            supportingFiles.add(new SupportingFile("multipart_helper.mustache", apiFolder, "multipart_helper.rs"));
        }
        fileProperties = SpecRules.entries(additionalProperties.get(FILE_PROPERTIES));
        boxOnlyCycles = additionalProperties.containsKey(BOX_ONLY_CYCLES)
                && convertPropertyToBooleanAndWriteBack(BOX_ONLY_CYCLES);
        for (String flag : Arrays.asList(BOX_RESPONSE_ERROR, USER_AGENT_FROM_PACKAGE)) {
            additionalProperties.put(flag, additionalProperties.containsKey(flag)
                    && convertPropertyToBooleanAndWriteBack(flag));
        }
        Object allows = additionalProperties.get(API_LINT_ALLOWS);
        if (allows != null && String.valueOf(allows).trim().isEmpty()) {
            additionalProperties.remove(API_LINT_ALLOWS);
        }
        Object dump = additionalProperties.get(PREPROCESSED_SPEC_FILE);
        preprocessedSpecFile = dump == null || String.valueOf(dump).isEmpty() ? null : String.valueOf(dump);

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

    /**
     * The OpenAPI normalizer is run by {@link #preprocessOpenAPI} as well, after the spec rules: for a 3.1 document it
     * rewrites a {@code $ref} with sibling keywords into an {@code allOf} wrapper, which the rules must see in its
     * original form (as the client's scripts did, running on the file).
     */
    @Override
    public boolean getUseOpenapiNormalizer() {
        return false;
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        // The spec rules edit the document as a file would be edited, so they run on a non-resolving parse of the
        // input file: the resolving parse the CLI did shares one Schema instance between identical subtrees (two
        // components with the same inline union hold the same object) and drops `required` names that are not
        // properties of the same object (names inherited through allOf). The result is then parsed again as the
        // CLI parses its input and takes the place of the document the generator holds.
        OpenAPI working = getInputSpec() != null ? parseFile(getInputSpec(), false) : reparse(openAPI, false);
        applySpecRules(working);
        String json = serialize(working);
        preprocessedSpecJson = json;
        if (preprocessedSpecFile != null) {
            java.nio.file.Path target = java.nio.file.Paths.get(outputFolder, preprocessedSpecFile);
            try {
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, json);
                LOGGER.info("{}: wrote the preprocessed spec to {}", GENERATOR_NAME, target);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot write " + target, e);
            }
        }
        copyInto(openAPI, parse(json, true));
        if (super.getUseOpenapiNormalizer()) {
            Map<String, String> rules = new LinkedHashMap<>(openapiNormalizer());
            if (openAPI.getOpenapi() != null && new org.openapitools.codegen.utils.SemVer(openAPI.getOpenapi()).atLeast("3.1.0")) {
                rules.put("NORMALIZE_31SPEC", "true");
            }
            try {
                org.openapitools.codegen.OpenAPINormalizerAccess.normalize(openAPI, rules);
            } catch (Exception e) {
                LOGGER.error("An exception occurred in OpenAPI Normalizer. Please report the issue via "
                        + "https://github.com/openapitools/openapi-generator/issues/new/: ", e);
            }
        }
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

    private static String serialize(OpenAPI openAPI) {
        boolean v31 = openAPI.getOpenapi() != null
                && new org.openapitools.codegen.utils.SemVer(openAPI.getOpenapi()).atLeast("3.1.0");
        return v31 ? io.swagger.v3.core.util.Json31.pretty(openAPI) : io.swagger.v3.core.util.Json.pretty(openAPI);
    }

    private static OpenAPI reparse(OpenAPI openAPI, boolean resolve) {
        return parse(serialize(openAPI), resolve);
    }

    /** Parses a document the way the CLI parses its input ({@code resolve}), or as an unshared tree (no resolve). */
    private static OpenAPI parse(String json, boolean resolve) {
        return check(new io.swagger.parser.OpenAPIParser().readContents(json, null, parseOptions(resolve)),
                "the preprocessed spec");
    }

    private static OpenAPI parseFile(String location, boolean resolve) {
        return check(new io.swagger.parser.OpenAPIParser().readLocation(location, null, parseOptions(resolve)),
                location);
    }

    private static io.swagger.v3.parser.core.models.ParseOptions parseOptions(boolean resolve) {
        io.swagger.v3.parser.core.models.ParseOptions options = new io.swagger.v3.parser.core.models.ParseOptions();
        options.setResolve(resolve);
        return options;
    }

    private static OpenAPI check(io.swagger.v3.parser.core.models.SwaggerParseResult result, String what) {
        if (result.getOpenAPI() == null) {
            throw new IllegalStateException(GENERATOR_NAME + ": cannot parse " + what + ": " + result.getMessages());
        }
        return result.getOpenAPI();
    }

    /** Moves the content of {@code from} into {@code into}, which the generator holds a reference to. */
    private static void copyInto(OpenAPI into, OpenAPI from) {
        into.setOpenapi(from.getOpenapi());
        into.setInfo(from.getInfo());
        into.setExternalDocs(from.getExternalDocs());
        into.setServers(from.getServers());
        into.setSecurity(from.getSecurity());
        into.setTags(from.getTags());
        into.setPaths(from.getPaths());
        into.setComponents(from.getComponents());
        into.setExtensions(from.getExtensions());
        into.setWebhooks(from.getWebhooks());
        into.setJsonSchemaDialect(from.getJsonSchemaDialect());
        into.setSpecVersion(from.getSpecVersion());
    }

    /**
     * Milestone 2.3: the spec decisions named by the options, then the generic repairs, in the order the scripts ran.
     * Runs on the parsed document before the normalizer and the inline-model resolver.
     */
    private void applySpecRules(OpenAPI openAPI) {
        java.util.function.Consumer<String> log = msg -> LOGGER.info("{}: {}", GENERATOR_NAME, msg);
        if (webhookModule != null) {
            int routed = SpecRules.routeWebhooks(openAPI, webhookModule);
            LOGGER.info("{}: {}: routed {} webhook operations to tag {}", GENERATOR_NAME, WEBHOOK_MODULE, routed,
                    webhookModule);
        }
        SpecRules.stringSchemas(openAPI, stringIdSchemas, log);
        SpecRules.stringIdProperties(openAPI, stringIdProperties, SpecRules.names(stringIdSchemas), log);
        int inlined = SpecRules.inlineStringRefsInUnions(openAPI);
        int pruned = SpecRules.pruneGeneratorHostileKeywords(openAPI);
        List<String> retitled = SpecRules.disambiguateInlineTitles(openAPI, this::toModelName);
        for (String r : retitled) {
            LOGGER.warn("{}: inline title collides with a component schema, renamed: {}", GENERATOR_NAME, r);
        }
        int stripped = SpecRules.stripRefSiblings(openAPI);
        int collapsed = SpecRules.collapseRequiredOnlyUnions(openAPI);
        int titled = SpecRules.titleUnnamedUnionVariants(openAPI);
        List<String> hoisted = SpecRules.hoistInlineUnions(openAPI);
        for (String h : hoisted) {
            LOGGER.info("{}: hoisted inline union {}", GENERATOR_NAME, h);
        }
        SpecRules.stringProperties(openAPI, stringProperties, log);
        SpecRules.jsonValueSchemas(openAPI, jsonValueSchemas, log);
        SpecRules.fileProperties(openAPI, fileProperties, log);
        // A hoisted union may carry a stringIdSchemas name: apply the string rendering to it as well.
        SpecRules.stringSchemas(openAPI, stringIdSchemas, log);
        List<String> renamed = SpecRules.normalizeHyphenatedSchemaNames(openAPI);
        for (String r : renamed) {
            LOGGER.info("{}: renamed hyphenated schema {}", GENERATOR_NAME, r);
        }
        List<String> params = SpecRules.simplifyObjectQueryParameters(openAPI, deepObjectQueryParameters,
                resetQueryParameterStyle);
        for (String q : params) {
            LOGGER.info("{}: query parameter {}", GENERATOR_NAME, q);
        }
        LOGGER.info("{}: spec rules: {} string refs inlined in unions, {} default/propertyNames removed, {} $ref "
                        + "siblings stripped, {} validation-only unions collapsed, {} union variants titled, {} inline "
                        + "unions hoisted, {} schemas renamed, {} query parameters changed", GENERATOR_NAME, inlined,
                pruned, stripped, collapsed, titled, hoisted.size(), renamed.size(), params.size());
    }

    /**
     * {@code src/apis/mod.rs} declares the webhook module: the stock template lists only the path APIs
     * ({@code apiInfo.apis}), while webhooks are bundled apart under {@code webhooks}.
     */
    @Override
    public Map<String, Object> postProcessSupportingFileData(Map<String, Object> objs) {
        objs = super.postProcessSupportingFileData(objs);
        if (webhookModule != null && objs.get("webhooks") instanceof List && !((List<?>) objs.get("webhooks")).isEmpty()) {
            List<?> webhooks = (List<?>) objs.get("webhooks");
            if (webhooks.size() > 1) {
                LOGGER.warn("{}: {} webhook API modules generated; only {} is declared in mod.rs (webhooks with "
                        + "their own tags are not supported)", GENERATOR_NAME, webhooks.size(), webhookModule);
            }
            objs.put(WEBHOOK_MODULES_KEY, Arrays.asList(toApiFilename(webhookModule)));
        }
        return objs;
    }

    /**
     * Runs the transitional model rewrites ({@code legacyModelRewrites}) over the written crate, then the stock
     * post-processing.
     */
    @Override
    public void postProcess() {
        if (legacyModelRewrites) {
            try {
                java.util.Map<String, Object> raw = be.timvw.openapi.rust.legacy.LegacyContext.load(
                        java.nio.file.Paths.get(getInputSpec()));
                java.util.Map<String, Object> preprocessed = be.timvw.openapi.rust.legacy.LegacyContext.parseJson(
                        preprocessedSpecJson);
                be.timvw.openapi.rust.legacy.LegacyModelRewrites.run(
                        new be.timvw.openapi.rust.legacy.LegacyContext(java.nio.file.Paths.get(outputFolder), raw,
                                preprocessed, boxPolicy()),
                        msg -> LOGGER.info("{}: {}", GENERATOR_NAME, msg));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(GENERATOR_NAME + ": legacy model rewrites failed", e);
            }
        }
        super.postProcess();
    }

    /** What the legacy rewrites box: every model payload, or with {@code boxOnlyCycles} only those on a cycle. */
    private be.timvw.openapi.rust.legacy.BoxPolicy boxPolicy() {
        if (!boxOnlyCycles) {
            return be.timvw.openapi.rust.legacy.BoxPolicy.ALWAYS;
        }
        Map<String, Integer> components = boxComponents;
        return (holder, target) -> BoxRules.inCycle(components, holder, target);
    }

    /** {@code default_api} re-exports the webhook module when {@code webhookModuleReexport} is set. */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        objs = super.postProcessOperationsWithModels(objs, allModels);
        boolean helperUsed = false;
        if (objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            for (CodegenOperation op : objs.getOperations().getOperation()) {
                for (List<CodegenParameter> params : Arrays.asList(op.allParams, op.formParams, op.bodyParams,
                        op.queryParams, op.pathParams, op.headerParams, op.requiredParams, op.optionalParams)) {
                    if (params == null) {
                        continue;
                    }
                    for (CodegenParameter p : params) {
                        if (p.dataType != null && p.dataType.startsWith("models::")) {
                            p.vendorExtensions.put(TYPE_QUALIFIED, true);
                        }
                    }
                }
                if (multipartHelper && op.isMultipart && op.formParams != null && !op.formParams.isEmpty()) {
                    MultipartRules.classify(openAPI, op,
                            msg -> LOGGER.warn("{}: multipart: {}", GENERATOR_NAME, msg));
                    for (CodegenParameter p : op.formParams) {
                        if (p.isFile || !p.vendorExtensions.containsKey(MultipartRules.STOCK)) {
                            helperUsed = true;
                        }
                    }
                }
            }
        }
        if (helperUsed) {
            objs.put(MULTIPART_HELPER_IMPORT, true);
        }
        if (webhookModuleReexport && objs.getOperations() != null
                && toApiName("default").equals(objs.getOperations().getClassname())) {
            objs.put(WEBHOOK_REEXPORT_KEY, toApiFilename(webhookModule));
        }
        return objs;
    }
}
