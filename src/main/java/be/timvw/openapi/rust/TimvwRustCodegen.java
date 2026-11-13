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
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.model.WebhooksMap;
import org.openapitools.codegen.InlineModelResolverAccess;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConstants;
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
import java.util.SortedSet;

/**
 * {@code timvw-rust}: a Rust client generator. {@link RustCodegenBase} holds the Rust language layer (ported from
 * openapi-generator's {@code -g rust}); this class adds the generator's own options and rules.
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
 *   <li>{@code apiLintAllows} and {@code userAgentFromPackage} shape the API modules (Milestone 2.6);
 *       {@code Error::ResponseError} always holds {@code Box<ResponseContent<T>>} (the former {@code boxResponseError},
 *       see {@link #ALWAYS_ON});</li>
 *   <li>{@code multipartHelper} sends multipart bodies through a generated helper module (see {@link MultipartRules}),
 *       and {@code fileProperties} declares properties to be files (Milestone 2.5);</li>
 *   <li>{@code use crate::models;} is written only into a model file that refers to another model, and an API
 *       module imports {@code ContentType}, {@code de::Error} and the file stream types only where an operation uses
 *       them (see {@link Imports}); this replaced the {@code legacyModelRewrites} text rewrites (Milestone 2.4), which
 *       are gone;</li>
 *   <li>the renderings of the former baseline options, always applied (see {@link #ALWAYS_ON}): a model reference
 *       is boxed only where the model graph needs it to break a cycle ({@link BoxRules}); {@code Default} only where
 *       its value is one the API would accept ({@link DefaultRules}); closed enums get {@code FromStr} and
 *       {@code TryFrom} ({@link EnumFromStr}); structs have no JSON {@code Display} and the API code serializes them
 *       explicitly; an integer is {@code i64} unless the spec says {@code format: int32} ({@link #getSchemaType});
 *       an inline schema is named after its title when that is unique and after where it stands otherwise, and two
 *       different schemas that would share a Rust model or file fail generation ({@link InlineNames},
 *       {@link NameCollisions}); unions render from the model ({@link UnionRules}) and a union with a tag decodes by
 *       that tag ({@link UnionRules#dispatch}); what a response can hold has a catch-all for values the spec does not
 *       list ({@link OpenEnums}); a schema {@code default} is noted in the documentation; a field whose union is
 *       nullable can send {@code null};</li>
 *   <li>{@code typedEventStreams} renders a method returning a typed stream for a {@code text/event-stream} response, and
 *       {@code contentTypeMethods} one method per request body media type and response shape (Milestone 3.6, see
 *       {@link ContentTypeMethods}).</li>
 * </ul>
 *
 * <p>Every template is the generator's own, under {@code timvw-rust/}; none comes from the openapi-generator jar.</p>
 */
public class TimvwRustCodegen extends RustCodegenBase {

    private static final Logger LOGGER = LoggerFactory.getLogger("org.openapitools.codegen.timvw.TimvwRustCodegen");

    public static final String GENERATOR_NAME = "timvw-rust";

    /** Our own option: derive {@code bon::Builder} on model structs and {@code #[bon::builder]} on API functions. */
    public static final String BON_BUILDERS = "bonBuilders";

    /**
     * Our own option (default on): every model implements the generated {@code models::validation::Validate} trait, which
     * checks the validation keywords of its schema (see {@link ValidateRules}).
     */
    public static final String VALIDATE_METHODS = "validateMethods";
    /** Set when a generated {@code validate()} matches a {@code pattern}: the crate depends on {@code regex}. */
    static final String VALIDATION_REGEX = "validationRegex";

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
     * Removed: component schemas rendered as {@code serde_json::Value}. Unions render as typed enums now, so setting it
     * is an error rather than ignored.
     */
    public static final String JSON_VALUE_SCHEMAS = "jsonValueSchemas";
    /** {@code <path>?<name>} query parameters kept as objects sent in deepObject style. */
    public static final String DEEP_OBJECT_QUERY_PARAMETERS = "deepObjectQueryParameters";
    /** Query parameters get the default style (form, exploded), whatever the spec says. */
    public static final String RESET_QUERY_PARAMETER_STYLE = "resetQueryParameterStyle";
    /** Output-relative path the spec is written to after the spec rules ran (JSON; for scripts and debugging). */
    public static final String PREPROCESSED_SPEC_FILE = "preprocessedSpecFile";
    /**
     * Former option, always on: {@code Error::ResponseError} holds {@code Box<ResponseContent<T>>}, so a
     * {@code Result} stays small with the response's headers in it (clippy {@code result_large_err}).
     */
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
    /** Former option, always on: box a model reference only where it closes a cycle (see {@link BoxRules}). */
    public static final String BOX_ONLY_CYCLES = "boxOnlyCycles";
    /** Former option, always on: {@code Default} only where its value is one the API would accept. */
    public static final String DEFAULT_ONLY_WHERE_VALID = "defaultOnlyWhereValid";
    /** Former option, always on: closed enums get {@code FromStr} and {@code TryFrom} (see {@link EnumFromStr}). */
    public static final String ENUM_FROM_STR = "enumFromStr";
    /** Former option, always on: structs have no JSON {@code Display}; the API code calls {@code serde_json::to_string}. */
    public static final String NO_STRUCT_DISPLAY = "noStructDisplay";
    /** Set on a parameter of struct type, which the api template then serializes as JSON. */
    static final String STRUCT_JSON = "x-rust-struct-json";
    /** Set on a parameter holding an array of structs, whose items the api template serializes as JSON. */
    static final String STRUCT_JSON_ITEMS = "x-rust-struct-json-items";
    /** Former option, always on: an integer is {@code i64} unless the spec says {@code format: int32}. */
    public static final String INTEGERS_AS_I64 = "integersAsI64";
    /** Former option, always on: inline schemas are named after a unique title or their location. */
    public static final String QUALIFIED_INLINE_NAMES = "qualifiedInlineNames";
    /** Former option, always on: unions render from the model. */
    public static final String MODEL_UNIONS = "modelUnions";
    /** Former option, always on: a union with a tag decodes by that tag. */
    public static final String UNION_DISPATCH = "unionDispatch";
    /** Former option, always on: enums and tagged unions a response can hold have a catch-all. */
    public static final String OPEN_ENUMS = "openEnums";
    /** Output-relative path the union plan is written to, as JSON (for tools and debugging). */
    public static final String UNION_PLAN_FILE = "unionPlanFile";
    /**
     * A {@code text/event-stream} success response is a method returning a stream of the event schema, decoded from
     * each {@code data:} payload (Milestone 3.6, see {@link ContentTypeMethods}).
     */
    public static final String TYPED_EVENT_STREAMS = "typedEventStreams";
    /** Tower stack: the features of the runtime dependency in Cargo.toml (see {@link #runtimeFeatures}). */
    static final String RUNTIME_FEATURES = "runtimeFeatures";
    /**
     * One method per request body media type and per success response shape, named with a suffix (Milestone 3.6, see
     * {@link ContentTypeMethods}).
     */
    public static final String CONTENT_TYPE_METHODS = "contentTypeMethods";
    /**
     * Every operation whose success is a body (not a file or an event stream) also gets {@code <operation>_response},
     * returning {@code apis::ApiResponse<T>}: status, headers and the undecoded body (decision 1).
     */
    public static final String RESPONSE_FUNCTIONS = "responseFunctions";
    /**
     * How the generated client sends requests: {@code tower} (the default) through {@code timvw_rust_runtime::Client},
     * a tower service that is reqwest unless replaced; {@code reqwest-middleware} through reqwest and
     * reqwest-middleware, as before 0.25.
     */
    public static final String HTTP_STACK = "httpStack";
    /** {@link #HTTP_STACK} value: the client sends through the timvw-rust-runtime crate (tower). */
    public static final String HTTP_STACK_TOWER = "tower";
    /** {@link #HTTP_STACK} value: the client sends through reqwest and reqwest-middleware. */
    public static final String HTTP_STACK_REQWEST_MIDDLEWARE = "reqwest-middleware";
    /** The version requirement of {@code timvw-rust-runtime} in the generated Cargo.toml (with {@code httpStack=tower}). */
    public static final String RUNTIME_VERSION = "runtimeVersion";
    /** The {@code timvw-rust-runtime} release this generator renders for: the default of {@link #RUNTIME_VERSION}. */
    public static final String DEFAULT_RUNTIME_VERSION = "0.3.1";
    /**
     * A path dependency on {@code timvw-rust-runtime} next to the version (with {@code httpStack=tower}), for testing
     * against an unreleased runtime; defaults to the system property {@code timvw-rust.runtimePath}.
     */
    public static final String RUNTIME_PATH = "runtimePath";
    /**
     * Set on every operation: the name of its {@code timvw_rust_runtime::RequestOptions} parameter (with
     * {@code httpStack=tower} and {@code bonBuilders}), {@code options} unless a parameter of the operation has it.
     */
    static final String OPTIONS_PARAM = "x-rust-options-param";
    /**
     * One Cargo feature per group of API operations (a tag, or a group of {@link #API_FEATURES_FILE}), with a
     * {@code -types} twin for its models alone, {@code types} and {@code full} (see {@link ApiFeatures}).
     */
    public static final String API_FEATURES = "apiFeatures";
    /** A YAML or JSON file of feature groups selecting operations by tag and path prefix; implies {@link #API_FEATURES}. */
    public static final String API_FEATURES_FILE = "apiFeaturesFile";
    /** Set on an operation with {@link #RESPONSE_FUNCTIONS}: the context its {@code _response} twin renders with. */
    static final String RESPONSE_FUNCTION = "x-rust-response-function";
    /** Set when an API module has a {@code <Operation>ResponseHeaders} struct: {@code api_response.rs} has its helpers. */
    static final String TYPED_HEADERS = "x-rust-typed-headers";
    /** Set when an API module has a {@code <Operation>ErrorHeaders} struct: {@code ResponseContent} reads them. */
    static final String TYPED_ERROR_HEADERS = "x-rust-typed-error-headers";
    /**
     * Inline enums of parameters and of form or multipart body properties become component enums, so the API function
     * takes the enum instead of {@code &str} (see {@link ExtraRules#typedEnumParameters}).
     */
    public static final String TYPED_ENUM_PARAMETERS = "typedEnumParameters";
    /**
     * Former option, always on: a field whose union is nullable is {@code Option<Option<U>>} like any other nullable
     * field, so a request can send {@code null}.
     */
    public static final String NULLABLE_UNION_FIELDS = "nullableUnionFields";
    /**
     * The unused-helper pruning also drops helpers marked {@code #[allow(dead_code)]}, which are otherwise kept as part
     * of the helper module's API (see {@link HelperPruning}).
     */
    public static final String PRUNE_ALL_HELPERS = "pruneAllHelpers";
    /** Former option, always on: a property's or parameter's schema {@code default} is noted in its documentation. */
    public static final String SCHEMA_DEFAULT_DOCS = "schemaDefaultDocs";
    /** Deprecated properties and schemas get {@code #[deprecated]}; generated files allow it among themselves. */
    public static final String DEPRECATED_ATTRIBUTES = "deprecatedAttributes";
    /**
     * Set on an array parameter of models (enums with {@link #TYPED_ENUM_PARAMETERS}): a header or path value joins the
     * items' {@code Display}, as {@code .join(",")} only joins strings.
     */
    static final String DISPLAY_ITEMS = "x-rust-display-items";
    /**
     * Set on every path, query, header, cookie and form parameter (an urlencoded field or a multipart text part): the
     * method that writes its value, or each item of an array, as text. {@code to_rfc3339} for a {@code date-time}
     * ({@code chrono::DateTime}, whose {@code Display} is not RFC 3339), {@code to_string} otherwise ({@code date} is
     * {@code chrono::NaiveDate}, whose {@code Display} is {@code YYYY-MM-DD}).
     */
    static final String TO_TEXT = "x-rust-to-text";
    /** Set on a path, query, header, cookie or form parameter whose argument is a {@code &str}: it is its own text. */
    static final String STR = "x-rust-str";
    /** Set on an array parameter of strings: an item is its own text. */
    static final String STR_ITEMS = "x-rust-str-items";
    /** Set on an urlencoded form field array whose encoding is pipeDelimited or spaceDelimited: its delimiter. */
    static final String FORM_SEPARATOR = "x-rust-form-separator";
    /** Set on an urlencoded form field array whose encoding says {@code explode: false}. */
    static final String FORM_JOINED = "x-rust-form-joined";
    /** Set on a query or form array that is sent as one pair per item. */
    static final String REPEAT = "x-rust-repeat";
    /** Set on a query or form array: the delimiter its items are joined with when it is not repeated. */
    static final String SEPARATOR = "x-rust-separator";
    /** Set on an operation that sends a {@code Cookie} header (see {@link #markCookies}). */
    static final String COOKIES = "x-rust-cookies";
    /** Set when an operation sends a {@code Cookie} header: {@code apis/mod.rs} then has {@code cookie_encode}. */
    static final String USES_COOKIES = "usesCookies";
    /** Set on a path, query or header parameter whose value, or whose items, are a {@code chrono::DateTime}. */
    static final String RFC3339 = "x-rust-rfc3339";
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
    /**
     * Set on a request body the function only serializes: the borrowed type its argument has ({@code &models::Item},
     * {@code &[models::Item]}, {@code &serde_json::Value}), without the {@code Option} around an optional one.
     */
    static final String BODY_REF = "x-rust-body-ref";
    /** Set with {@link #BODY_REF} on a required, non-nullable body: the argument itself is the reference. */
    static final String BODY_REF_DIRECT = "x-rust-body-ref-direct";
    /** Key under which the api template finds whether its module uses the multipart helper. */
    static final String MULTIPART_HELPER_IMPORT = "multipartHelperImport";
    /**
     * Set on a parameter whose dataType is already a path: {@code models::X}, or the type a schema mapping names
     * ({@code crate::custom::Filter}).
     */
    static final String TYPE_QUALIFIED = "x-rust-type-qualified";
    /** Key under which the api template finds the module {@code default_api} re-exports. */
    static final String WEBHOOK_REEXPORT_KEY = "webhookReexport";
    /** Key under which the api mod template finds the webhook module files to declare. */
    static final String WEBHOOK_MODULES_KEY = "webhookModules";

    /** Mustache lambda that templates wrap around doc-comment text. */
    static final String RUSTDOC_LAMBDA = "rustdoc";

    /** Options of stock {@code -g rust} whose value timvw-rust fixes (see {@link #FIXED_VALUES}). */
    public static final String SUPPORT_ASYNC = "supportAsync";
    public static final String SUPPORT_MIDDLEWARE = "supportMiddleware";
    public static final String PREFER_UNSIGNED_INT = "preferUnsignedInt";
    public static final String BEST_FIT_INT = "bestFitInt";
    /** Options of stock {@code -g rust} that timvw-rust does not support (see {@link #REJECTED_OPTIONS}). */
    public static final String AVOID_BOXED_MODELS = "avoidBoxedModels";
    public static final String MOCKALL = "mockall";
    public static final String TOP_LEVEL_API_CLIENT = "topLevelApiClient";
    public static final String SUPPORT_TOKEN_SOURCE = "supportTokenSource";
    public static final String BON_BUILDER = "useBonBuilder";

    /**
     * Options of stock {@code -g rust} with the value timvw-rust always generates for: not advertised, and rejected
     * when set, whatever the value, so a configuration taken over from {@code -g rust} fails instead of being
     * silently ignored. The generator only renders the async reqwest-middleware client.
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

    /**
     * The options that were the mapping's baseline (decision 12), and {@code boxResponseError} (decision 4): their
     * {@code true} rendering is always applied, and setting one, to any value, fails generation like a
     * {@link #FIXED_VALUES fixed option}.
     */
    static final List<String> ALWAYS_ON = List.of(
            BOX_ONLY_CYCLES,
            DEFAULT_ONLY_WHERE_VALID,
            ENUM_FROM_STR,
            NO_STRUCT_DISPLAY,
            INTEGERS_AS_I64,
            QUALIFIED_INLINE_NAMES,
            MODEL_UNIONS,
            UNION_DISPATCH,
            OPEN_ENUMS,
            SCHEMA_DEFAULT_DOCS,
            NULLABLE_UNION_FIELDS,
            BOX_RESPONSE_ERROR);

    /** The openapi-generator version the generator runs in, e.g. {@code 7.27.0}. */
    private final String openApiGeneratorVersion;
    private boolean titleNaming = true;
    private String webhookModule;
    private boolean webhookModuleReexport;
    private List<String> stringIdSchemas = new ArrayList<>();
    private List<String> stringIdProperties = new ArrayList<>();
    private List<String> stringProperties = new ArrayList<>();
    /** The unions that are nullable, whose parameters take an {@code Option} (see {@link ExtraRules#nullableUnions}). */
    private final Set<String> nullableUnionModels = new HashSet<>();
    private List<String> deepObjectQueryParameters = new ArrayList<>();
    private String preprocessedSpecFile;
    private boolean resetQueryParameterStyle;
    private boolean multipartHelper;
    /** {@code httpStack=tower}: file parameters are {@code timvw_rust_runtime::Upload}s. */
    private boolean httpStackTower;
    private boolean bonBuilders;
    private String preprocessedSpecJson;
    private List<String> fileProperties = new ArrayList<>();
    private final Set<String> structJsonModels = new java.util.TreeSet<>();
    private boolean anyDispatch;
    private boolean typedEnumParameters;
    private boolean deprecatedAttributes;
    private boolean pruneAllHelpers;
    private final Set<String> deprecatedModels = new java.util.TreeSet<>();
    /**
     * The file (without {@code .rs}) each model is written to, by its Rust type name. The file is named after the
     * schema ({@code toModelFilename} of the schema name), the type is {@code toModelName} of it: with a model name
     * prefix, suffix or mapping, {@code toModelFilename} of the type name is not the file.
     */
    private final Map<String, String> modelFiles = new HashMap<>();
    private OpenEnums.Census openEnumsCensus;
    private String unionPlanFile;
    private boolean validateMethods = true;
    private final Map<String, CodegenModel> unionModels = new HashMap<>();
    private boolean typedEventStreams;
    /** Some operation got a typed event stream: only then are {@code sse.rs} and its {@code mod} line written. */
    private boolean anyEventStream;
    private boolean contentTypeMethods;
    private boolean responseFunctions;
    /** The methods added for an operation, by its own method, which gets its auth methods only after it was grouped. */
    private final Map<CodegenOperation, List<CodegenOperation>> contentVariants = new java.util.IdentityHashMap<>();
    /**
     * The path APIs by class name, as rendered: openapi-generator writes the webhooks after them into the file of
     * their tag, so a webhook module that shares its name with one is rendered with the path operations too.
     */
    private final Map<String, OperationsMap> pathApis = new HashMap<>();
    /** With {@link #RESPONSE_FUNCTIONS}: the spec operation of each method, for its typed headers. */
    private final Map<CodegenOperation, io.swagger.v3.oas.models.Operation> specOperations =
            new java.util.IdentityHashMap<>();
    private boolean apiFeatures;
    private List<ApiFeatures.Group> apiFeatureGroups = List.of();
    /** With {@link #API_FEATURES}: the features of the operations and models, planned once the spec is final. */
    private ApiFeatures.Plan apiFeaturesPlan;
    /** The methods per API module ({@code tag#operationId}), to fail on a suffixed name another operation has. */
    private final Map<String, String> methodNames = new HashMap<>();

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

        cliOptions.add(CliOption.newBoolean(BON_BUILDERS,
                "Derive bon::Builder on model structs and put #[bon::builder] on API functions", true));
        cliOptions.add(CliOption.newBoolean(VALIDATE_METHODS,
                "Every model implements the generated models::validation::Validate trait: validate() checks minLength, "
                        + "maxLength, pattern (with the regex crate), minimum, maximum, exclusiveMinimum, "
                        + "exclusiveMaximum, multipleOf, minItems, maxItems, uniqueItems, minProperties and "
                        + "maxProperties, in every nested model, and returns every violation. Nothing calls it. "
                        + "Set to false to generate no trait, no impls and no regex dependency", true));
        cliOptions.add(CliOption.newBoolean(TITLE_NAMING,
                "Let schema titles take part in type naming: titled component schemas of the same shape are merged, "
                        + "inline schemas matching a titled component are replaced by it, and an inline array's title "
                        + "names its item type. Set to false to derive names from schema keys and property paths only",
                true));
        cliOptions.add(CliOption.newString(WEBHOOK_MODULE,
                "Tag given to every webhook operation, so that webhooks are generated into their own API module "
                        + "instead of the API module of their tag (default_api when untagged), where they follow the path operations. "
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
                "Send multipart bodies through a generated src/apis/multipart_helper.rs: a file is a part "
                        + "named after the file, optional arrays send one part per item and optional objects one JSON "
                        + "part, and an encoding contentType of a scalar part is applied", false));
        cliOptions.add(CliOption.newString(FILE_PROPERTIES,
                "Schema.property entries rendered as a file (type: string, format: binary)"));
        cliOptions.add(CliOption.newString(API_LINT_ALLOWS,
                "Lints allowed at the top of every API module and configuration.rs, written as "
                        + "#![allow(<value>)], e.g. 'clippy::too_many_arguments, clippy::large_enum_variant'"));
        cliOptions.add(CliOption.newBoolean(USER_AGENT_FROM_PACKAGE,
                "Default user agent <packageName>/<crate version> (CARGO_PKG_VERSION) instead of "
                        + "OpenAPI-Generator/<spec version>/rust; httpUserAgent still wins", false));
        cliOptions.add(CliOption.newString(PREPROCESSED_SPEC_FILE,
                "Write the spec as it stands after the spec rules (before inline schemas are named) to this "
                        + "output-relative path, as JSON"));
        cliOptions.add(CliOption.newString(UNION_PLAN_FILE,
                "Write the variants of every union and the schemas they refer to to this "
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
        cliOptions.add(CliOption.newBoolean(RESPONSE_FUNCTIONS,
                "Every operation whose success is a body also gets <operation>_response, which sends the same request "
                        + "and fails the same way but returns apis::ApiResponse<T>: the status, the headers and the "
                        + "undecoded body, with json() (the generated type), json_as::<U>() (a type of the "
                        + "caller's), json_borrowed::<U>() (borrowing from the body) and text()", false));
        cliOptions.add(CliOption.newBoolean(TYPED_ENUM_PARAMETERS,
                "Inline enums of query, path, header and cookie parameters, and of the properties of form and "
                        + "multipart bodies, become component enums (<operationId>_<parameter>, <Schema>_<property>), so "
                        + "the API function takes the enum instead of &str", false));
        cliOptions.add(CliOption.newBoolean(PRUNE_ALL_HELPERS,
                "Helpers of union_dispatch.rs and multipart_helper.rs that nothing calls are always left out; with "
                        + "this option also those marked #[allow(dead_code)], which are otherwise kept", false));
        cliOptions.add(CliOption.newBoolean(DEPRECATED_ATTRIBUTES,
                "Deprecated properties and schemas get #[deprecated]; every generated model and API file starts with "
                        + "#![allow(deprecated)], so only callers are warned", false));
        CliOption httpStack = new CliOption(HTTP_STACK,
                "How the client sends requests: 'tower' sends through timvw_rust_runtime::Client, a tower service "
                        + "(reqwest by default) that takes tower layers and can be replaced; 'reqwest-middleware' sends "
                        + "through reqwest and reqwest-middleware").defaultValue(HTTP_STACK_TOWER);
        httpStack.addEnum(HTTP_STACK_TOWER, "tower service from the timvw-rust-runtime crate (default)");
        httpStack.addEnum(HTTP_STACK_REQWEST_MIDDLEWARE, "reqwest with reqwest-middleware");
        cliOptions.add(httpStack);
        cliOptions.add(new CliOption(RUNTIME_VERSION,
                "With httpStack=tower: the version requirement of timvw-rust-runtime in Cargo.toml")
                .defaultValue(DEFAULT_RUNTIME_VERSION));
        cliOptions.add(CliOption.newString(RUNTIME_PATH,
                "With httpStack=tower: a path dependency on timvw-rust-runtime (next to the version), to build "
                        + "against an unreleased runtime"));
        cliOptions.add(CliOption.newBoolean(API_FEATURES,
                "One Cargo feature per API group (a tag by default, e.g. vector-stores; untagged operations are "
                        + "default-api), each with a <group>-types twin enabling only its models (no HTTP client), plus "
                        + "types (every model) and full (everything, a default feature). Models and API modules or "
                        + "functions get #[cfg(feature = ...)]", false));
        cliOptions.add(CliOption.newString(API_FEATURES_FILE,
                "With apiFeatures (implied): a YAML or JSON file mapping a feature name to the 'tags' and/or 'paths' "
                        + "(path prefixes) of its operations; operations no group selects keep their tag's feature"));
        cliOptions.add(CliOption.newString(DEEP_OBJECT_QUERY_PARAMETERS,
                "<path>?<name> query parameters kept as objects and sent in deepObject style; every other "
                        + "object-typed query parameter is sent as a string"));
    }

    @Override
    public String getName() {
        return GENERATOR_NAME;
    }

    @Override
    public String getHelp() {
        return "Generates an async Rust client (a tower service from timvw-rust-runtime, or reqwest with "
                + "reqwest-middleware) whose public API is determined by "
                + "the spec, the options and the generator version.";
    }

    /**
     * The openapi-generator version the generator was built and tested against, or {@code null} when unknown (read
     * from {@code timvw-rust.properties}, written by the Maven build).
     */
    static String builtAgainst() {
        try (java.io.InputStream in = TimvwRustCodegen.class.getResourceAsStream("/timvw-rust.properties")) {
            if (in == null) {
                return null;
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("openapi-generator.version");
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** {@code major.minor} of a version, or the version itself when it has no minor. */
    static String minorOf(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length < 2 ? version : parts[0] + "." + parts[1];
    }

    /**
     * An integer the base mapping ({@link RustCodegenBase#getSchemaType}) renders as {@code i32} is {@code i64} unless
     * its format is {@code int32}. Every location (fields, container items, parameters, responses, union variants)
     * gets its type from here. The schema flags are left alone, so {@code isInteger}/{@code isLong} still select the
     * integer enum rendering, through {@link RustCodegenBase#isIntegerEnum} and {@code x-rust-integer-enum}. The base
     * mapping also maps the formats {@code int8}, {@code int16} and {@code uint8} to {@code uint64} to sized and
     * unsigned types, which become {@code i64} as well.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public String getSchemaType(Schema p) {
        String type = super.getSchemaType(p);
        return NARROW_INTEGER_TYPES.contains(type) && !"int32".equals(p.getFormat()) ? "i64" : type;
    }

    /** The integer types other than {@code i64} the base mapping can answer, which {@link #getSchemaType} widens. */
    private static final Set<String> NARROW_INTEGER_TYPES = Set.of("i8", "i16", "i32", "u8", "u16", "u32", "u64");

    /** The model name the base generator gives a composed schema without typed branches ({@code anyOf<>}). */
    static final String UNTYPED_COMPOSED = "AnyOfLessThanGreaterThan";
    static final String UNTYPED_COMPOSED_TYPE = "models::" + UNTYPED_COMPOSED;
    static final String JSON_VALUE = "serde_json::Value";

    /** Names the variants of a union from its schema, and types an untyped variant as {@code serde_json::Value}. */
    @Override
    @SuppressWarnings("rawtypes")
    public CodegenModel fromModel(String name, Schema model) {
        CodegenModel mdl = super.fromModel(name, model);
        retypeUntypedVariants(mdl);
        UnionRules.nameVariants(mdl, name, model, this::toVariantName);
        return mdl;
    }

    /** The models that get a doc ({@code docs/<Model>.md}), known once the models are processed. */
    private final Set<String> documentedModels = new HashSet<>();

    /** Marks every model property with {@link BoxRules#BOX} and every model with {@link DefaultRules#DERIVE_DEFAULT}. */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        objs = super.postProcessAllModels(objs);
        List<String> ownFields = new ArrayList<>();
        Map<String, Map<UnionRules.Kind, UnionRules.Accepts>> mapped = UnionRules.mappedShapes(openAPI, schemaMapping);
        Map<String, CodegenModel> unions = UnionRules.plan(objs,
                msg -> LOGGER.warn("{}: unions: {}", GENERATOR_NAME, msg), ownFields::add, mapped);
        // A field declared next to oneOf/anyOf that a variant lacks: one line per field is thousands for
        // openai-client-base, so one warning with the count, and each field at debug.
        ownFields.forEach(msg -> LOGGER.debug("{}: unions: {}", GENERATOR_NAME, msg));
        if (!ownFields.isEmpty()) {
            LOGGER.warn("{}: unions: {} fields declared on a union itself are not rendered, as a variant does not "
                    + "carry them (debug logging lists them)", GENERATOR_NAME, ownFields.size());
        }
        LOGGER.info("{}: {} fields of a nullable union made nullable", GENERATOR_NAME,
                ExtraRules.nullableUnionFields(objs, unions, openAPI));
        nullableUnionModels.addAll(ExtraRules.nullableUnions(unions, openAPI));
        if (deprecatedAttributes) {
            deprecatedModels.addAll(ExtraRules.markDeprecated(objs));
        }
        BoxRules.mark(objs);
        UnionRules.payloads(unions, objs);
        unionModels.putAll(unions);
        UnionRules.DispatchCounts counts = UnionRules.dispatch(unions, objs, openAPI, mapped);
        if (httpStackTower && UploadParams.markUnions(unions)) {
            // `From<PathBuf>` for the unions: Upload::path.
            additionalProperties.put(UploadParams.RUNTIME_FS, true);
        }
        anyDispatch = counts.explicit() + counts.implicit() > 0;
        LOGGER.info("{}: union dispatch: {} tagged ({} by a discriminator, {} implicitly), {} untagged; {} have a value "
                        + "on two variants, {} allow the tag to be missing", GENERATOR_NAME,
                counts.explicit() + counts.implicit(), counts.explicit(), counts.implicit(), counts.untagged(),
                counts.sharedValue(), counts.optional());
        openEnumsCensus = OpenEnums.mark(objs, unions, openAPI, mapped);
        LOGGER.info("{}: open enums: {} enums, {} reachable, {} of them guarded; {} tagged unions, {} reachable, {} of "
                        + "them guarded; {} untagged unions left as they are", GENERATOR_NAME,
                openEnumsCensus.enums().size(), openEnumsCensus.reachableEnums().size(),
                openEnumsCensus.guardedEnums().size(), openEnumsCensus.taggedUnions().size(),
                openEnumsCensus.reachableTagged().size(), openEnumsCensus.guardedUnions().size(),
                openEnumsCensus.untaggedUnions().size());
        writeUnionPlan(unions);
        if (validateMethods) {
            ValidateRules.checkModuleName(objs);
        }
        ValidateRules.Result validation = ValidateRules.apply(objs, validateMethods,
                msg -> LOGGER.warn("{}: validate: {}", GENERATOR_NAME, msg));
        if (validateMethods) {
            additionalProperties.put(VALIDATION_REGEX, validation.patterns() > 0);
            LOGGER.info("{}: validate: {} models, {} of them check something; {} patterns, {} skipped",
                    GENERATOR_NAME, validation.models(), validation.checking(), validation.patterns(),
                    validation.skippedPatterns().size());
        }
        DefaultRules.mark(objs);
        Imports.markModels(objs);
        documentedModels.addAll(DocLinks.markModels(objs));
        modelFiles.putAll(modelFiles(objs));
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
            Map<String, Object> described = UnionRules.describe(unions, openAPI);
            described.put(OPEN_ENUMS, openEnumsCensus.describe());
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

    /**
     * Types a property whose schema is a composed schema without typed branches ({@code anyOf: [{}, {}]}; a
     * {@code [{}, null]} one is already {@code {}}, see {@link NullSchemas}) as {@code serde_json::Value}. The base generator derives the model name {@code AnyOfLessThanGreaterThan} from
     * {@code anyOf<>} and references it, but never writes it. The property keeps its other flags, so a required
     * nullable field stays {@code Option<Box<serde_json::Value>>} as before. Every property list of the model holds
     * its own copies, so all of them are visited.
     */
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
     * Validates the configuration before the base generator reads its options.
     *
     * @throws IllegalArgumentException naming the offending option and the supported value
     */
    @Override
    public void processOpts() {
        String built = builtAgainst();
        if (built != null && !java.util.Objects.equals(minorOf(built), minorOf(openApiGeneratorVersion))) {
            LOGGER.warn("{}: built and tested against openapi-generator {}, running in {}; the output may differ",
                    GENERATOR_NAME, built, openApiGeneratorVersion);
        }

        // The CLI applies `--library` through setLibrary() rather than additionalProperties. The constructor already
        // sets the default library, so through the setter only another library can be told apart.
        if (getLibrary() != null && !REQWEST_LIBRARY.equals(getLibrary())) {
            throw new IllegalArgumentException(String.format(
                    "%s: option '%s' is not configurable: %s always generates for '%s'; remove it (it was set to '%s')",
                    GENERATOR_NAME, CodegenConstants.LIBRARY, GENERATOR_NAME, REQWEST_LIBRARY, getLibrary()));
        }

        // These keys are matched ignoring case, so that `BoxOnlyCycles` gets the message of `boxOnlyCycles` rather
        // than the unknown-option one.
        for (String option : REJECTED_OPTIONS) {
            String key = keyIgnoringCase(option);
            if (key != null) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not supported; remove it (it was set to '%s')",
                        GENERATOR_NAME, key, additionalProperties.get(key)));
            }
        }

        for (String option : ALWAYS_ON) {
            String key = keyIgnoringCase(option);
            if (key != null) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not configurable: %s always uses 'true'; remove it (it was set to '%s')",
                        GENERATOR_NAME, key, GENERATOR_NAME, additionalProperties.get(key)));
            }
        }

        for (Map.Entry<String, String> entry : FIXED_VALUES.entrySet()) {
            String key = keyIgnoringCase(entry.getKey());
            if (key != null) {
                throw new IllegalArgumentException(String.format(
                        "%s: option '%s' is not configurable: %s always uses '%s'; remove it (it was set to '%s')",
                        GENERATOR_NAME, key, GENERATOR_NAME, entry.getValue(), additionalProperties.get(key)));
            }
        }

        // After the options with a message of their own, so those keep it; the removed options get theirs below.
        List<String> notSuggested = new ArrayList<>(ALWAYS_ON);
        notSuggested.addAll(FIXED_VALUES.keySet());
        notSuggested.addAll(REJECTED_OPTIONS);
        notSuggested.add(JSON_VALUE_SCHEMAS);
        notSuggested.add(LEGACY_MODEL_REWRITES);
        UnknownOptions.check(GENERATOR_NAME, additionalProperties.keySet(), cliOptions(),
                List.of(JSON_VALUE_SCHEMAS, LEGACY_MODEL_REWRITES), notSuggested);

        setLibrary(REQWEST_LIBRARY);
        super.processOpts();

        boolean bonBuilders = !additionalProperties.containsKey(BON_BUILDERS)
                || convertPropertyToBooleanAndWriteBack(BON_BUILDERS);
        additionalProperties.put(BON_BUILDERS, bonBuilders);
        this.bonBuilders = bonBuilders;

        validateMethods = !additionalProperties.containsKey(VALIDATE_METHODS)
                || convertPropertyToBooleanAndWriteBack(VALIDATE_METHODS);
        additionalProperties.put(VALIDATE_METHODS, validateMethods);
        if (validateMethods) {
            supportingFiles.add(new SupportingFile("validation.mustache", modelFolder, "validation.rs"));
        }

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
            throw new IllegalArgumentException(String.format("%s: %s was removed: unions render as typed enums; drop "
                    + "the option", GENERATOR_NAME, JSON_VALUE_SCHEMAS));
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
        typedEnumParameters = additionalProperties.containsKey(TYPED_ENUM_PARAMETERS)
                && convertPropertyToBooleanAndWriteBack(TYPED_ENUM_PARAMETERS);
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
        responseFunctions = additionalProperties.containsKey(RESPONSE_FUNCTIONS)
                && convertPropertyToBooleanAndWriteBack(RESPONSE_FUNCTIONS);
        additionalProperties.put(RESPONSE_FUNCTIONS, responseFunctions);
        if (responseFunctions) {
            supportingFiles.add(new SupportingFile("api_response.mustache", apiFolder, "api_response.rs"));
            additionalProperties.put("x-rust-crate", String.valueOf(additionalProperties.get("packageName"))
                    .replace('-', '_'));
        }
        Object stack = additionalProperties.get(HTTP_STACK);
        String httpStack = stack == null || String.valueOf(stack).isEmpty() ? HTTP_STACK_TOWER : String.valueOf(stack);
        if (!HTTP_STACK_TOWER.equals(httpStack) && !HTTP_STACK_REQWEST_MIDDLEWARE.equals(httpStack)) {
            throw new IllegalArgumentException(String.format("%s: option '%s' is '%s'; use '%s' or '%s'",
                    GENERATOR_NAME, HTTP_STACK, httpStack, HTTP_STACK_TOWER, HTTP_STACK_REQWEST_MIDDLEWARE));
        }
        boolean tower = HTTP_STACK_TOWER.equals(httpStack);
        additionalProperties.put(HTTP_STACK, httpStack);
        additionalProperties.put("httpStackTower", tower);
        httpStackTower = tower;
        additionalProperties.put("httpCrate", tower ? "timvw_rust_runtime" : "reqwest");
        additionalProperties.put("httpErrorVariant", tower ? "Http" : "Reqwest");
        Object runtimeVersion = additionalProperties.get(RUNTIME_VERSION);
        additionalProperties.put(RUNTIME_VERSION, runtimeVersion == null || String.valueOf(runtimeVersion).isEmpty()
                ? DEFAULT_RUNTIME_VERSION : String.valueOf(runtimeVersion));
        Object runtimePath = additionalProperties.get(RUNTIME_PATH);
        String path = runtimePath == null || String.valueOf(runtimePath).isEmpty()
                ? System.getProperty("timvw-rust.runtimePath") : String.valueOf(runtimePath);
        if (path == null || path.isEmpty()) {
            additionalProperties.remove(RUNTIME_PATH);
        } else {
            additionalProperties.put(RUNTIME_PATH, path.replace('\\', '/'));
        }

        Object featuresFile = additionalProperties.get(API_FEATURES_FILE);
        boolean withFile = featuresFile != null && !String.valueOf(featuresFile).isEmpty();
        apiFeatures = additionalProperties.containsKey(API_FEATURES) && convertPropertyToBooleanAndWriteBack(API_FEATURES);
        if (withFile && additionalProperties.containsKey(API_FEATURES) && !apiFeatures) {
            throw new IllegalArgumentException(String.format("%s: %s is set, but %s is false; drop one of them",
                    GENERATOR_NAME, API_FEATURES_FILE, API_FEATURES));
        }
        apiFeatures |= withFile;
        if (withFile) {
            apiFeatureGroups = ApiFeatures.read(java.nio.file.Paths.get(String.valueOf(featuresFile)));
        }
        additionalProperties.put(API_FEATURES, apiFeatures);

        Object plan = additionalProperties.get(UNION_PLAN_FILE);
        unionPlanFile = plan == null || String.valueOf(plan).isEmpty() ? null : String.valueOf(plan);
        for (String flag : Arrays.asList(USER_AGENT_FROM_PACKAGE)) {
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

    /** The key of {@code additionalProperties} equal to {@code option} ignoring case, or {@code null}. */
    private String keyIgnoringCase(String option) {
        if (additionalProperties.containsKey(option)) {
            return option;
        }
        for (String key : additionalProperties.keySet()) {
            if (key.equalsIgnoreCase(option)) {
                return key;
            }
        }
        return null;
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
        if (openAPI.getInfo() != null && openAPI.getInfo().getDescription() != null
                && !openAPI.getInfo().getDescription().isBlank()) {
            additionalProperties.put(ReadmeText.DESCRIPTION,
                    ReadmeText.withoutDoctests(openAPI.getInfo().getDescription().strip()));
        }
        // The spec rules edit the document as a file would be edited, so they run on a non-resolving parse of the
        // input file: the resolving parse the CLI did shares one Schema instance between identical subtrees (two
        // components with the same inline union hold the same object) and drops `required` names that are not
        // properties of the same object (names inherited through allOf). The result is then parsed again as the
        // CLI parses its input and takes the place of the document the generator holds.
        OpenAPI working = getInputSpec() != null ? parseFile(getInputSpec(), false) : reparse(openAPI, false);
        for (String where : NullSchemas.rewrite(working)) {
            LOGGER.info("{}: read a schema of only null, or of any value or null, as any value at {}",
                    GENERATOR_NAME, where);
        }
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
        UnionRules.pinArrayTitles(openAPI);
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
            flattenWithQualifiedNames(openAPI);
        }
        LOGGER.info("{}: {} string enum branches of unions became models", GENERATOR_NAME,
                UnionRules.hoistEnumBranches(openAPI, this::toVariantName));
        super.preprocessOpenAPI(openAPI);
        for (String where : SpecNormalizer.integerWithFloatFormatToNumber(openAPI)) {
            LOGGER.warn("{}: read 'type: integer' with a float format as 'type: number' at {}", GENERATOR_NAME, where);
        }
        if (apiFeatures) {
            // Fails on a groups file that does not fit the spec here, where the error is not wrapped; the plan the
            // templates use is made again once the models are processed.
            apiFeaturesPlan();
            apiFeaturesPlan = null;
        }
    }

    /**
     * Names the inline schemas, then runs the resolver between two checks: the first sees the schemas the resolver
     * would overwrite, the second the models it really made.
     */
    private void flattenWithQualifiedNames(OpenAPI openAPI) {
        InlineNames.Plan plan = InlineNames.qualify(openAPI, inlineSchemaOption(), this::toModelName,
                this::toModelFilename);
        LOGGER.info("{}: inline names: {} models planned, {} titles name a model, {} inline schemas are one instance: {}",
                GENERATOR_NAME, plan.sites().size(), plan.kept().size(), plan.shared().size(),
                plan.shared());
        plan.qualified().forEach((title, why) -> LOGGER.info("{}: inline names: title {} names no model: {}",
                GENERATOR_NAME, title, why));
        failOnCollisions(NameCollisions.beforeFlattening(openAPI, plan.sites(), this::toModelName,
                this::toModelFilename));
        InlineModelResolverAccess.flatten(openAPI, inlineSchemaNameMapping(), inlineSchemaOption());
        failOnCollisions(NameCollisions.afterFlattening(openAPI, plan.sites(), this::toModelName,
                this::toModelFilename));
    }

    private static void failOnCollisions(List<String> collisions) {
        if (!collisions.isEmpty()) {
            throw new IllegalArgumentException(String.format("%s: different schemas would produce the same Rust "
                            + "name (%d); fix this upstream, the generator does not pick a winner:%n  %s",
                    GENERATOR_NAME, collisions.size(),
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
        LOGGER.info("{}: {} defaults noted in the documentation", GENERATOR_NAME,
                ExtraRules.documentDefaults(openAPI));
        if (webhookModule != null) {
            int routed = SpecRules.routeWebhooks(openAPI, webhookModule);
            LOGGER.info("{}: {}: routed {} webhook operations to tag {}", GENERATOR_NAME, WEBHOOK_MODULE, routed,
                    webhookModule);
        }
        SpecRules.stringSchemas(openAPI, stringIdSchemas, log);
        SpecRules.stringIdProperties(openAPI, stringIdProperties, SpecRules.names(stringIdSchemas), log);
        for (String u : SpecRules.unwrapNullableParameters(openAPI)) {
            LOGGER.info("{}: parameter {} takes the non-null branch of its union", GENERATOR_NAME, u);
        }
        int inlined = SpecRules.inlineStringRefsInUnions(openAPI);
        int pruned = SpecRules.pruneGeneratorHostileKeywords(openAPI);
        int stripped = SpecRules.stripRefSiblings(openAPI);
        int collapsed = SpecRules.collapseRequiredOnlyUnions(openAPI);
        int titled = SpecRules.titleUnnamedUnionVariants(openAPI);
        List<String> hoisted = SpecRules.hoistInlineUnions(openAPI);
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
     * {@code src/apis/mod.rs} declares the webhook module: the template lists only the path APIs
     * ({@code apiInfo.apis}), while webhooks are bundled apart under {@code webhooks}. Without {@code webhookModule}
     * the webhook APIs join {@code apiInfo.apis} (see {@link #addWebhookApis}).
     */
    @Override
    public Map<String, Object> postProcessSupportingFileData(Map<String, Object> objs) {
        objs = super.postProcessSupportingFileData(objs);
        if (webhookModule == null) {
            addWebhookApis(objs);
        }
        if (webhookModule != null && objs.get("webhooks") instanceof List && !((List<?>) objs.get("webhooks")).isEmpty()) {
            List<?> webhooks = (List<?>) objs.get("webhooks");
            if (webhooks.size() > 1) {
                LOGGER.warn("{}: {} webhook API modules generated; only {} is declared in mod.rs (webhooks with "
                        + "their own tags are not supported)", GENERATOR_NAME, webhooks.size(), webhookModule);
            }
            objs.put(WEBHOOK_MODULES_KEY, Arrays.asList(toApiFilename(webhookModule)));
        }
        if (apiFeatures) {
            describeApiFeatures(objs);
        }
        if (typedEventStreams && !anyEventStream) {
            // No operation streams: no sse.rs, no `pub mod sse;` and no stream dependencies in Cargo.toml.
            objs.put(TYPED_EVENT_STREAMS, false);
            supportingFiles.removeIf(f -> "sse.rs".equals(f.getDestinationFilename()));
            LOGGER.info("{}: {}: no operation has a text/event-stream response, so sse.rs is not written",
                    GENERATOR_NAME, TYPED_EVENT_STREAMS);
        }
        if (httpStackTower) {
            objs.put(RUNTIME_FEATURES, runtimeFeatures(objs));
        }
        return objs;
    }

    /**
     * The features of the runtime dependency (tower stack), quoted and comma-separated for Cargo.toml, or {@code null}
     * for none: {@code fs} when a file parameter takes a path ({@code Upload::path}; the runtime leaves it out on
     * WebAssembly), {@code time} for the idle timeout of typed event streams.
     */
    private static String runtimeFeatures(Map<String, Object> objs) {
        List<String> features = new ArrayList<>();
        if (Boolean.TRUE.equals(objs.get(UploadParams.RUNTIME_FS))) {
            features.add("\"fs\"");
        }
        if (Boolean.TRUE.equals(objs.get(TYPED_EVENT_STREAMS))) {
            features.add("\"time\"");
        }
        return features.isEmpty() ? null : String.join(", ", features);
    }

    /** The features of the operations and models, planned on first use: the spec no longer changes by then. */
    private ApiFeatures.Plan apiFeaturesPlan() {
        if (apiFeaturesPlan == null) {
            apiFeaturesPlan = ApiFeatures.plan(openAPI, apiFeatureGroups,
                    new ApiFeatures.Naming(this::sanitizeTag, this::toApiName, this::toApiFilename));
            LOGGER.info("{}: {}: {} features: {}", GENERATOR_NAME, API_FEATURES, apiFeaturesPlan.features.size(),
                    apiFeaturesPlan.features);
        }
        return apiFeaturesPlan;
    }

    /** With {@code apiFeatures}: the cfg of an API module's items and of its operations' items. */
    private void markApiFeatures(Map<String, Object> module, String classname, List<CodegenOperation> operations) {
        ApiFeatures.Plan plan = apiFeaturesPlan();
        String cfg = plan.moduleCfg(classname);
        if (cfg != null) {
            module.put(ApiFeatures.MODULE_CFG, cfg);
        } else {
            LOGGER.warn("{}: {}: API module {} has no operation the spec lists; it is not gated", GENERATOR_NAME,
                    API_FEATURES, classname);
        }
        for (CodegenOperation op : operations) {
            String own = plan.operationCfg(classname, op.path, op.httpMethod);
            if (own != null) {
                op.vendorExtensions.put(ApiFeatures.OPERATION_CFG, own);
                if (httpStackTower) {
                    // Some feature sets compile none of the functions that use an import: the module allows it, so
                    // a crate's own lib.rs needs no `allow(unused_imports)`.
                    module.put(ApiFeatures.FUNCTION_GATED, true);
                }
            }
        }
    }

    /**
     * With {@code apiFeatures}: the features for Cargo.toml and the README, the cfg of every model, of the webhook
     * module and of its re-export.
     */
    private void describeApiFeatures(Map<String, Object> objs) {
        ApiFeatures.Plan plan = apiFeaturesPlan();
        // A union with a file variant holds a timvw_rust_runtime::Upload: the features that compile it without the
        // API functions need the runtime too.
        Set<String> uploadFeatures = new HashSet<>();
        if (objs.get("models") instanceof List) {
            for (Object m : (List<?>) objs.get("models")) {
                if (m instanceof ModelMap && ((ModelMap) m).getModel() != null
                        && ((ModelMap) m).getModel().vendorExtensions.containsKey(UploadParams.FILE_UNION)) {
                    SortedSet<String> f = plan.models.get(((ModelMap) m).getModel().name);
                    if (f == null || f.isEmpty()) {
                        uploadFeatures.add(ApiFeatures.TYPES);
                    } else if (f.equals(plan.features)) {
                        // An ungated model: the runtime is no longer optional.
                        objs.put(UploadParams.RUNTIME_REQUIRED, true);
                    } else {
                        uploadFeatures.addAll(f);
                    }
                }
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String f : plan.features) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", f);
            g.put("selects", plan.selects.get(f));
            g.put(UploadParams.TYPES_NEED_RUNTIME, uploadFeatures.contains(f) && !objs.containsKey(
                    UploadParams.RUNTIME_REQUIRED));
            groups.add(g);
        }
        objs.put("apiFeatureGroups", groups);
        List<String> full = new ArrayList<>(plan.features);
        full.add(ApiFeatures.TYPES);
        objs.put("apiFeaturesFull", full);
        List<String> types = new ArrayList<>(plan.features.stream().map(f -> f + ApiFeatures.TYPES_SUFFIX).toList());
        if (uploadFeatures.contains(ApiFeatures.TYPES) && !objs.containsKey(UploadParams.RUNTIME_REQUIRED)) {
            types.add("dep:timvw-rust-runtime");
        }
        objs.put("apiFeaturesTypes", types);
        if (webhookModule != null) {
            String cfg = plan.moduleCfg(toApiName(sanitizeTag(webhookModule)));
            if (cfg != null) {
                objs.put("webhookModuleCfg", cfg);
            }
        }
        if (objs.get("models") instanceof List) {
            int gated = 0;
            for (Object m : (List<?>) objs.get("models")) {
                if (m instanceof ModelMap && ((ModelMap) m).getModel() != null) {
                    CodegenModel cm = ((ModelMap) m).getModel();
                    String cfg = plan.modelCfg(cm.name);
                    if (cfg != null) {
                        cm.vendorExtensions.put(ApiFeatures.MODEL_CFG, cfg);
                        gated++;
                    }
                }
            }
            LOGGER.info("{}: {}: {} of {} models are gated", GENERATOR_NAME, API_FEATURES, gated,
                    ((List<?>) objs.get("models")).size());
        }
    }

    /**
     * Without {@code webhookModule}, {@code src/apis/mod.rs} and the README list the webhook APIs with the path APIs
     * ({@code apiInfo.apis}). A webhook API whose tag path operations also carry replaces that path API: it holds the
     * path operations followed by the webhooks ({@link #mergePathOperations}), so the README lists the webhooks too. A
     * tag that only webhooks carry is added, before the first API whose class name sorts after it, so that its module
     * is declared and part of the crate.
     */
    @SuppressWarnings("unchecked")
    private void addWebhookApis(Map<String, Object> objs) {
        if (!(objs.get("webhooks") instanceof List) || !(objs.get("apiInfo") instanceof Map)) {
            return;
        }
        Map<String, Object> apiInfo = (Map<String, Object>) objs.get("apiInfo");
        List<Object> apis = apiInfo.get("apis") instanceof List
                ? new ArrayList<>((List<Object>) apiInfo.get("apis")) : new ArrayList<>();
        int added = 0;
        for (Object webhook : (List<?>) objs.get("webhooks")) {
            if (!(webhook instanceof Map) || ((Map<?, ?>) webhook).get("classname") == null) {
                continue;
            }
            String classname = String.valueOf(((Map<?, ?>) webhook).get("classname"));
            int at = apis.size();
            boolean replaced = false;
            for (int i = 0; i < apis.size(); i++) {
                Object name = apis.get(i) instanceof Map ? ((Map<?, ?>) apis.get(i)).get("classname") : null;
                if (classname.equals(String.valueOf(name))) {
                    apis.set(i, webhook);
                    replaced = true;
                    break;
                }
                if (at == apis.size() && name != null && String.valueOf(name).compareTo(classname) > 0) {
                    at = i;
                }
            }
            if (!replaced) {
                apis.add(at, webhook);
                added++;
                LOGGER.info("{}: the webhooks of {} have no path operations; {}.rs is declared in mod.rs",
                        GENERATOR_NAME, classname, ((Map<?, ?>) webhook).get("classFilename"));
            }
        }
        apiInfo.put("apis", apis);
        if (added > 0) {
            LOGGER.info("{}: {} API modules hold only webhooks (set {} to generate every webhook into one module)",
                    GENERATOR_NAME, added, WEBHOOK_MODULE);
        }
    }

    /**
     * Runs the steps that need the written crate (the union dispatch module, {@link EnumFromStr}), then the default
     * post-processing.
     */
    @Override
    public void postProcess() {
        java.nio.file.Path models = java.nio.file.Paths.get(outputFolder, "src", "models");
        try {
            checkStructJsonModels(models, structJsonModels, modelFiles);
            if (anyDispatch) {
                UnionRules.writeDispatchModule(models);
                logPruned(UnionRules.DISPATCH_MODULE, HelperPruning.prune(
                        models.resolve(UnionRules.DISPATCH_MODULE + ".rs"), List.of(models), !pruneAllHelpers));
                if (apiFeatures) {
                    // the models the enabled features compile may not use every helper
                    java.nio.file.Path mod = models.resolve("mod.rs");
                    String declaration = "pub(crate) mod " + UnionRules.DISPATCH_MODULE + ";\n";
                    java.nio.file.Files.writeString(mod, java.nio.file.Files.readString(mod).replace(declaration,
                            "#[allow(dead_code)]\n" + declaration));
                }
            }
            if (multipartHelper) {
                java.nio.file.Path apis = java.nio.file.Paths.get(outputFolder, "src", "apis");
                logPruned("multipart_helper", HelperPruning.prune(apis.resolve("multipart_helper.rs"), List.of(apis),
                        !pruneAllHelpers));
            }
            if (deprecatedAttributes) {
                for (String model : deprecatedModels) {
                    String filename = modelFiles.get(model);
                    java.nio.file.Path file = filename == null ? null : models.resolve(filename + ".rs");
                    if (file != null && java.nio.file.Files.exists(file)) {
                        java.nio.file.Files.writeString(file,
                                ExtraRules.deprecateItem(java.nio.file.Files.readString(file), model));
                    }
                }
                ExtraRules.allowDeprecated(models, java.nio.file.Paths.get(outputFolder, "src", "apis"));
            }
            EnumFromStr.Result result = EnumFromStr.run(models,
                    msg -> LOGGER.warn("{}: FromStr: {}", GENERATOR_NAME, msg));
            LOGGER.info("{}: FromStr: {} closed enums, {} of them given a Display", GENERATOR_NAME, result.enums(),
                    result.displaysAdded());
            if (apiFeatures) {
                // the enabled features may compile no closed enum, which is what constructs the error
                java.nio.file.Path mod = models.resolve("mod.rs");
                String declaration = "pub mod " + EnumFromStr.ERROR_MODULE + ";\n";
                java.nio.file.Files.writeString(mod, java.nio.file.Files.readString(mod).replace(declaration,
                        "#[allow(dead_code)]\n" + declaration));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(GENERATOR_NAME + ": cannot process " + models, e);
        }
        super.postProcess();
    }

    /** The file of every model by its Rust type name: {@code classname} to {@code classFilename}. */
    static Map<String, String> modelFiles(Map<String, ModelsMap> objs) {
        Map<String, String> files = new HashMap<>();
        for (CodegenModel cm : ExtraRules.models(objs)) {
            files.put(cm.classname, cm.classFilename);
        }
        return files;
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
     *
     * @param names the Rust type names of the parameters' models
     * @param files the file of each model (without {@code .rs}) by its Rust type name; a model without one fails
     */
    static void checkStructJsonModels(java.nio.file.Path models, Set<String> names, Map<String, String> files)
            throws java.io.IOException {
        List<String> notStructs = new ArrayList<>();
        for (String model : names) {
            String filename = files.get(model);
            java.nio.file.Path file = filename == null ? null : models.resolve(filename + ".rs");
            if (file == null || !java.nio.file.Files.exists(file) || !java.util.regex.Pattern.compile(
                    "\\bpub struct " + java.util.regex.Pattern.quote(model) + "\\b")
                    .matcher(java.nio.file.Files.readString(file)).find()) {
                notStructs.add(model);
            }
        }
        if (!notStructs.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "%s: parameters of these types are serialized as JSON, but they are no longer structs: %s",
                    GENERATOR_NAME, notStructs));
        }
    }

    /**
     * Marks a request body that is only serialized, so its argument borrows it: a model, a {@code Vec} (as a slice),
     * a map or a {@code serde_json::Value}. A file ({@code PathBuf}) is opened, a string is already {@code &str}, and
     * a boolean, number, date or date-time is {@code Copy} (chrono): those keep their type. A body whose type is
     * {@code String} (a string enum without {@code typedEnumParameters}, {@code format: byte}) becomes {@code &str}.
     */
    static void markBodyRef(CodegenParameter p) {
        if (!p.isBodyParam || p.isFile || p.isBinary || p.isString || p.isUuid || p.dataType == null
                || p.isBoolean || p.isInteger || p.isLong || p.isShort || p.isNumber || p.isFloat || p.isDouble
                || p.isDate || p.isDateTime) {
            return;
        }
        // Mirrors the "### Models and primative types" line of the parameter type in api.mustache (the
        // {{^isPrimitiveType}}{{^isContainer}}{{^x-rust-type-qualified}}models:: part): a model gets models::, a
        // container, a path (serde_json::Value, std::collections::HashMap, models::X) or a primitive does not.
        // x-rust-type-qualified is only set later, so a dataType that already contains "::" stands in for it.
        String type = p.dataType;
        if (!p.isPrimitiveType && !p.isContainer && !p.isFreeFormObject && !p.isAnyType && !type.contains("::")) {
            type = "models::" + type;
        }
        String borrowed;
        if ("String".equals(type)) {
            borrowed = "&str";
        } else if (p.isArray && type.startsWith("Vec<") && type.endsWith(">")) {
            borrowed = "&[" + type.substring(4, type.length() - 1) + "]";
        } else {
            borrowed = "&" + type;
        }
        p.vendorExtensions.put(BODY_REF, borrowed);
        if (p.required && !p.isNullable) {
            p.vendorExtensions.put(BODY_REF_DIRECT, true);
        }
    }

    /** Marks a parameter whose type, or whose array items' type, is one of the structs, or that is a map. */
    static void markStructJson(CodegenParameter p, Set<String> structs) {
        if (p.isMap) {
            // a map (additionalProperties) has no Display either: its JSON, as for a struct
            p.vendorExtensions.put(STRUCT_JSON, true);
            return;
        }
        boolean items = p.isArray && p.items != null;
        if (items && (p.items.isArray || p.items.isMap)) {
            // an array of arrays or of maps: each item has no text form of its own, so it is sent as its JSON
            p.vendorExtensions.put(STRUCT_JSON_ITEMS, true);
            return;
        }
        String type = items ? p.items.dataType : p.dataType;
        if (type != null && structs.contains(type.replaceFirst("^models::", ""))) {
            p.vendorExtensions.put(items ? STRUCT_JSON_ITEMS : STRUCT_JSON, true);
        }
    }

    /**
     * Marks how a path, query, header, cookie or form parameter becomes text: {@code date-time} values in RFC 3339, as
     * {@code format: date-time} requires, everything else with {@code Display}.
     */
    static void markText(CodegenParameter p) {
        if (!p.isPathParam && !p.isQueryParam && !p.isHeaderParam && !p.isCookieParam && !p.isFormParam) {
            return;
        }
        String type = p.isArray && p.items != null ? p.items.dataType : p.dataType;
        boolean dateTime = type != null && type.startsWith("chrono::DateTime<");
        p.vendorExtensions.put(TO_TEXT, dateTime ? "to_rfc3339" : "to_string");
        if (!p.isArray && "String".equals(p.dataType)) {
            p.vendorExtensions.put(STR, true);
        }
        if (dateTime) {
            p.vendorExtensions.put(RFC3339, true);
        }
        if (p.isArray && p.items != null) {
            if ("String".equals(p.items.dataType)) {
                p.vendorExtensions.put(STR_ITEMS, true);
            }
            // A query array is repeated for style: form, explode: true (collectionFormat multi), a form field array
            // unless its encoding says explode: false; otherwise the items are joined with the style's delimiter.
            // pipeDelimited and spaceDelimited with explode: true are repeated too, as OpenAPI 3.2 spells out.
            boolean delimited = "pipes".equals(p.collectionFormat) || "ssv".equals(p.collectionFormat);
            boolean repeat = p.isFormParam ? !p.vendorExtensions.containsKey(FORM_JOINED)
                    : "multi".equals(p.collectionFormat) || (delimited && p.isExplode);
            if (repeat) {
                p.vendorExtensions.put(REPEAT, true);
            }
            p.vendorExtensions.put(SEPARATOR, p.isFormParam
                    ? (String) p.vendorExtensions.getOrDefault(FORM_SEPARATOR, ",") : separator(p.collectionFormat));
        }
    }

    /** The delimiter a non-exploded array is joined with: {@code |} for pipeDelimited, a space for spaceDelimited. */
    static String separator(String collectionFormat) {
        if ("pipes".equals(collectionFormat)) {
            return "|";
        }
        if ("ssv".equals(collectionFormat)) {
            return " ";
        }
        if ("tsv".equals(collectionFormat)) {
            return "\\t";
        }
        return ",";
    }

    /**
     * Marks an operation that sends a {@code Cookie} header: it has a cookie parameter or an api key sent as one.
     * Returns whether it does.
     */
    static boolean markCookies(CodegenOperation op) {
        boolean cookies = op.cookieParams != null && !op.cookieParams.isEmpty();
        if (op.authMethods != null) {
            cookies |= op.authMethods.stream()
                    .anyMatch(a -> Boolean.TRUE.equals(a.isApiKey) && Boolean.TRUE.equals(a.isKeyInCookie));
        }
        if (cookies) {
            op.vendorExtensions.put(COOKIES, true);
        }
        return cookies;
    }

    /** Whether a Rust type is one of chrono's date types (with {@code useChrono}). */
    private static boolean isChrono(String type) {
        return type != null && (type.startsWith("chrono::DateTime<") || type.equals("chrono::NaiveDate"));
    }

    /**
     * The types {@code --schema-mappings} puts in place of a schema that would be a struct or a map: an object, with
     * properties, {@code additionalProperties} or an {@code allOf}, and no enum, {@code oneOf} or {@code anyOf}. A
     * parameter of one is sent as its JSON, as one of a generated struct is, so the type needs {@code Serialize}. A
     * mapped scalar, enum or union schema is sent with {@code Display}, as the type's own text.
     */
    private Set<String> mappedStructs() {
        Set<String> mapped = new HashSet<>();
        for (Map.Entry<String, String> e : schemaMapping.entrySet()) {
            if (isMappedStruct(e.getKey())) {
                mapped.add(e.getValue());
            }
        }
        return mapped;
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
     * own (see {@link ContentTypeMethods}): each is the operation restricted to its media types, rendered by
     * {@code fromOperation} under a suffixed operation id. Variants share the operation's error enum.
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, io.swagger.v3.oas.models.Operation operation,
            CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        super.addOperationToGroup(tag, resourcePath, operation, co, operations);
        // The default grouping renames a method whose name another method of the tag has.
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

    /**
     * With {@code responseFunctions}, marks every method of one API module whose success is a body: the api template
     * renders it a second time as {@code <operation>_response}, returning {@code ApiResponse<T>}. A file response
     * already is the {@code reqwest::Response} and an event stream is not a body to hold, so neither gets one. Fails
     * when the name is another method's.
     */
    private void addResponseFunctions(List<CodegenOperation> operations) {
        Set<String> names = new HashSet<>();
        for (CodegenOperation op : operations) {
            names.add(op.operationId);
        }
        for (CodegenOperation op : operations) {
            if (op.isResponseFile || op.vendorExtensions.containsKey(EVENT_STREAM)) {
                continue;
            }
            String name = op.operationId + "_response";
            if (names.contains(name)) {
                throw new IllegalArgumentException(String.format("%s: %s: %s would get the method %s, but another "
                                + "operation of its API module is named %s; rename one of them (operationId)",
                        GENERATOR_NAME, RESPONSE_FUNCTIONS, op.operationId, name, name));
            }
            String type = isSupportMultipleResponses() ? op.operationIdCamelCase + "Success"
                    : op.returnType == null ? "()" : op.returnType;
            Map<String, Object> context = new HashMap<>();
            context.put("x-rust-as-response", true);
            context.put("x-rust-response-type", type);
            // The twin's doc paragraph follows the operation's doc lines with an empty line, only when there are some.
            context.put("x-rust-doc-gap", (op.notes != null && !op.notes.isEmpty())
                    || op.vendorExtensions.containsKey(VARIANT_DOC));
            op.vendorExtensions.put(RESPONSE_FUNCTION, context);
        }
        for (CodegenOperation op : operations) {
            if (op.vendorExtensions.containsKey(CONTENT_VARIANT)) {
                continue;
            }
            List<CodegenOperation> methods = new ArrayList<>(List.of(op));
            methods.addAll(contentVariants.getOrDefault(op, List.of()));
            io.swagger.v3.oas.models.Operation spec = specOperations.get(op);
            if (spec == null) {
                continue;
            }
            // Every operation's error is a ResponseContent, a file or an event stream's too; its methods share it.
            Map<String, Object> errorHeaders = ResponseHeaders.plan(openAPI, spec, true, op.operationId,
                    this::getTypeDeclaration, this::toVarName,
                    msg -> LOGGER.warn("{}: {}: {}", GENERATOR_NAME, RESPONSE_FUNCTIONS, msg));
            if (errorHeaders != null) {
                errorHeaders.put("name", op.operationIdCamelCase + "ErrorHeaders");
                errorHeaders.put("error", true);
                op.vendorExtensions.put(ResponseHeaders.ERROR_STRUCT, errorHeaders);
                additionalProperties.put(TYPED_ERROR_HEADERS, true);
                noteHeaderHelpers(errorHeaders);
            }
            if (methods.stream().noneMatch(m -> m.vendorExtensions.containsKey(RESPONSE_FUNCTION))) {
                continue;
            }
            // Planned only for an operation that gets the struct: nothing else depends on its headers.
            Map<String, Object> headers = ResponseHeaders.plan(openAPI, spec, false, op.operationId,
                    this::getTypeDeclaration, this::toVarName,
                    msg -> LOGGER.warn("{}: {}: {}", GENERATOR_NAME, RESPONSE_FUNCTIONS, msg));
            if (headers == null) {
                continue;
            }
            // One struct per operation, named after it, which its methods' twins share.
            headers.put("name", op.operationIdCamelCase + "ResponseHeaders");
            headers.put("error", false);
            op.vendorExtensions.put(ResponseHeaders.STRUCT, headers);
            for (CodegenOperation m : methods) {
                @SuppressWarnings("unchecked")
                Map<String, Object> context = (Map<String, Object>) m.vendorExtensions.get(RESPONSE_FUNCTION);
                if (context != null) {
                    context.put("x-rust-response-headers-name", headers.get("name"));
                }
            }
            additionalProperties.put(TYPED_HEADERS, true);
            noteHeaderHelpers(headers);
        }
    }

    /** Marks the helpers of {@code api_response.rs} a typed headers struct reads with, and notes its types. */
    private void noteHeaderHelpers(Map<String, Object> struct) {
        for (Object f : (List<?>) struct.get("fields")) {
            additionalProperties.put(Boolean.TRUE.equals(((Map<?, ?>) f).get("list")) ? TYPED_HEADERS + "-list"
                    : TYPED_HEADERS + "-value", true);
        }
        noteTypes(ResponseHeaders.types(struct));
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

    /**
     * A parameter that {@link SpecRules#unwrapNullableParameters} gave the non-null branch of its union stays
     * nullable, as a {@code type: [T, null]} parameter and a parameter of a nullable union are: when required, the
     * argument is an {@code Option} and {@code None} sends nothing. A path parameter is never nullable, this one or
     * any other ({@code type: [T, null]}, {@code nullable: true}): a path always has a value, so it is the type.
     */
    @Override
    public CodegenParameter fromParameter(io.swagger.v3.oas.models.parameters.Parameter parameter,
            Set<String> imports) {
        CodegenParameter p = super.fromParameter(parameter, imports);
        if (parameter.getExtensions() != null && parameter.getExtensions().containsKey(SpecRules.NULL_BRANCH)) {
            p.vendorExtensions.remove(SpecRules.NULL_BRANCH);
            p.isNullable = true;
        }
        if (p.isPathParam) {
            // a path always has a value: a nullable one (type: [T, null], nullable: true) is the type, not an Option
            p.isNullable = false;
        }
        return p;
    }

    /**
     * A form field (urlencoded or multipart) of a {@code oneOf}/{@code anyOf} of one schema and {@code type: null},
     * which the normalizer has made a nullable {@code allOf} of that one schema by now, is a field of that schema,
     * nullable as a {@code type: [T, null]} field is: a required one left {@code None} is sent empty. A plain
     * {@code allOf: [$ref]} field, and a reference wrapped in one-element unions ({@link SpecRules#peel}), takes the
     * referenced type as well. Here rather than in the spec, since the body's schema may be a component whose model keeps the field as it is.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public CodegenParameter fromFormProperty(String name, Schema propertySchema, Set<String> imports) {
        Schema branch = propertySchema == null ? null : SpecRules.nonNullBranch(propertySchema);
        boolean nullable = branch != null;
        if (branch == null && propertySchema != null) {
            branch = SpecRules.singleAllOfRef(propertySchema);
            nullable = branch != null && (Boolean.TRUE.equals(propertySchema.getNullable())
                    || (propertySchema.getTypes() != null && propertySchema.getTypes().contains("null")));
        }
        if (branch != null) {
            branch = SpecRules.peel(branch);
        }
        CodegenParameter p = super.fromFormProperty(name, branch != null ? branch : propertySchema, imports);
        if (nullable) {
            p.isNullable = true;
        }
        // the wrapper's description documents the field, as it did before
        if (branch != null && propertySchema.getDescription() != null
                && (p.description == null || p.description.isEmpty())) {
            p.unescapedDescription = propertySchema.getDescription();
            p.description = escapeText(propertySchema.getDescription());
        }
        return p;
    }

    /**
     * Every method names the error enum it returns: its own unless it is another method of an operation. A body the
     * method only serializes is borrowed ({@link #markBodyRef}).
     */
    @Override
    public CodegenOperation fromOperation(String path, String httpMethod, io.swagger.v3.oas.models.Operation operation,
            List<io.swagger.v3.oas.models.servers.Server> servers) {
        CodegenOperation co = super.fromOperation(path, httpMethod, operation, servers);
        co.vendorExtensions.put(ERROR_ENUM, co.operationIdCamelCase + "Error");
        co.vendorExtensions.put(OPTIONS_PARAM, optionsParam(co));
        // Here rather than in postProcessOperationsWithModels, which webhook operations never reach.
        for (List<CodegenParameter> params : Arrays.asList(co.allParams, co.bodyParams, co.requiredParams,
                co.optionalParams)) {
            if (params != null) {
                params.forEach(TimvwRustCodegen::markBodyRef);
            }
        }
        markFormExplode(co, operation);
        if (responseFunctions) {
            specOperations.put(co, operation);
        }
        return co;
    }

    /**
     * The name of the per-call {@code RequestOptions} parameter: {@code options}, or {@code request_options} (then
     * {@code request_options_2}, ...) when a parameter of the operation is named so.
     */
    static String optionsParam(CodegenOperation co) {
        Set<String> taken = new HashSet<>(List.of("configuration"));
        if (co.allParams != null) {
            for (CodegenParameter p : co.allParams) {
                taken.add(p.paramName.startsWith("r#") ? p.paramName.substring(2) : p.paramName);
            }
        }
        if (!taken.contains("options")) {
            return "options";
        }
        String name = "request_options";
        for (int i = 2; taken.contains(name); i++) {
            name = "request_options_" + i;
        }
        return name;
    }

    /**
     * An urlencoded form field array is sent as a pair per item ({@code style: form, explode: true}, the default)
     * unless the body's {@code encoding} gives it {@code explode: false} (the default for pipeDelimited and
     * spaceDelimited): marks those with {@link #FORM_JOINED}, and a pipe- or space-delimited one with
     * {@link #FORM_SEPARATOR}.
     */
    private void markFormExplode(CodegenOperation co, io.swagger.v3.oas.models.Operation operation) {
        if (operation.getRequestBody() == null) {
            return;
        }
        io.swagger.v3.oas.models.parameters.RequestBody body =
                org.openapitools.codegen.utils.ModelUtils.getReferencedRequestBody(openAPI, operation.getRequestBody());
        if (body == null || body.getContent() == null) {
            return;
        }
        io.swagger.v3.oas.models.media.MediaType form = body.getContent().get("application/x-www-form-urlencoded");
        if (form == null || form.getEncoding() == null) {
            return;
        }
        for (List<CodegenParameter> params : Arrays.asList(co.allParams, co.formParams, co.requiredParams,
                co.optionalParams)) {
            if (params == null) {
                continue;
            }
            for (CodegenParameter p : params) {
                io.swagger.v3.oas.models.media.Encoding encoding = p.isFormParam ? form.getEncoding().get(p.baseName)
                        : null;
                if (encoding == null) {
                    continue;
                }
                io.swagger.v3.oas.models.media.Encoding.StyleEnum style = encoding.getStyle();
                boolean formStyle = style == null || style == io.swagger.v3.oas.models.media.Encoding.StyleEnum.FORM;
                boolean explode = encoding.getExplode() != null ? encoding.getExplode() : formStyle;
                if (style == io.swagger.v3.oas.models.media.Encoding.StyleEnum.PIPE_DELIMITED) {
                    p.vendorExtensions.put(FORM_SEPARATOR, "|");
                } else if (style == io.swagger.v3.oas.models.media.Encoding.StyleEnum.SPACE_DELIMITED) {
                    p.vendorExtensions.put(FORM_SEPARATOR, " ");
                }
                if (!explode) {
                    p.vendorExtensions.put(FORM_JOINED, true);
                }
            }
        }
    }

    /** A webhook's parameter and return types link to the models' docs as an operation's do. */
    @Override
    public WebhooksMap postProcessWebhooksWithModels(WebhooksMap objs, List<ModelMap> allModels) {
        objs = super.postProcessWebhooksWithModels(objs, allModels);
        if (objs.getWebhooks() != null && objs.getWebhooks().getOperation() != null) {
            Set<String> documented = new HashSet<>(documentedModels);
            documented.addAll(DocLinks.documented(allModels));
            DocLinks.markOperations(objs.getWebhooks().getOperation(), documented);
        }
        if (apiFeatures && objs.getWebhooks() != null && objs.getWebhooks().getOperation() != null) {
            markApiFeatures(objs, objs.getWebhooks().getClassname(), objs.getWebhooks().getOperation());
        }
        mergePathOperations(objs);
        return objs;
    }

    /**
     * openapi-generator writes the webhooks of a tag into the API file of that tag, after the path operations of the
     * tag were written there: untagged webhooks into {@code default_api.rs}, next to the untagged path operations. It
     * renders that file again from the webhooks alone, so the path operations would be lost. Here the webhooks join
     * the path operations of their API module instead, so the file holds both. A webhook whose method name a path
     * method of the module already has fails generation. {@code webhookModule} keeps the webhooks apart.
     */
    private void mergePathOperations(WebhooksMap objs) {
        OperationMap hooks = objs.getWebhooks();
        OperationsMap paths = hooks == null ? null : pathApis.get(hooks.getClassname());
        if (paths == null || paths.getOperations() == null || paths.getOperations().getOperation() == null
                || hooks.getOperation() == null) {
            return;
        }
        List<CodegenOperation> merged = new ArrayList<>(paths.getOperations().getOperation());
        Set<String> taken = new HashSet<>();
        for (CodegenOperation op : merged) {
            taken.add(op.nickname);
            taken.add(op.operationId);
            if (op.vendorExtensions.containsKey(RESPONSE_FUNCTION)) {
                taken.add(op.operationId + "_response");
            }
        }
        for (CodegenOperation hook : hooks.getOperation()) {
            if (taken.contains(hook.nickname) || taken.contains(hook.operationId)) {
                throw new IllegalArgumentException(String.format("%s: webhook %s goes into the API module %s with "
                                + "the path operations of its tag, where a path method is already named %s; rename "
                                + "one of them (operationId) or set %s to generate the webhooks into a module of their "
                                + "own", GENERATOR_NAME, hook.operationId, hooks.getClassname(), hook.operationId,
                        WEBHOOK_MODULE));
            }
            merged.add(hook);
        }
        OperationMap operations = new OperationMap();
        operations.putAll(paths.getOperations());
        operations.setOperation(merged);
        OperationsMap combined = new OperationsMap();
        combined.putAll(paths);
        combined.setOperation(operations);
        Set<Map<String, String>> imports = new java.util.LinkedHashSet<>();
        for (List<Map<String, String>> list : Arrays.asList(paths.getImports(), objs.getImports())) {
            if (list != null) {
                imports.addAll(list);
            }
        }
        combined.setImports(new ArrayList<>(imports));
        if (!imports.isEmpty()) {
            combined.put("hasImport", true);
        }
        Imports.markApi(combined, multipartHelper, isSupportMultipleResponses(), httpStackTower);
        objs.clear();
        objs.putAll(combined);
        LOGGER.info("{}: {} webhook operations written into {} next to its {} path operations (set {} to generate "
                        + "the webhooks into a module of their own)", GENERATOR_NAME, hooks.getOperation().size(),
                hooks.getClassname(), paths.getOperations().getOperation().size(), WEBHOOK_MODULE);
    }

    /** {@code default_api} re-exports the webhook module when {@code webhookModuleReexport} is set. */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        objs = super.postProcessOperationsWithModels(objs, allModels);
        boolean helperUsed = false;
        Set<String> structs = renderedStructs(allModels);
        Set<String> mappedStructs = mappedStructs();
        structs.addAll(mappedStructs);
        if (objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            for (CodegenOperation op : objs.getOperations().getOperation()) {
                op.vendorExtensions.putIfAbsent(ERROR_ENUM, op.operationIdCamelCase + "Error");
                if (markCookies(op)) {
                    additionalProperties.put(USES_COOKIES, true);
                }
                for (CodegenOperation variant : contentVariants.getOrDefault(op, List.of())) {
                    variant.authMethods = op.authMethods;
                    variant.hasAuthMethods = op.hasAuthMethods;
                    variant.vendorExtensions.put(ERROR_ENUM, op.operationIdCamelCase + "Error");
                    if (markCookies(variant)) {
                        additionalProperties.put(USES_COOKIES, true);
                    }
                    ErrorStatus.mark(variant, op, op.operationIdCamelCase + "Error");
                }
                if (!op.vendorExtensions.containsKey(CONTENT_VARIANT)) {
                    ErrorStatus.mark(op, op, String.valueOf(op.vendorExtensions.get(ERROR_ENUM)));
                }
                for (List<CodegenParameter> params : Arrays.asList(op.allParams, op.formParams, op.bodyParams,
                        op.queryParams, op.pathParams, op.headerParams, op.cookieParams, op.requiredParams,
                        op.optionalParams)) {
                    if (params == null) {
                        continue;
                    }
                    for (CodegenParameter p : params) {
                        if (p.dataType != null && (p.dataType.startsWith("models::")
                                || schemaMapping.containsValue(p.dataType))) {
                            p.vendorExtensions.put(TYPE_QUALIFIED, true);
                        }
                        if (p.dataType != null && schemaMapping.containsValue(p.dataType)) {
                            markMapped(p, mappedStructs.contains(p.dataType));
                        }
                        markStructJson(p, structs);
                        // a mapped type has no model file to check: it is the crate's own
                        if (p.vendorExtensions.containsKey(STRUCT_JSON) && !p.isMap
                                && !mappedStructs.contains(p.dataType)) {
                            structJsonModels.add(p.dataType.replaceFirst("^models::", ""));
                        } else if (p.vendorExtensions.containsKey(STRUCT_JSON_ITEMS) && !p.items.isContainer
                                && !mappedStructs.contains(p.items.dataType)) {
                            structJsonModels.add(p.items.dataType.replaceFirst("^models::", ""));
                        }
                        if (p.dataType != null && unionModels.containsKey(p.dataType.replaceFirst("^models::", ""))) {
                            p.vendorExtensions.put(UnionRules.UNION_TEXT, true);
                            // a required parameter of a nullable union takes an Option, as a field of one does
                            if (nullableUnionModels.contains(p.dataType.replaceFirst("^models::", ""))
                                    && !p.isPathParam && !p.isBodyParam) {
                                p.isNullable = true;
                            }
                        }
                        if (p.isArray && p.items != null && !"String".equals(p.items.dataType)
                                && !p.vendorExtensions.containsKey(STRUCT_JSON_ITEMS)) {
                            p.vendorExtensions.put(DISPLAY_ITEMS, true);
                        }
                        markText(p);
                        if (httpStackTower) {
                            UploadParams.mark(p, bonBuilders);
                        }
                    }
                }
                if (multipartHelper && op.isMultipart && op.formParams != null && !op.formParams.isEmpty()) {
                    MultipartRules.classify(openAPI, op, unionModels,
                            msg -> LOGGER.warn("{}: multipart: {}", GENERATOR_NAME, msg));
                    if (httpStackTower && bonBuilders) {
                        UploadParams.markUnionSetters(op);
                    }
                    for (CodegenParameter p : op.formParams) {
                        if (p.isFile || !p.vendorExtensions.containsKey(MultipartRules.STOCK)) {
                            helperUsed = true;
                        }
                    }
                }
                if (httpStackTower && UploadParams.uploads(op)) {
                    additionalProperties.put(UploadParams.RUNTIME_FS, true);
                }
            }
        }
        if (helperUsed) {
            objs.put(MULTIPART_HELPER_IMPORT, true);
        }
        if (responseFunctions && objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            addResponseFunctions(objs.getOperations().getOperation());
        }
        Imports.markApi(objs, multipartHelper, isSupportMultipleResponses(), httpStackTower);
        if (objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            // the webhooks' operations are not given the models: the ones documented with the models count too
            Set<String> documented = new HashSet<>(documentedModels);
            documented.addAll(DocLinks.documented(allModels));
            DocLinks.markOperations(objs.getOperations().getOperation(), documented);
        }
        if (webhookModuleReexport && objs.getOperations() != null
                && toApiName("default").equals(objs.getOperations().getClassname())) {
            objs.put(WEBHOOK_REEXPORT_KEY, toApiFilename(webhookModule));
            if (apiFeatures) {
                String cfg = apiFeaturesPlan().moduleCfg(toApiName(sanitizeTag(webhookModule)));
                if (cfg != null) {
                    objs.put("webhookReexportCfg", cfg);
                }
            }
        }
        if (apiFeatures && objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            markApiFeatures(objs, objs.getOperations().getClassname(), objs.getOperations().getOperation());
        }
        if (objs.getOperations() != null && objs.getOperations().getClassname() != null) {
            pathApis.put(objs.getOperations().getClassname(), objs);
        }
        return objs;
    }
}
