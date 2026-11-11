package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The typed headers of an operation's successes ({@code responseFunctions}): the headers its success responses
 * declare, as a struct {@code <Operation>ResponseHeaders} in the API module with an {@code Option} field per header
 * ({@code header.schema.typed}).
 *
 * <p>The successes are the {@code 2xx} and {@code 3xx} responses (exact codes and ranges); an operation without any
 * takes its {@code default} response, as openapi-generator does for the return type. The struct holds the union of
 * their headers: {@code ApiResponse} is one type for every success status, and any declared header may be missing
 * anyway, so a header only one status declares is an {@code Option} like the others. A header two successes declare
 * with different types is read as text ({@code String}).</p>
 *
 * <p>The error responses get the same with {@code errors}: {@code <Operation>ErrorHeaders}, the union of the headers
 * of the {@code 4xx}, {@code 5xx} and {@code default} responses (the statuses of {@code <Operation>Error}), read from
 * a {@code ResponseContent} ({@code header.schema.typed-error}).</p>
 *
 * <p>A header is typed as a parameter of its schema is: {@code getTypeDeclaration}, so integers, numbers, booleans,
 * {@code uuid}, {@code date}/{@code date-time} (chrono, with {@code useChrono}), enum components and arrays of those
 * (the simple style, comma-separated). What has no text form (an object, a map, a union, a free-form value, a nested
 * array) is the header's text. Hop-by-hop headers ({@link #SKIPPED}) and {@code Content-Type}, which the
 * specification says to ignore, get no field, and neither does a name that is not an HTTP token (warned). Two headers
 * whose field names are the same get {@code _2}, {@code _3}, ... (warned) rather than failing generation.</p>
 */
final class ResponseHeaders {

    /**
     * Set on an operation whose successes declare headers and that has a {@code _response} twin: the struct to render
     * ({@code name}, {@code fields}, {@code statuses}).
     */
    static final String STRUCT = "x-rust-response-headers";
    /**
     * Set on an operation whose error responses declare headers ({@code responseFunctions}): the struct to render, as
     * {@link #STRUCT}.
     */
    static final String ERROR_STRUCT = "x-rust-error-headers";

    /**
     * Headers that get no field: the hop-by-hop headers of RFC 9110 section 7.6.1 (and {@code Keep-Alive} and
     * {@code Proxy-Connection}), which describe the connection rather than the response, and {@code Content-Type},
     * which the OpenAPI specification says a response's {@code headers} ignore.
     */
    static final Set<String> SKIPPED = Set.of("connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "content-type");

    private static final Set<String> PARSED = Set.of("i32", "i64", "f32", "f64", "bool", "uuid::Uuid",
            "chrono::NaiveDate");
    /** An HTTP field name (RFC 9110 {@code token}); any other name cannot be looked up in a header map. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final String DATE_TIME = "chrono::DateTime<chrono::FixedOffset>";

    private ResponseHeaders() {
    }

    /** One field of the struct. */
    record Field(String header, String spelling, String type, String parse, boolean list, List<String> doc) {
    }

    /**
     * The struct of {@code operation}'s success headers, or of its error headers, or {@code null} when those responses
     * declare none (or only skipped ones).
     *
     * @param errors    the error responses ({@code 4xx}, {@code 5xx} and {@code default}, the statuses of the
     *                  operation's error enum) rather than the successes
     * @param method    the operation's method name, for warnings
     * @param typeOf    the Rust type of a schema, as a parameter of it is typed
     * @param toVarName the Rust field name of a header name
     * @param warn      where a skipped header or a renamed field is reported
     */
    @SuppressWarnings("rawtypes")
    static Map<String, Object> plan(OpenAPI openAPI, Operation operation, boolean errors, String method,
            Function<Schema, String> typeOf, Function<String, String> toVarName, Consumer<String> warn) {
        if (operation.getResponses() == null) {
            return null;
        }
        List<String> statuses = new ArrayList<>();
        for (String code : operation.getResponses().keySet()) {
            if (errors ? code.startsWith("4") || code.startsWith("5") || "default".equals(code)
                    : code.startsWith("2") || code.startsWith("3")) {
                statuses.add(code);
            }
        }
        if (!errors && statuses.isEmpty() && operation.getResponses().containsKey("default")) {
            statuses.add("default");
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        List<String> declaring = new ArrayList<>();
        for (String code : statuses) {
            ApiResponse response = ModelUtils.getReferencedApiResponse(openAPI, operation.getResponses().get(code));
            if (response == null || response.getHeaders() == null) {
                continue;
            }
            boolean any = false;
            for (Map.Entry<String, Header> e : response.getHeaders().entrySet()) {
                String name = e.getKey().toLowerCase(Locale.ROOT);
                if (SKIPPED.contains(name)) {
                    continue;
                }
                if (!TOKEN.matcher(name).matches()) {
                    warn.accept(String.format("%s: the %s response header '%s' is not a valid HTTP header name; it "
                            + "gets no typed field", method, code, e.getKey()));
                    continue;
                }
                any = true;
                Field field = field(openAPI, name, e.getKey(), header(openAPI, e.getValue()), typeOf);
                Field before = fields.get(name);
                if (before == null) {
                    fields.put(name, field);
                } else if (!before.type().equals(field.type()) || before.list() != field.list()) {
                    fields.put(name, new Field(name, before.spelling(), "String", "Some(v.to_owned())", false,
                            before.doc()));
                }
            }
            if (any) {
                declaring.add(code);
            }
        }
        if (fields.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rendered = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Field f : fields.values()) {
            String base = toVarName.apply(f.header());
            String ident = base;
            for (int n = 2; names.containsKey(ident); n++) {
                ident = base.startsWith("r#") ? base.substring(2) + "_" + n : base + "_" + n;
            }
            if (!ident.equals(base)) {
                warn.accept(String.format("%s: the response headers '%s' and '%s' both become the field %s; '%s' is "
                        + "the field %s", method, names.get(base), f.spelling(), base, f.spelling(), ident));
            }
            names.put(ident, f.spelling());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", ident);
            m.put("header", f.spelling());
            m.put("literal", f.header().replace("\\", "\\\\").replace("\"", "\\\""));
            m.put("type", f.list() ? "Vec<" + f.type() + ">" : f.type());
            m.put("parse", f.parse());
            m.put("list", f.list());
            m.put("doc", f.doc());
            rendered.add(m);
        }
        Map<String, Object> struct = new LinkedHashMap<>();
        struct.put("fields", rendered);
        struct.put("statuses", String.join(", ", declaring.stream().map(s -> "`" + s + "`").toList()));
        return struct;
    }

    /** The Rust types of the struct's fields, for the crate's dependencies. */
    @SuppressWarnings("unchecked")
    static List<String> types(Map<String, Object> struct) {
        List<String> types = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) struct.get("fields")) {
            types.add(String.valueOf(f.get("type")));
        }
        return types;
    }

    private static Header header(OpenAPI openAPI, Header header) {
        Header h = header;
        for (int depth = 0; h != null && h.get$ref() != null && depth < 10; depth++) {
            String ref = ModelUtils.getSimpleRef(h.get$ref());
            h = openAPI.getComponents() == null || openAPI.getComponents().getHeaders() == null ? null
                    : openAPI.getComponents().getHeaders().get(ref);
        }
        return h;
    }

    @SuppressWarnings("rawtypes")
    private static Field field(OpenAPI openAPI, String name, String spelling, Header header,
            Function<Schema, String> typeOf) {
        List<String> doc = new ArrayList<>();
        Schema schema = null;
        if (header != null) {
            if (header.getDescription() != null && !header.getDescription().isBlank()) {
                doc.add(RustDoc.wrapBareUrls(header.getDescription().trim().replaceAll("\\s+", " ")));
            }
            schema = header.getSchema();
            if (schema == null && header.getContent() != null && !header.getContent().isEmpty()) {
                MediaType media = header.getContent().values().iterator().next();
                schema = media == null ? null : media.getSchema();
            }
        }
        if (schema == null) {
            return new Field(name, spelling, "String", "Some(v.to_owned())", false, doc);
        }
        String type = typeOf.apply(schema);
        if (type.startsWith("Vec<") && type.endsWith(">")) {
            Schema items = ModelUtils.getSchemaItems(ModelUtils.getReferencedSchema(openAPI, schema));
            String itemType = type.substring(4, type.length() - 1);
            String parse = items == null ? null : scalar(openAPI, itemType, items);
            if (parse != null) {
                return new Field(name, spelling, itemType, parse, true, doc);
            }
            return new Field(name, spelling, "String", "Some(v.to_owned())", false, doc);
        }
        String parse = scalar(openAPI, type, schema);
        if (parse == null) {
            return new Field(name, spelling, "String", "Some(v.to_owned())", false, doc);
        }
        return new Field(name, spelling, type, parse, false, doc);
    }

    /**
     * The Rust expression that reads a value of {@code type} from the header text {@code v} ({@code &str}, trimmed)
     * as an {@code Option}, or {@code null} when the type has no text form.
     */
    @SuppressWarnings("rawtypes")
    private static String scalar(OpenAPI openAPI, String type, Schema schema) {
        if ("String".equals(type)) {
            return "Some(v.to_owned())";
        }
        if (PARSED.contains(type)) {
            return "v.parse::<" + type + ">().ok()";
        }
        if (DATE_TIME.equals(type)) {
            return "chrono::DateTime::parse_from_rfc3339(v).ok()";
        }
        if (type.startsWith("models::") && !type.contains("<")) {
            Schema resolved = ModelUtils.getReferencedSchema(openAPI, schema);
            if (resolved == null || resolved.getEnum() == null || resolved.getEnum().isEmpty()) {
                return null;
            }
            if (ModelUtils.isIntegerSchema(resolved)) {
                return "v.parse::<i64>().ok().and_then(|n| serde_json::from_value::<" + type + ">(n.into()).ok())";
            }
            if (ModelUtils.isStringSchema(resolved)) {
                return "serde_json::from_value::<" + type + ">(serde_json::Value::String(v.to_owned())).ok()";
            }
        }
        return null;
    }
}
