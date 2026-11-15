package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server target (slice 1 of docs/design/targets-and-server.md): what {@code server.mustache} renders for one
 * operation, or why the operation is left out.
 *
 * <p>An operation is served when its request body, if any, and every response are JSON (or have no content), and its
 * path and query parameters have a text form ({@link #parse}). The request struct holds the path and query parameters
 * as typed fields, the body as its type and the raw header map (header and cookie parameters are read from there).
 * The response enum has a variant per declared status: {@code Status200(body)}, {@code Status204}, a
 * {@code status} field for a range ({@code Status4XX}) and for {@code Default}, and a {@code headers} field holding the
 * operation's {@code <Operation>ResponseHeaders} (successes) or {@code <Operation>ErrorHeaders} (errors) where the
 * response declares headers.</p>
 *
 * <p>Left out, with a comment in the server module and a warning: request bodies that are not JSON (multipart, forms,
 * files, text), responses that are not JSON (event streams, files, text), deepObject and object query parameters, and
 * a path parameter that is not a whole path segment.</p>
 */
final class ServerRules {

    /** Why an operation is not served. */
    static final class Unsupported extends Exception {
        Unsupported(String reason) {
            super(reason);
        }
    }

    private static final Set<String> PARSED = Set.of("i32", "i64", "f32", "f64", "bool", "uuid::Uuid",
            "chrono::NaiveDate");
    private static final String DATE_TIME = "chrono::DateTime<chrono::FixedOffset>";
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}/]+)}");
    /** Field names the request struct uses itself. */
    private static final Set<String> TAKEN = Set.of("headers", "body");

    private ServerRules() {
    }

    /** Whether a media type is JSON, as the client's {@code ContentType} reads a response. */
    static boolean isJson(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT);
        return m.startsWith("application/") && m.contains("json");
    }

    /**
     * The Rust expression that reads a value of {@code type} from the text {@code v} ({@code &str}) as an
     * {@code Option}, or {@code null} when the type has no text form here.
     */
    static String parse(String type, Map<String, CodegenModel> models) {
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
            CodegenModel cm = models.get(type.substring("models::".length()));
            if (cm == null || !cm.isEnum) {
                return null;
            }
            if (cm.isInteger || cm.isLong || "i64".equals(cm.dataType) || "i32".equals(cm.dataType)) {
                return "v.parse::<i64>().ok().and_then(|n| serde_json::from_value::<" + type + ">(n.into()).ok())";
            }
            return "serde_json::from_value::<" + type + ">(serde_json::Value::String(v.to_owned())).ok()";
        }
        return null;
    }

    /** A model's type as the generated code names it: {@code models::<Model>} for a model's bare name. */
    static String qualify(String type, Map<String, CodegenModel> models) {
        return type != null && models.containsKey(type) ? "models::" + type : type;
    }

    /** A Rust string literal's content. */
    static String literal(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** One line of documentation from a spec text: its whitespace collapsed, bare URLs wrapped. */
    static String docLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return RustDoc.wrapBareUrls(text.trim().replaceAll("\\s+", " "));
    }

    /** A name derived from a method name: without the raw identifier prefix. */
    static String bare(String name) {
        return name.startsWith("r#") ? name.substring(2) : name;
    }

    /**
     * The operation as {@code server.mustache} renders it.
     *
     * @param success the operation's success headers struct ({@link ResponseHeaders#plan} with a {@code name}), or
     *                {@code null}
     * @param errors  its error headers struct, or {@code null}
     * @param cfg     the cfg predicate of the operation's items, or {@code null}
     * @throws Unsupported when the server cannot serve it yet, with the reason
     */
    static Map<String, Object> plan(OpenAPI openAPI, CodegenOperation op, Operation spec,
            Map<String, CodegenModel> models, Map<String, Object> success, Map<String, Object> errors, String cfg)
            throws Unsupported {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", op.operationId);
        m.put("bare", bare(op.operationId));
        m.put("camel", op.operationIdCamelCase);
        m.put("method", op.httpMethod.toLowerCase(Locale.ROOT));
        m.put("httpMethod", op.httpMethod.toUpperCase(Locale.ROOT));
        m.put("path", op.path);
        m.put("summary", docLine(spec.getSummary()));
        if (cfg != null) {
            m.put("cfg", cfg);
        }

        // the route: every path parameter is a whole segment, named by its position
        Map<String, String> positions = new LinkedHashMap<>();
        List<String> segments = new ArrayList<>();
        for (String segment : op.path.split("/", -1)) {
            if (segment.indexOf('{') < 0 && segment.indexOf('}') < 0) {
                segments.add(segment);
                continue;
            }
            Matcher matcher = PATH_PARAM.matcher(segment);
            if (!matcher.matches()) {
                throw new Unsupported("the path segment '" + segment + "' is not one path parameter");
            }
            String position = "p" + positions.size();
            positions.put(matcher.group(1), position);
            segments.add("{" + position + "}");
        }
        m.put("route", String.join("/", segments));

        Set<String> fields = new HashSet<>(TAKEN);
        List<Map<String, Object>> pathParams = new ArrayList<>();
        for (CodegenParameter p : list(op.pathParams)) {
            String parse = parse(qualify(p.dataType, models), models);
            if (parse == null) {
                throw new Unsupported("the path parameter '" + p.baseName + "' (" + p.dataType + ") has no text form");
            }
            String position = positions.get(p.baseName);
            if (position == null) {
                throw new Unsupported("the path parameter '" + p.baseName + "' is not in the path");
            }
            Map<String, Object> f = field(p, fields);
            f.put("position", position);
            f.put("type", qualify(p.dataType, models));
            f.put("parse", parse);
            pathParams.add(f);
        }
        m.put("pathParams", pathParams);
        m.put("hasPath", !pathParams.isEmpty());

        List<Map<String, Object>> queryParams = new ArrayList<>();
        for (CodegenParameter p : list(op.queryParams)) {
            if (p.isDeepObject) {
                throw new Unsupported("the query parameter '" + p.baseName + "' is a deepObject");
            }
            boolean array = p.isArray && p.items != null;
            String item = qualify(array ? p.items.dataType : p.dataType, models);
            String parse = array && p.items.isContainer ? null : parse(item, models);
            if (parse == null) {
                throw new Unsupported("the query parameter '" + p.baseName + "' (" + p.dataType + ") has no text "
                        + "form");
            }
            Map<String, Object> f = field(p, fields);
            String type = array ? "Vec<" + item + ">" : item;
            boolean required = p.required && !p.isNullable;
            f.put("type", required ? type : "Option<" + type + ">");
            f.put("required", required);
            f.put("list", array);
            f.put("parse", parse);
            if (array && !p.vendorExtensions.containsKey(TimvwRustCodegen.REPEAT)) {
                f.put("separator", p.vendorExtensions.getOrDefault(TimvwRustCodegen.SEPARATOR, ","));
            }
            queryParams.add(f);
        }
        m.put("queryParams", queryParams);
        m.put("hasQuery", !queryParams.isEmpty());

        RequestBody body = spec.getRequestBody() == null ? null
                : ModelUtils.getReferencedRequestBody(openAPI, spec.getRequestBody());
        if (body != null && body.getContent() != null && !body.getContent().isEmpty()) {
            String media = notJson(body.getContent());
            if (media != null) {
                throw new Unsupported("its request body is " + media);
            }
            if (op.bodyParam == null) {
                throw new Unsupported("its request body has no type");
            }
            Map<String, Object> b = new LinkedHashMap<>();
            boolean required = Boolean.TRUE.equals(body.getRequired());
            String type = qualify(op.bodyParam.dataType, models);
            b.put("type", required ? type : "Option<" + type + ">");
            b.put("required", required);
            b.put("doc", docLine(body.getDescription()));
            m.put("body", b);
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        if (spec.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> e : spec.getResponses().entrySet()) {
                responses.add(response(openAPI, op, e.getKey(), e.getValue(), success, errors));
            }
        }
        if (responses.isEmpty()) {
            throw new Unsupported("it declares no response");
        }
        m.put("responses", responses);
        if (success != null) {
            m.put("successHeaders", success.get("name"));
        }
        if (errors != null) {
            m.put("errorHeaders", errors.get("name"));
        }
        return m;
    }

    private static Map<String, Object> field(CodegenParameter p, Set<String> fields) {
        String name = p.paramName;
        String bare = bare(name);
        if (!fields.add(bare)) {
            name = bare + "_" + (p.isPathParam ? "path" : "query");
            fields.add(name);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", name);
        f.put("name", literal(p.baseName));
        f.put("where", p.isPathParam ? "path" : "query");
        f.put("doc", docLine(p.unescapedDescription));
        return f;
    }

    /** The first media type of {@code content} that is not JSON, or {@code null}. */
    private static String notJson(Content content) {
        for (String media : content.keySet()) {
            if (!isJson(media)) {
                return "'" + media + "'";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(OpenAPI openAPI, CodegenOperation op, String code, ApiResponse raw,
            Map<String, Object> success, Map<String, Object> errors) throws Unsupported {
        ApiResponse response = ModelUtils.getReferencedApiResponse(openAPI, raw);
        Map<String, Object> r = new LinkedHashMap<>();
        boolean isDefault = "default".equals(code);
        String upper = code.toUpperCase(Locale.ROOT);
        boolean range = upper.length() == 3 && upper.endsWith("XX");
        if (!isDefault && !range && !code.matches("[1-5][0-9][0-9]")) {
            throw new Unsupported("the response code '" + code + "' is not a status");
        }
        r.put("code", code);
        r.put("variant", isDefault ? "Default" : "Status" + upper);
        r.put("withStatus", isDefault || range);
        if (!isDefault && !range) {
            r.put("status", code);
        }
        r.put("doc", docLine(response == null ? null : response.getDescription()));
        Content content = response == null ? null : response.getContent();
        if (content != null && !content.isEmpty()) {
            String media = notJson(content);
            if (media != null) {
                throw new Unsupported("its " + code + " response is " + media);
            }
            String type = null;
            for (CodegenResponse cr : list(op.responses)) {
                if (isDefault ? cr.isDefault : code.equalsIgnoreCase(cr.code)) {
                    type = cr.dataType;
                }
            }
            r.put("bodyType", type == null || type.isEmpty() ? "serde_json::Value" : type);
        }
        // the struct of its class: successes 2xx and 3xx (default when there are none), errors the others
        boolean ok = code.startsWith("2") || code.startsWith("3");
        Map<String, Object> struct = ok ? success : errors;
        if (isDefault && success != null && ((List<String>) success.get("codes")).contains(code)) {
            struct = success;
        }
        if (struct != null && ((List<String>) struct.get("codes")).contains(code)) {
            r.put("headersType", "crate::headers::" + struct.get("name"));
        }
        // `Status200(body)` when the body is all it holds, `Status204` when it holds nothing, named fields otherwise
        String variant = (String) r.get("variant");
        String bodyType = (String) r.get("bodyType");
        String headersType = (String) r.get("headersType");
        boolean withStatus = isDefault || range;
        List<String> declared = new ArrayList<>();
        List<String> bound = new ArrayList<>();
        if (withStatus) {
            declared.add("status: http::StatusCode");
            bound.add("status");
        }
        if (bodyType != null) {
            declared.add("body: " + bodyType);
            bound.add("body");
        }
        if (headersType != null) {
            declared.add("headers: " + headersType);
            bound.add("headers");
        }
        if (declared.isEmpty()) {
            r.put("declaration", variant);
            r.put("pattern", variant);
        } else if (!withStatus && headersType == null) {
            r.put("declaration", variant + "(" + bodyType + ")");
            r.put("pattern", variant + "(body)");
        } else {
            r.put("declaration", variant + " { " + String.join(", ", declared) + " }");
            r.put("pattern", variant + " { " + String.join(", ", bound) + " }");
        }
        String status = withStatus ? "status" : "super::status(" + code + ")";
        String headers = headersType != null ? "headers.to_header_map()" : "http::HeaderMap::new()";
        r.put("encode", bodyType != null ? "super::json_response(" + status + ", " + headers + ", &body)"
                : "super::empty_response(" + status + ", " + headers + ")");
        return r;
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
