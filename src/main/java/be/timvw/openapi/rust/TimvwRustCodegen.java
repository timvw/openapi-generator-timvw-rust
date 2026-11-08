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
import java.util.HashMap;
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
 *       {@code stringIdSchemas}, {@code stringIdProperties}, {@code stringProperties},
 *       {@code deepObjectQueryParameters} and {@code resetQueryParameterStyle} for decisions a client takes about its spec;</li>
 *   <li>{@code boxResponseError}, {@code apiLintAllows} and {@code userAgentFromPackage} shape the API modules
 *       (Milestone 2.6);</li>
 *   <li>{@code multipartHelper} sends multipart bodies through a generated helper module (see {@link MultipartRules}),
 *       and {@code fileProperties} declares properties to be files (Milestone 2.5);</li>
 *   <li>{@code use crate::models;} is written only into a model file that refers to another model, and an API
 *       module imports {@code ContentType}, {@code de::Error} and the file stream types only where an operation uses
 *       them (see {@link Imports}); this replaced the {@code legacyModelRewrites} text rewrites (Milestone 2.4), which
 *       are gone;</li>
 *   <li>{@code boxOnlyCycles} boxes a model reference only where the model graph needs it to break a cycle
 *       (Milestone 3.1, see {@link BoxRules});</li>
 *   <li>{@code defaultOnlyWhereValid} keeps {@code Default} only where its value is one the API would accept
 *       (Milestone 3.2, see {@link DefaultRules});</li>
 *   <li>{@code enumFromStr} gives closed enums {@code FromStr} and {@code TryFrom} (Milestone 3.2, see
 *       {@link EnumFromStr});</li>
 *   <li>{@code noStructDisplay} leaves structs without a JSON {@code Display} and has the API code serialize them
 *       explicitly (Milestone 3.2);</li>
 *   <li>{@code integersAsI64} renders an integer as {@code i64} unless the spec says {@code format: int32}
 *       (Milestone 3.4, see {@link #getSchemaType});</li>
 *   <li>{@code qualifiedInlineNames} names an inline schema after its title when that is unique and after where
 *       it stands otherwise, and fails generation when two different schemas would share a Rust model or file (Milestone 3.3, see {@link InlineNames} and
 *       {@link NameCollisions});</li>
 *   <li>{@code modelUnions} renders unions from the model (Milestone 3.3, see {@link UnionRules});</li>
 *   <li>{@code unionDispatch} makes a union with a tag decode by that tag (Milestone 3.3, see
 *       {@link UnionRules#dispatch});</li>
 *   <li>{@code openEnums} gives what a response can hold a catch-all for values the spec does not list (Milestone 3.5,
 *       see {@link OpenEnums});</li>
 *   <li>{@code typedEventStreams} renders a method returning a typed stream for a {@code text/event-stream} response, and
 *       {@code contentTypeMethods} one method per request body media type and response shape (Milestone 3.6, see
 *       {@link ContentTypeMethods}).</li>
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
    /**
     * Removed: component schemas rendered as {@code serde_json::Value}. Unions render as typed enums now
     * ({@code modelUnions}, {@code unionDispatch}), so setting it is an error rather than ignored.
     */
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
    /**
     * Removed: the transitional text rewrites of Milestone 2.4 (Java ports of openai-client-base's scripts). Model-level
     * rules replaced them, so setting it is an error rather than ignored.
     */
    public static final String LEGACY_MODEL_REWRITES = "legacyModelRewrites";
    /** Multipart bodies go through a generated {@code src/apis/multipart_helper.rs} (Milestone 2.5). */
    public static final String MULTIPART_HELPER = "multipartHelper";
    /** {@code Schema.property} entries rendered as a file ({@code type: string, format: binary}). */
    public static final String FILE_PROPERTIES = "fileProperties";
    /** Box a model reference only where it closes a cycle of the model graph (Milestone 3.1, see {@link BoxRules}). */
    public static final String BOX_ONLY_CYCLES = "boxOnlyCycles";
    /** {@code Default} only where its value is one the API would accept (Milestone 3.2, see {@link DefaultRules}). */
    public static final String DEFAULT_ONLY_WHERE_VALID = "defaultOnlyWhereValid";
    /** Closed enums get {@code FromStr} and {@code TryFrom} (Milestone 3.2, see {@link EnumFromStr}). */
    public static final String ENUM_FROM_STR = "enumFromStr";
    /** Structs have no JSON {@code Display}; the API code calls {@code serde_json::to_string} (Milestone 3.2). */
    public static final String NO_STRUCT_DISPLAY = "noStructDisplay";
    /** Set on a parameter of struct type, which the api template then serializes as JSON. */
    static final String STRUCT_JSON = "x-rust-struct-json";
    /** Set on a parameter holding an array of structs, whose items the api template serializes as JSON. */
    static final String STRUCT_JSON_ITEMS = "x-rust-struct-json-items";
    /** Render an integer as {@code i64} unless the spec says {@code format: int32} (Milestone 3.4). */
    public static final String INTEGERS_AS_I64 = "integersAsI64";
    /** Name inline schemas after a unique title or their location; a real name collision fails generation (Milestone 3.3). */
    public static final String QUALIFIED_INLINE_NAMES = "qualifiedInlineNames";
    /** Unions render from the model (Milestone 3.3). */
    public static final String MODEL_UNIONS = "modelUnions";
    /** A union with a tag decodes by that tag; it requires {@link #MODEL_UNIONS} (Milestone 3.3). */
    public static final String UNION_DISPATCH = "unionDispatch";
    /** Enums and tagged unions a response can hold have a catch-all; it requires {@link #MODEL_UNIONS} (Milestone 3.5). */
    public static final String OPEN_ENUMS = "openEnums";
    /** Output-relative path the union plan of {@code modelUnions} is written to, as JSON (for tools and debugging). */
    public static final String UNION_PLAN_FILE = "unionPlanFile";
    /**
     * A {@code text/event-stream} success response is a method returning a stream of the event schema, decoded from
     * each {@code data:} payload (Milestone 3.6, see {@link ContentTypeMethods}).
     */
    public static final String TYPED_EVENT_STREAMS = "typedEventStreams";
    /**
     * One method per request body media type and per success response shape, named with a suffix (Milestone 3.6, see
     * {@link ContentTypeMethods}).
     */
    public static final String CONTENT_TYPE_METHODS = "contentTypeMethods";
    /**
     * Inline enums of parameters and of form or multipart body properties become component enums, so the API function
     * takes the enum instead of {@code &str} (see {@link ExtraRules#typedEnumParameters}).
     */
    public static final String TYPED_ENUM_PARAMETERS = "typedEnumParameters";
    /**
     * With {@link #MODEL_UNIONS}: a field whose union is nullable is {@code Option<Option<U>>} like any other nullable
     * field, so a request can send {@code null}.
     */
    public static final String NULLABLE_UNION_FIELDS = "nullableUnionFields";
    /**
     * The unused-helper pruning also drops helpers marked {@code #[allow(dead_code)]}, which are otherwise kept as part
     * of the helper module's API (see {@link HelperPruning}).
     */
    public static final String PRUNE_ALL_HELPERS = "pruneAllHelpers";
    /** A property's or parameter's schema {@code default} is noted in its documentation. */
    public static final String SCHEMA_DEFAULT_DOCS = "schemaDefaultDocs";
    /** Deprecated properties and schemas get {@code #[deprecated]}; generated files allow it among themselves. */
    public static final String DEPRECATED_ATTRIBUTES = "deprecatedAttributes";
    /**
     * Set on an array parameter of models (enums with {@link #TYPED_ENUM_PARAMETERS}): a header or path value joins the
     * items' {@code Display}, as {@code .join(",")} only joins strings.
     */
    static final String DISPLAY_ITEMS = "x-rust-display-items";
    /** Set on a property or model that the model template marks {@code #[deprecated]}. */
    static final String DEPRECATED = "x-rust-deprecated";
    /** Set on an operation whose method returns {@code sse::EventStream}. */
    static final String EVENT_STREAM = "x-rust-event-stream";
    /** Set on every operation: the error enum its method returns, shared by the methods of one operation. */
    static final String ERROR_ENUM = "x-rust-error-enum";
    /** Set on a method that is not the operation's own: it renders no error enum of its own. */
    static final String CONTENT_VARIANT = "x-rust-content-variant";
    /** A doc line saying what a method of an operation sends and returns. */
    static final String VARIANT_DOC = "x-rust-variant-doc";
    /** Set on a string body parameter sent as text with this media type (application/sdp, text/plain). */
    static final String TEXT_BODY = "x-rust-text-body";
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

    /**
     * Stock options that timvw-rust fixes: always applied with this value, not advertised, and rejected when set,
     * whatever the value. The generator only maintains the async reqwest-middleware client.
     */
    static final Map<String, String> FIXED_VALUES = fixedValues();

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
    private List<String> deepObjectQueryParameters = new ArrayList<>();
    private String preprocessedSpecFile;
    private boolean resetQueryParameterStyle;
    private boolean multipartHelper;
    private String preprocessedSpecJson;
    private List<String> fileProperties = new ArrayList<>();
    private boolean boxOnlyCycles;
    private boolean integersAsI64;
    private boolean defaultOnlyWhereValid;
    private boolean enumFromStr;
    private boolean noStructDisplay;
    private final Set<String> structJsonModels = new java.util.TreeSet<>();
    private boolean qualifiedInlineNames;
    private boolean modelUnions;
    private boolean unionDispatch;
    private boolean anyDispatch;
    private boolean openEnums;
    private boolean typedEnumParameters;
    private boolean nullableUnionFields;
    private boolean schemaDefaultDocs;
    private boolean deprecatedAttributes;
    private boolean pruneAllHelpers;
    private final Set<String> deprecatedModels = new java.util.TreeSet<>();
    private OpenEnums.Census openEnumsCensus;
    private String unionPlanFile;
    private final Map<String, CodegenModel> unionModels = new HashMap<>();
    private boolean typedEventStreams;
    /** Some operation got a typed event stream: only then are {@code sse.rs} and its {@code mod} line written. */
    private boolean anyEventStream;
    private boolean contentTypeMethods;
    /** The methods added for an operation, by its own method, which gets its auth methods only after it was grouped. */
    private final Map<CodegenOperation, List<CodegenOperation>> contentVariants = new java.util.IdentityHashMap<>();
    /** The methods per API module ({@code tag#operationId}), to fail on a suffixed name another operation has. */
    private final Map<String, String> methodNames = new HashMap<>();

    private static Map<String, String> templateSets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("7.27", "og7.27.0");
        return m;
    }

    private static Map<String, String> fixedValues() {
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

    /** @param openApiGeneratorVersion the openapi-generator version the generator runs in, e.g. {@code 7.27.0} */
    TimvwRustCodegen(String openApiGeneratorVersion) {
        super();
        this.openApiGeneratorVersion = openApiGeneratorVersion;
        outputFolder = "generated-code/" + GENERATOR_NAME;

        // Our overrides first, then the stock rust templates of the running openapi-generator.
        embeddedTemplateDir = STOCK_TEMPLATE_DIR;
        String set = templateSetFor(openApiGeneratorVersion);
        templateDir = set == null ? STOCK_TEMPLATE_DIR : TEMPLATE_ROOT + "/" + set;

        // Advertise only what can be set: drop rejected and fixed options from `config-help`.
        cliOptions.removeIf(o -> REJECTED_OPTIONS.contains(o.getOpt()) || FIXED_VALUES.containsKey(o.getOpt()));
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
        cliOptions.add(CliOption.newBoolean(RESET_QUERY_PARAMETER_STYLE,
                "Give every query parameter not listed in deepObjectQueryParameters the default style (form, "
                        + "exploded), so arrays are sent as repeated parameters whatever the spec says", false));
        cliOptions.add(CliOption.newBoolean(MULTIPART_HELPER,
                "Send multipart bodies through a generated src/apis/multipart_helper.rs: files are read into a part "
                        + "named after the file, optional arrays send one part per item and optional objects one JSON "
                        + "part, and an encoding contentType of a scalar part is applied", false));
        cliOptions.add(CliOption.newBoolean(QUALIFIED_INLINE_NAMES,
                "An inline schema's title names its model only when it is unique; every other inline schema that "
                        + "becomes a model is named after where it stands (Parent_property, Parent_inner, Parent_value, "
                        + "Parent_<branch title>). Generation fails when two structurally different schemas would "
                        + "produce the same Rust model or file", false));
        cliOptions.add(CliOption.newString(FILE_PROPERTIES,
                "Schema.property entries rendered as a file (type: string, format: binary)"));
        cliOptions.add(CliOption.newBoolean(DEFAULT_ONLY_WHERE_VALID,
                "Default only where its value is one the API would accept: a struct derives it only without required "
                        + "fields (a required nullable field counts), and no enum implements it", false));
        cliOptions.add(CliOption.newBoolean(BOX_RESPONSE_ERROR,
                "Error::ResponseError holds Box<ResponseContent<T>>, keeping every Result small (clippy "
                        + "result_large_err)", false));
        cliOptions.add(CliOption.newString(API_LINT_ALLOWS,
                "Lints allowed at the top of every API module and configuration.rs, written as "
                        + "#![allow(<value>)], e.g. 'clippy::needless_return, clippy::into_iter_on_ref'"));
        cliOptions.add(CliOption.newBoolean(USER_AGENT_FROM_PACKAGE,
                "Default user agent <packageName>/<crate version> (CARGO_PKG_VERSION) instead of "
                        + "OpenAPI-Generator/<spec version>/rust; httpUserAgent still wins", false));
        cliOptions.add(CliOption.newBoolean(INTEGERS_AS_I64,
                "Render an integer as i64 unless the spec says format: int32, wherever it appears; spec bounds never "
                        + "pick a type. Without the option integers render as in stock -g rust (an integer without a "
                        + "format is i32)", false));
        cliOptions.add(CliOption.newString(PREPROCESSED_SPEC_FILE,
                "Write the spec as it stands after the spec rules (before inline schemas are named) to this "
                        + "output-relative path, as JSON"));
        cliOptions.add(CliOption.newBoolean(ENUM_FROM_STR,
                "Closed enums implement FromStr and TryFrom<&str>, TryFrom<&String> and TryFrom<String>, accepting "
                        + "exactly what their Display prints; property enums get the Display they lack", false));
        cliOptions.add(CliOption.newBoolean(NO_STRUCT_DISPLAY,
                "Structs have no JSON Display; API code serializes parameters "
                        + "of struct type with serde_json::to_string, with the same bytes on the wire", false));
        cliOptions.add(CliOption.newBoolean(MODEL_UNIONS,
                "Render every oneOf and anyOf as an untagged enum from the model: variants named after the branch and "
                        + "ordered so that decoding takes the narrowest one", false));
        cliOptions.add(CliOption.newBoolean(UNION_DISPATCH,
                "With modelUnions: a union with a discriminator, or whose branches all require one property with fixed "
                        + "values, decodes the variant its tag selects instead of trying them all. It serializes as "
                        + "before: each struct holds its tag", false));
        cliOptions.add(CliOption.newBoolean(OPEN_ENUMS,
                "With modelUnions: a value enum or tagged union that a response body or webhook payload can hold accepts "
                        + "values the spec does not list. An enum gets a unit variant Unknown, which serializes as "
                        + "\"unknown\" and which FromStr does not parse to; a tagged union gets Unknown(serde_json::Value), "
                        + "which serializes as it came. What other variants rely on to reject a value stays closed", false));
        cliOptions.add(CliOption.newString(UNION_PLAN_FILE,
                "With modelUnions: write the variants of every union and the schemas they refer to to this "
                        + "output-relative path, as JSON"));
        cliOptions.add(CliOption.newBoolean(TYPED_EVENT_STREAMS,
                "A text/event-stream success response is a method returning sse::EventStream<Event, OperationError>: "
                        + "each data: payload decodes to the event schema (a union by its type field; the SSE event "
                        + "name is not used) and data: [DONE] ends it. The operation's own method when the event "
                        + "stream is its first response media type, <operation>_stream otherwise", false));
        cliOptions.add(CliOption.newBoolean(CONTENT_TYPE_METHODS,
                "One method per request body media type and per success response shape (JSON, text, bytes): the "
                        + "operation's method keeps the first of each, the others are <operation>_<suffix> "
                        + "(_json, _multipart, _form, _text, _bytes or the media subtype)", false));
        cliOptions.add(CliOption.newBoolean(TYPED_ENUM_PARAMETERS,
                "Inline enums of query, path, header and cookie parameters, and of the properties of form and "
                        + "multipart bodies, become component enums (<operationId>_<parameter>, <Schema>_<property>), so "
                        + "the API function takes the enum instead of &str", false));
        cliOptions.add(CliOption.newBoolean(NULLABLE_UNION_FIELDS,
                "With modelUnions: a field whose union is nullable is Option<Option<U>> like any other nullable field, "
                        + "so a request can send null; without it the field is Option<U>", false));
        cliOptions.add(CliOption.newBoolean(PRUNE_ALL_HELPERS,
                "Helpers of union_dispatch.rs and multipart_helper.rs that nothing calls are always left out; with "
                        + "this option also those marked #[allow(dead_code)], which are otherwise kept", false));
        cliOptions.add(CliOption.newBoolean(SCHEMA_DEFAULT_DOCS,
                "A property's or parameter's schema default is noted in its documentation (Defaults to `value`.)",
                false));
        cliOptions.add(CliOption.newBoolean(DEPRECATED_ATTRIBUTES,
                "Deprecated properties and schemas get #[deprecated]; every generated model and API file starts with "
                        + "#![allow(deprecated)], so only callers are warned", false));
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

    /**
     * With {@code integersAsI64}, an integer the stock generator renders as {@code i32} is {@code i64} unless its
     * format is {@code int32}. Every location (fields, container items, parameters, responses, union variants) gets
     * its type from here. The stock flags are left alone, so {@code isInteger} still selects the integer enum
     * rendering. {@code preferUnsignedInt} and {@code bestFitInt} are pinned to false; up to openapi-generator 7.25
     * the stock mapping then only answers {@code i32} or {@code i64} for an integer, 7.27 also maps the formats
     * {@code int8}, {@code int16} and {@code uint8} to {@code uint64} to sized and unsigned types, which the option
     * turns into {@code i64} as well.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public String getSchemaType(Schema p) {
        String type = super.getSchemaType(p);
        return integersAsI64 && NARROW_INTEGER_TYPES.contains(type) && !"int32".equals(p.getFormat()) ? "i64" : type;
    }

    /** The integer types other than {@code i64} the stock mapping can answer, which {@code integersAsI64} widens. */
    private static final Set<String> NARROW_INTEGER_TYPES = Set.of("i8", "i16", "i32", "u8", "u16", "u32", "u64");

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
        if (modelUnions) {
            retypeUntypedVariants(mdl);
            UnionRules.nameVariants(mdl, name, model, this::toModelName);
            return mdl;
        }
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
    /** Marks every model property with {@link BoxRules#BOX} and every model with {@link DefaultRules#DERIVE_DEFAULT}. */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        objs = super.postProcessAllModels(objs);
        Map<String, CodegenModel> unions = modelUnions
                ? UnionRules.plan(objs, msg -> LOGGER.warn("{}: {}: {}", GENERATOR_NAME, MODEL_UNIONS, msg)) : Map.of();
        if (nullableUnionFields) {
            LOGGER.info("{}: {}: {} fields of a nullable union made nullable", GENERATOR_NAME, NULLABLE_UNION_FIELDS,
                    ExtraRules.nullableUnionFields(objs, unions, openAPI));
        }
        if (deprecatedAttributes) {
            deprecatedModels.addAll(ExtraRules.markDeprecated(objs));
        }
        BoxRules.mark(objs, boxOnlyCycles);
        if (modelUnions) {
            UnionRules.payloads(unions, objs);
            unionModels.putAll(unions);
            if (unionDispatch) {
                UnionRules.DispatchCounts counts = UnionRules.dispatch(unions, objs, openAPI);
                anyDispatch = counts.explicit() + counts.implicit() > 0;
                LOGGER.info("{}: {}: {} tagged ({} by a discriminator, {} implicitly), {} untagged; {} have a value on "
                                + "two variants, {} allow the tag to be missing", GENERATOR_NAME, UNION_DISPATCH,
                        counts.explicit() + counts.implicit(), counts.explicit(), counts.implicit(), counts.untagged(),
                        counts.sharedValue(), counts.optional());
            }
            if (openEnums) {
                openEnumsCensus = OpenEnums.mark(objs, unions, openAPI);
                LOGGER.info("{}: {}: {} enums, {} reachable, {} of them guarded; {} tagged unions, {} reachable, {} of them "
                                + "guarded; {} untagged unions left as they are", GENERATOR_NAME, OPEN_ENUMS,
                        openEnumsCensus.enums().size(), openEnumsCensus.reachableEnums().size(),
                        openEnumsCensus.guardedEnums().size(), openEnumsCensus.taggedUnions().size(),
                        openEnumsCensus.reachableTagged().size(), openEnumsCensus.guardedUnions().size(),
                        openEnumsCensus.untaggedUnions().size());
            }
            writeUnionPlan(unions);
        }
        DefaultRules.mark(objs, defaultOnlyWhereValid);
        Imports.markModels(objs, modelUnions);
        return objs;
    }

    private void writeUnionPlan(Map<String, CodegenModel> unions) {
        if (unionPlanFile == null) {
            return;
        }
        java.nio.file.Path target = java.nio.file.Paths.get(outputFolder, unionPlanFile);
        boolean v31 = openAPI.getOpenapi() != null
                && new org.openapitools.codegen.utils.SemVer(openAPI.getOpenapi()).atLeast("3.1.0");
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            Map<String, Object> described = UnionRules.describe(unions, openAPI, unionDispatch);
            if (openEnumsCensus != null) {
                described.put(OPEN_ENUMS, openEnumsCensus.describe());
            }
            (v31 ? io.swagger.v3.core.util.Json31.pretty() : io.swagger.v3.core.util.Json.pretty())
                    .writeValue(target.toFile(), described);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot write " + target, e);
        }
        LOGGER.info("{}: wrote the union plan to {}", GENERATOR_NAME, target);
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

    /** A union variant can hold an untyped {@code anyOf} too, directly or as its map value or array item. */
    private static void retypeUntypedVariants(CodegenModel model) {
        if (model.getComposedSchemas() == null) {
            return;
        }
        for (List<CodegenProperty> variants : Arrays.asList(model.getComposedSchemas().getOneOf(),
                model.getComposedSchemas().getAnyOf())) {
            if (variants != null) {
                variants.forEach(TimvwRustCodegen::retypeUntypedComposed);
            }
        }
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

        // The CLI applies `--library` through setLibrary() rather than additionalProperties. The stock constructor
        // already sets the default library, so through the setter only another library can be told apart.
        if (getLibrary() != null && !REQWEST_LIBRARY.equals(getLibrary())) {
            throw new IllegalArgumentException(String.format(
                    "%s: option '%s' is not configurable: %s always generates for '%s'; remove it (it was set to '%s')",
                    GENERATOR_NAME, CodegenConstants.LIBRARY, GENERATOR_NAME, REQWEST_LIBRARY, getLibrary()));
        }

        for (String option : REJECTED_OPTIONS) {
            if (additionalProperties.containsKey(option)) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not supported; remove it (it was set to '%s')",
                        GENERATOR_NAME, option, additionalProperties.get(option)));
            }
        }

        for (Map.Entry<String, String> entry : FIXED_VALUES.entrySet()) {
            String option = entry.getKey();
            if (additionalProperties.containsKey(option)) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not configurable: %s always uses '%s'; remove it (it was set to '%s')",
                        GENERATOR_NAME, option, GENERATOR_NAME, entry.getValue(), additionalProperties.get(option)));
            }
            additionalProperties.put(option, entry.getValue());
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
        if (!SpecRules.entries(additionalProperties.get(JSON_VALUE_SCHEMAS)).isEmpty()) {
            throw new IllegalArgumentException(String.format("%s: %s was removed: unions render as typed enums with "
                    + "%s and %s; drop the option", GENERATOR_NAME, JSON_VALUE_SCHEMAS, MODEL_UNIONS, UNION_DISPATCH));
        }
        deepObjectQueryParameters = SpecRules.entries(additionalProperties.get(DEEP_OBJECT_QUERY_PARAMETERS));
        resetQueryParameterStyle = additionalProperties.containsKey(RESET_QUERY_PARAMETER_STYLE)
                && convertPropertyToBooleanAndWriteBack(RESET_QUERY_PARAMETER_STYLE);
        if (additionalProperties.containsKey(LEGACY_MODEL_REWRITES)) {
            throw new IllegalArgumentException(String.format("%s: %s was removed: the model-level rules replaced the "
                    + "text rewrites (unused imports are no longer written, see %s); drop the option",
                    GENERATOR_NAME, LEGACY_MODEL_REWRITES, "x-rust-uses-models"));
        }
        multipartHelper = additionalProperties.containsKey(MULTIPART_HELPER)
                && convertPropertyToBooleanAndWriteBack(MULTIPART_HELPER);
        additionalProperties.put(MULTIPART_HELPER, multipartHelper);
        if (multipartHelper) {
            supportingFiles.add(new SupportingFile("multipart_helper.mustache", apiFolder, "multipart_helper.rs"));
        }
        fileProperties = SpecRules.entries(additionalProperties.get(FILE_PROPERTIES));
        boxOnlyCycles = additionalProperties.containsKey(BOX_ONLY_CYCLES)
                && convertPropertyToBooleanAndWriteBack(BOX_ONLY_CYCLES);
        defaultOnlyWhereValid = additionalProperties.containsKey(DEFAULT_ONLY_WHERE_VALID)
                && convertPropertyToBooleanAndWriteBack(DEFAULT_ONLY_WHERE_VALID);
        additionalProperties.put(DEFAULT_ONLY_WHERE_VALID, defaultOnlyWhereValid);
        enumFromStr = additionalProperties.containsKey(ENUM_FROM_STR)
                && convertPropertyToBooleanAndWriteBack(ENUM_FROM_STR);
        noStructDisplay = additionalProperties.containsKey(NO_STRUCT_DISPLAY)
                && convertPropertyToBooleanAndWriteBack(NO_STRUCT_DISPLAY);
        integersAsI64 = additionalProperties.containsKey(INTEGERS_AS_I64)
                && convertPropertyToBooleanAndWriteBack(INTEGERS_AS_I64);
        qualifiedInlineNames = additionalProperties.containsKey(QUALIFIED_INLINE_NAMES)
                && convertPropertyToBooleanAndWriteBack(QUALIFIED_INLINE_NAMES);
        modelUnions = additionalProperties.containsKey(MODEL_UNIONS)
                && convertPropertyToBooleanAndWriteBack(MODEL_UNIONS);
        unionDispatch = additionalProperties.containsKey(UNION_DISPATCH)
                && convertPropertyToBooleanAndWriteBack(UNION_DISPATCH);
        if (unionDispatch && !modelUnions) {
            throw new IllegalArgumentException(GENERATOR_NAME + ": " + UNION_DISPATCH + " requires " + MODEL_UNIONS
                    + ": it decodes the unions that option renders");
        }
        openEnums = additionalProperties.containsKey(OPEN_ENUMS) && convertPropertyToBooleanAndWriteBack(OPEN_ENUMS);
        if (openEnums && !modelUnions) {
            throw new IllegalArgumentException(GENERATOR_NAME + ": " + OPEN_ENUMS + " requires " + MODEL_UNIONS
                    + ": which values stay closed depends on the variants and the order that option settles");
        }
        typedEnumParameters = additionalProperties.containsKey(TYPED_ENUM_PARAMETERS)
                && convertPropertyToBooleanAndWriteBack(TYPED_ENUM_PARAMETERS);
        nullableUnionFields = additionalProperties.containsKey(NULLABLE_UNION_FIELDS)
                && convertPropertyToBooleanAndWriteBack(NULLABLE_UNION_FIELDS);
        if (nullableUnionFields && !modelUnions) {
            throw new IllegalArgumentException(GENERATOR_NAME + ": " + NULLABLE_UNION_FIELDS + " requires "
                    + MODEL_UNIONS + ": it changes the fields of the unions that option renders");
        }
        schemaDefaultDocs = additionalProperties.containsKey(SCHEMA_DEFAULT_DOCS)
                && convertPropertyToBooleanAndWriteBack(SCHEMA_DEFAULT_DOCS);
        deprecatedAttributes = additionalProperties.containsKey(DEPRECATED_ATTRIBUTES)
                && convertPropertyToBooleanAndWriteBack(DEPRECATED_ATTRIBUTES);
        pruneAllHelpers = additionalProperties.containsKey(PRUNE_ALL_HELPERS)
                && convertPropertyToBooleanAndWriteBack(PRUNE_ALL_HELPERS);
        typedEventStreams = additionalProperties.containsKey(TYPED_EVENT_STREAMS)
                && convertPropertyToBooleanAndWriteBack(TYPED_EVENT_STREAMS);
        additionalProperties.put(TYPED_EVENT_STREAMS, typedEventStreams);
        if (typedEventStreams) {
            supportingFiles.add(new SupportingFile("sse.mustache", apiFolder, "sse.rs"));
        }
        contentTypeMethods = additionalProperties.containsKey(CONTENT_TYPE_METHODS)
                && convertPropertyToBooleanAndWriteBack(CONTENT_TYPE_METHODS);
        additionalProperties.put(CONTENT_TYPE_METHODS, contentTypeMethods);
        Object plan = additionalProperties.get(UNION_PLAN_FILE);
        unionPlanFile = plan == null || String.valueOf(plan).isEmpty() ? null : String.valueOf(plan);
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
        if (modelUnions) {
            UnionRules.pinArrayTitles(openAPI);
        }
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
            if (qualifiedInlineNames) {
                flattenWithQualifiedNames(openAPI);
            } else {
                InlineModelResolverAccess.flatten(openAPI, inlineSchemaNameMapping(), inlineSchemaOption());
            }
        }
        if (modelUnions) {
            LOGGER.info("{}: {}: {} string enum branches became models", GENERATOR_NAME, MODEL_UNIONS,
                    UnionRules.hoistEnumBranches(openAPI, this::toModelName));
        }
        super.preprocessOpenAPI(openAPI);
        for (String where : SpecNormalizer.integerWithFloatFormatToNumber(openAPI)) {
            LOGGER.warn("{}: read 'type: integer' with a float format as 'type: number' at {}", GENERATOR_NAME, where);
        }
    }

    /**
     * Names the inline schemas, then runs the resolver between two checks: the first sees the schemas the resolver
     * would overwrite, the second the models it really made.
     */
    private void flattenWithQualifiedNames(OpenAPI openAPI) {
        InlineNames.Plan plan = InlineNames.qualify(openAPI, inlineSchemaOption(), this::toModelName,
                this::toModelFilename);
        LOGGER.info("{}: {}: {} models planned, {} titles name a model, {} inline schemas are one instance: {}",
                GENERATOR_NAME, QUALIFIED_INLINE_NAMES, plan.sites().size(), plan.kept().size(), plan.shared().size(),
                plan.shared());
        plan.qualified().forEach((title, why) -> LOGGER.info("{}: {}: title {} names no model: {}", GENERATOR_NAME,
                QUALIFIED_INLINE_NAMES, title, why));
        failOnCollisions(NameCollisions.beforeFlattening(openAPI, plan.sites(), this::toModelName,
                this::toModelFilename));
        InlineModelResolverAccess.flatten(openAPI, inlineSchemaNameMapping(), inlineSchemaOption());
        failOnCollisions(NameCollisions.afterFlattening(openAPI, plan.sites(), this::toModelName,
                this::toModelFilename));
    }

    private static void failOnCollisions(List<String> collisions) {
        if (!collisions.isEmpty()) {
            throw new IllegalArgumentException(String.format("%s: %s: different schemas would produce the same Rust "
                            + "name (%d); fix this upstream, the generator does not pick a winner:%n  %s",
                    GENERATOR_NAME, QUALIFIED_INLINE_NAMES, collisions.size(),
                    String.join(System.lineSeparator() + "  ", collisions)));
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
        if (schemaDefaultDocs) {
            LOGGER.info("{}: {}: {} defaults noted in the documentation", GENERATOR_NAME, SCHEMA_DEFAULT_DOCS,
                    ExtraRules.documentDefaults(openAPI));
        }
        if (webhookModule != null) {
            int routed = SpecRules.routeWebhooks(openAPI, webhookModule);
            LOGGER.info("{}: {}: routed {} webhook operations to tag {}", GENERATOR_NAME, WEBHOOK_MODULE, routed,
                    webhookModule);
        }
        SpecRules.stringSchemas(openAPI, stringIdSchemas, log);
        SpecRules.stringIdProperties(openAPI, stringIdProperties, SpecRules.names(stringIdSchemas), log);
        int inlined = SpecRules.inlineStringRefsInUnions(openAPI);
        int pruned = SpecRules.pruneGeneratorHostileKeywords(openAPI);
        if (!qualifiedInlineNames) {
            List<String> retitled = SpecRules.disambiguateInlineTitles(openAPI, this::toModelName);
            for (String r : retitled) {
                LOGGER.warn("{}: inline title collides with a component schema, renamed: {}", GENERATOR_NAME, r);
            }
        }
        int stripped = SpecRules.stripRefSiblings(openAPI);
        int collapsed = SpecRules.collapseRequiredOnlyUnions(openAPI);
        int titled = SpecRules.titleUnnamedUnionVariants(openAPI);
        if (modelUnions && !qualifiedInlineNames) {
            for (String r : UnionRules.retitleNestedTwins(openAPI, this::toModelName)) {
                LOGGER.info("{}: {}: inline title repeats the enclosing one, renamed: {}", GENERATOR_NAME, MODEL_UNIONS,
                        r);
            }
        }
        List<String> hoisted = SpecRules.hoistInlineUnions(openAPI, nullableUnionFields);
        for (String h : hoisted) {
            LOGGER.info("{}: hoisted inline union {}", GENERATOR_NAME, h);
        }
        SpecRules.stringProperties(openAPI, stringProperties, log);
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
        if (typedEnumParameters) {
            for (String e : ExtraRules.typedEnumParameters(openAPI, this::toModelName)) {
                LOGGER.info("{}: {}: {}", GENERATOR_NAME, TYPED_ENUM_PARAMETERS, e);
            }
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
        if (typedEventStreams && !anyEventStream) {
            // No operation streams: no sse.rs, no `pub mod sse;` and no stream dependencies in Cargo.toml.
            objs.put(TYPED_EVENT_STREAMS, false);
            supportingFiles.removeIf(f -> "sse.rs".equals(f.getDestinationFilename()));
            LOGGER.info("{}: {}: no operation has a text/event-stream response, so sse.rs is not written",
                    GENERATOR_NAME, TYPED_EVENT_STREAMS);
        }
        return objs;
    }

    /**
     * Runs the steps that need the written crate (the union dispatch module, {@code enumFromStr}), then the stock
     * post-processing.
     */
    @Override
    public void postProcess() {
        java.nio.file.Path models = java.nio.file.Paths.get(outputFolder, "src", "models");
        try {
            checkStructJsonModels(models, structJsonModels);
            if (anyDispatch) {
                UnionRules.writeDispatchModule(models, openEnums);
                logPruned(UnionRules.DISPATCH_MODULE, HelperPruning.prune(
                        models.resolve(UnionRules.DISPATCH_MODULE + ".rs"), List.of(models), !pruneAllHelpers));
            }
            if (multipartHelper) {
                java.nio.file.Path apis = java.nio.file.Paths.get(outputFolder, "src", "apis");
                logPruned("multipart_helper", HelperPruning.prune(apis.resolve("multipart_helper.rs"), List.of(apis),
                        !pruneAllHelpers));
            }
            if (deprecatedAttributes) {
                for (String model : deprecatedModels) {
                    java.nio.file.Path file = models.resolve(toModelFilename(model) + ".rs");
                    if (java.nio.file.Files.exists(file)) {
                        java.nio.file.Files.writeString(file,
                                ExtraRules.deprecateItem(java.nio.file.Files.readString(file), model));
                    }
                }
                ExtraRules.allowDeprecated(models, java.nio.file.Paths.get(outputFolder, "src", "apis"));
            }
            if (enumFromStr) {
                EnumFromStr.Result result = EnumFromStr.run(models,
                        msg -> LOGGER.warn("{}: {}: {}", GENERATOR_NAME, ENUM_FROM_STR, msg));
                LOGGER.info("{}: {}: {} closed enums, {} of them given a Display", GENERATOR_NAME, ENUM_FROM_STR,
                        result.enums(), result.displaysAdded());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(GENERATOR_NAME + ": cannot process " + models, e);
        }
        super.postProcess();
    }

    private static void logPruned(String module, List<String> dropped) {
        if (!dropped.isEmpty()) {
            LOGGER.info("{}: {}.rs: nothing calls {}, left out", GENERATOR_NAME, module, dropped);
        }
    }

    /**
     * A parameter marked {@link #STRUCT_JSON} was a struct when the API module was rendered. If the written model is
     * something else (a union, whose {@code Display} is not its JSON), the explicit serialization would change the bytes
     * on the wire: fail instead. A safety net: nothing rewrites models after rendering any more.
     */
    void checkStructJsonModels(java.nio.file.Path models, Set<String> names) throws java.io.IOException {
        List<String> notStructs = new ArrayList<>();
        for (String model : names) {
            java.nio.file.Path file = models.resolve(toModelFilename(model) + ".rs");
            if (!java.nio.file.Files.exists(file) || !java.util.regex.Pattern.compile(
                    "\\bpub struct " + java.util.regex.Pattern.quote(model) + "\\b")
                    .matcher(java.nio.file.Files.readString(file)).find()) {
                notStructs.add(model);
            }
        }
        if (!notStructs.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "%s: %s: parameters of these types are serialized as JSON, but they are no longer structs: %s",
                    GENERATOR_NAME, NO_STRUCT_DISPLAY, notStructs));
        }
    }

    /** Marks a parameter whose type, or whose array items' type, is one of the structs. */
    static void markStructJson(CodegenParameter p, Set<String> structs) {
        boolean items = p.isArray && p.items != null;
        String type = items ? p.items.dataType : p.dataType;
        if (type != null && structs.contains(type.replaceFirst("^models::", ""))) {
            p.vendorExtensions.put(items ? STRUCT_JSON_ITEMS : STRUCT_JSON, true);
        }
    }

    /**
     * The models that end up as a struct: no enum, discriminated union or {@code oneOf}, as in the model template.
     */
    private static Set<String> renderedStructs(List<ModelMap> allModels) {
        Set<String> structs = new HashSet<>();
        for (ModelMap map : allModels) {
            CodegenModel cm = map.getModel();
            boolean union = UnionRules.isUnion(cm);
            if (!cm.isEnum && cm.discriminator == null && (cm.oneOf == null || cm.oneOf.isEmpty()) && !union) {
                structs.add(cm.classname);
            }
        }
        return structs;
    }

    /**
     * With {@code contentTypeMethods} or {@code typedEventStreams}, adds the other methods of the operation next to its
     * own (see {@link ContentTypeMethods}): each is the operation restricted to its media types, rendered by the stock
     * {@code fromOperation} under a suffixed operation id. Variants share the operation's error enum.
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, io.swagger.v3.oas.models.Operation operation,
            CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        super.addOperationToGroup(tag, resourcePath, operation, co, operations);
        // The stock grouping renames a method whose name another method of the tag has.
        co.vendorExtensions.put(ERROR_ENUM, co.operationIdCamelCase + "Error");
        if (!contentTypeMethods && !typedEventStreams) {
            return;
        }
        claimName(tag, co, false);
        io.swagger.v3.oas.models.responses.ApiResponse methodResponse = operation.getResponses() == null ? null
                : findMethodResponse(operation.getResponses());
        List<ContentTypeMethods.Method> methods = ContentTypeMethods.plan(openAPI, operation, methodResponse,
                contentTypeMethods, typedEventStreams, msg -> LOGGER.warn("{}: {}: {}", GENERATOR_NAME,
                        TYPED_EVENT_STREAMS, msg));
        ContentTypeMethods.Method own = methods.get(0);
        shapeMethod(co, own, null);
        if (methods.size() == 1) {
            return;
        }
        List<io.swagger.v3.oas.models.servers.Server> servers = openAPI.getPaths() == null
                || openAPI.getPaths().get(resourcePath) == null ? null : openAPI.getPaths().get(resourcePath).getServers();
        List<CodegenOperation> variants = new ArrayList<>();
        for (ContentTypeMethods.Method method : methods.subList(1, methods.size())) {
            io.swagger.v3.oas.models.Operation restricted = method.operation();
            String id = getOrGenerateOperationId(operation, resourcePath, co.httpMethod);
            restricted.setOperationId(id + method.suffix());
            CodegenOperation variant = fromOperation(resourcePath, co.httpMethod, restricted, servers);
            variant.tags = co.tags;
            shapeMethod(variant, method, co);
            String wanted = variant.operationId;
            super.addOperationToGroup(tag, resourcePath, restricted, variant, operations);
            if (!wanted.equals(variant.operationId)) {
                throw new IllegalArgumentException(String.format("%s: %s: method %s of %s collides with another "
                        + "operation of tag %s", GENERATOR_NAME, CONTENT_TYPE_METHODS, wanted, co.operationId, tag));
            }
            claimName(tag, variant, true);
            variants.add(variant);
        }
        contentVariants.put(co, variants);
        LOGGER.info("{}: {}: {} has {} more method(s): {}", GENERATOR_NAME, CONTENT_TYPE_METHODS, co.operationId,
                variants.size(), variants.stream().map(v -> v.operationId).collect(java.util.stream.Collectors.toList()));
    }

    private boolean flag(String option) {
        return Boolean.parseBoolean(String.valueOf(additionalProperties.get(option)));
    }

    /** Fails when a suffixed method takes the name of another method of the same API module. */
    private void claimName(String tag, CodegenOperation co, boolean variant) {
        String key = tag + "#" + co.operationId;
        String before = methodNames.putIfAbsent(key, variant ? "variant" : "operation");
        if (before != null && (variant || "variant".equals(before))) {
            throw new IllegalArgumentException(String.format("%s: %s: two methods of tag %s are named %s",
                    GENERATOR_NAME, CONTENT_TYPE_METHODS, tag, co.operationId));
        }
    }

    /** Marks what the template needs to render one method: a stream, a text body, the shared error enum, a doc line. */
    private void shapeMethod(CodegenOperation co, ContentTypeMethods.Method method, CodegenOperation of) {
        boolean stream = method.eventStream() && co.returnType != null && !co.isResponseFile;
        if (stream) {
            co.vendorExtensions.put(EVENT_STREAM, true);
            anyEventStream = true;
        }
        String request = method.requestMediaType();
        if (contentTypeMethods && request != null && !ContentTypeMethods.stockBody(request)) {
            for (List<CodegenParameter> params : Arrays.asList(co.allParams, co.bodyParams, co.requiredParams,
                    co.optionalParams)) {
                if (params == null) {
                    continue;
                }
                for (CodegenParameter p : params) {
                    if (p.isBodyParam && p.isString && !p.isFile && !p.isBinary) {
                        p.vendorExtensions.put(TEXT_BODY, request);
                    }
                }
            }
        }
        List<String> doc = new ArrayList<>();
        if (of != null) {
            co.vendorExtensions.put(CONTENT_VARIANT, true);
            co.vendorExtensions.put(ERROR_ENUM, of.operationIdCamelCase + "Error");
            String ownRequest = firstConsumes(of);
            if (request != null && !request.equals(ownRequest)) {
                doc.add("Sends the request body as `" + request + "`; `" + of.operationId + "` sends it as `"
                        + ownRequest + "`.");
            }
        }
        if (stream) {
            doc.add("Streams the `" + ContentTypeMethods.EVENT_STREAM + "` response: the `data:` payload of every event "
                    + "decodes to `" + co.returnType + "`, whatever the event's SSE `event:` name, and `data: [DONE]` or "
                    + "the end of the body ends the stream. The request has to ask for a stream.");
        } else if (of != null && method.otherResponse()) {
            doc.add("Decodes the `" + firstProduces(co) + "` form of the response; `" + of.operationId + "` decodes `"
                    + firstProduces(of) + "`.");
        }
        if (!doc.isEmpty()) {
            co.vendorExtensions.put(VARIANT_DOC, doc);
        }
    }

    private static String firstConsumes(CodegenOperation co) {
        return co.consumes == null || co.consumes.isEmpty() ? "" : String.valueOf(co.consumes.get(0).get("mediaType"));
    }

    private static String firstProduces(CodegenOperation co) {
        return co.produces == null || co.produces.isEmpty() ? "" : String.valueOf(co.produces.get(0).get("mediaType"));
    }

    /** Every method names the error enum it returns: its own unless it is another method of an operation. */
    @Override
    public CodegenOperation fromOperation(String path, String httpMethod, io.swagger.v3.oas.models.Operation operation,
            List<io.swagger.v3.oas.models.servers.Server> servers) {
        CodegenOperation co = super.fromOperation(path, httpMethod, operation, servers);
        co.vendorExtensions.put(ERROR_ENUM, co.operationIdCamelCase + "Error");
        return co;
    }

    /** {@code default_api} re-exports the webhook module when {@code webhookModuleReexport} is set. */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        objs = super.postProcessOperationsWithModels(objs, allModels);
        boolean helperUsed = false;
        Set<String> structs = noStructDisplay ? renderedStructs(allModels) : Set.of();
        if (objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            for (CodegenOperation op : objs.getOperations().getOperation()) {
                op.vendorExtensions.putIfAbsent(ERROR_ENUM, op.operationIdCamelCase + "Error");
                for (CodegenOperation variant : contentVariants.getOrDefault(op, List.of())) {
                    variant.authMethods = op.authMethods;
                    variant.hasAuthMethods = op.hasAuthMethods;
                    variant.vendorExtensions.put(ERROR_ENUM, op.operationIdCamelCase + "Error");
                }
                for (List<CodegenParameter> params : Arrays.asList(op.allParams, op.formParams, op.bodyParams,
                        op.queryParams, op.pathParams, op.headerParams, op.requiredParams, op.optionalParams)) {
                    if (params == null) {
                        continue;
                    }
                    for (CodegenParameter p : params) {
                        if (p.dataType != null && p.dataType.startsWith("models::")) {
                            p.vendorExtensions.put(TYPE_QUALIFIED, true);
                        }
                        markStructJson(p, structs);
                        if (p.vendorExtensions.containsKey(STRUCT_JSON)) {
                            structJsonModels.add(p.dataType.replaceFirst("^models::", ""));
                        } else if (p.vendorExtensions.containsKey(STRUCT_JSON_ITEMS)) {
                            structJsonModels.add(p.items.dataType.replaceFirst("^models::", ""));
                        }
                        if (p.dataType != null && unionModels.containsKey(p.dataType.replaceFirst("^models::", ""))) {
                            p.vendorExtensions.put(UnionRules.UNION_TEXT, true);
                        }
                        if (p.isArray && p.items != null && (p.items.isModel || p.items.isEnumRef
                                || (p.items.dataType != null && p.items.dataType.startsWith("models::")))) {
                            p.vendorExtensions.put(DISPLAY_ITEMS, true);
                        }
                    }
                }
                if (multipartHelper && op.isMultipart && op.formParams != null && !op.formParams.isEmpty()) {
                    MultipartRules.classify(openAPI, op, unionModels,
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
        Imports.markApi(objs, flag(SUPPORT_ASYNC), multipartHelper, flag(SUPPORT_MULTIPLE_RESPONSES));
        if (webhookModuleReexport && objs.getOperations() != null
                && toApiName("default").equals(objs.getOperations().getClassname())) {
            objs.put(WEBHOOK_REEXPORT_KEY, toApiFilename(webhookModule));
        }
        return objs;
    }
}
