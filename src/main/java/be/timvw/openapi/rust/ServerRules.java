package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server target (slices 1 and 2 of docs/design/targets-and-server.md): what {@code server.mustache} renders for
 * one operation, or why the operation is left out.
 *
 * <p>The request struct holds the path and query parameters as typed fields ({@link #parse}), the body and the raw
 * header map (header and cookie parameters are read from there). The body is its model for JSON, a {@code String}
 * for {@code text/*} (and a string that is not binary), a {@code <Operation>Form} struct for
 * {@code multipart/form-data} (a {@code FilePart} per file part) and {@code application/x-www-form-urlencoded}, and
 * the bytes for anything else; a body of more than one kind is an {@code <Operation>Body} enum, a variant per kind. The response enum has a variant per declared status and kind of content:
 * {@code Status200(body)}, {@code Status204}, a {@code status} field for a range ({@code Status4XX}) and for
 * {@code Default}, and a {@code headers} field holding the operation's {@code <Operation>ResponseHeaders} (successes)
 * or {@code <Operation>ErrorHeaders} (errors) where the response declares headers. A response's JSON is its
 * model, an event stream an {@code EventStream} of the event type, {@code text/*} a {@code String} and anything else
 * a {@code Binary}; a status with more than one kind has a variant for each, the first ({@code JSON} when there is
 * one) unsuffixed and the others suffixed {@code Stream}, {@code Text} or {@code Binary}.</p>
 *
 * <p>Left out, with a comment in the server module and a warning: multipart fields that are unions with a file variant,
 * a body that is both a multipart and an urlencoded form, deepObject and object query parameters, and a path
 * parameter that is not a whole path segment.</p>
 */
final class ServerRules {

    /** Why an operation is not served. */
    static final class Unsupported extends Exception {
        Unsupported(String reason) {
            super(reason);
        }
    }

    private static final Set<String> PARSED = Set.of("i32", "i64", "f32", "f64", "bool", "uuid::Uuid",
            "chrono::NaiveDate", "jiff::civil::Date", "jiff::Timestamp");
    private static final String DATE_TIME = "chrono::DateTime<chrono::FixedOffset>";
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}/]+)}");
    /** Field names the request struct uses itself. */
    private static final Set<String> TAKEN = Set.of("headers", "body");

    private ServerRules() {
    }

    /** The kinds of content the server reads and writes. */
    enum Kind {
        JSON, STREAM, TEXT, BINARY, MULTIPART, FORM
    }

    /** The kind of a media type. */
    static Kind kind(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (isJson(m)) {
            return Kind.JSON;
        }
        if (m.equals("text/event-stream")) {
            return Kind.STREAM;
        }
        if (m.startsWith("multipart/")) {
            return Kind.MULTIPART;
        }
        if (m.equals("application/x-www-form-urlencoded")) {
            return Kind.FORM;
        }
        return m.startsWith("text/") ? Kind.TEXT : Kind.BINARY;
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
     * @param typeOf  the Rust type of a schema, as the models name it
     * @throws Unsupported when the server cannot serve it yet, with the reason
     */
    static Map<String, Object> plan(OpenAPI openAPI, CodegenOperation op, Operation spec,
            Map<String, CodegenModel> models, Map<String, Object> success, Map<String, Object> errors, String cfg,
            Function<Schema<?>, String> typeOf, Function<String, CodegenOperation> forMedia) throws Unsupported {
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
            m.put("body", body(openAPI, op, body, models, forMedia));
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        if (spec.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> e : spec.getResponses().entrySet()) {
                responses.addAll(response(openAPI, op, e.getKey(), e.getValue(), success, errors, typeOf));
            }
        }
        if (responses.isEmpty()) {
            throw new Unsupported("it declares no response");
        }
        m.put("responses", responses);
        // a stream or a binary body is read once: the enum is not Clone
        boolean cloned = responses.stream().allMatch(r -> !Boolean.TRUE.equals(r.get("once")));
        m.put("derive", cloned ? "Clone, Debug" : "Debug");
        if (success != null) {
            m.put("successHeaders", success.get("name"));
        }
        if (errors != null) {
            m.put("errorHeaders", errors.get("name"));
        }
        return m;
    }

    /**
     * The request body: its type, how it is decoded, and the struct of a form. A body of one kind of media type is
     * that kind's type; a body of more than one kind is an enum, {@code <Operation>Body}, with a variant per kind named
     * as the client names its function for that media type ({@code Json}, {@code Multipart}, {@code Form},
     * {@code Text}, {@code Bytes}, or the subtype: {@code Sdp}), chosen by the request's {@code Content-Type}.
     *
     * @param forMedia the operation as it is with only this request media type
     */
    private static Map<String, Object> body(OpenAPI openAPI, CodegenOperation op, RequestBody body,
            Map<String, CodegenModel> models, Function<String, CodegenOperation> forMedia) throws Unsupported {
        // the media types of each kind, in the order declared; a string that is not binary is text whatever its media
        // type (application/sdp), as the client sends it
        Map<Kind, List<String>> kinds = new LinkedHashMap<>();
        for (Map.Entry<String, io.swagger.v3.oas.models.media.MediaType> entry : body.getContent().entrySet()) {
            String media = entry.getKey();
            Kind kind = kind(media);
            if (kind == Kind.STREAM || kind == Kind.BINARY && plainString(openAPI, entry.getValue().getSchema())) {
                kind = Kind.TEXT;
            }
            kinds.computeIfAbsent(kind, k -> new ArrayList<>()).add(media);
        }
        if (kinds.containsKey(Kind.MULTIPART) && kinds.containsKey(Kind.FORM)) {
            throw new Unsupported("its request body is both a multipart and an urlencoded form");
        }
        boolean required = Boolean.TRUE.equals(body.getRequired());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("required", required);
        b.put("doc", docLine(body.getDescription()));
        if (kinds.size() == 1) {
            Kind kind = kinds.keySet().iterator().next();
            String media = kinds.get(kind).get(0);
            String type = one(op, op.operationIdCamelCase, kind, media, models, b);
            switch (kind) {
                case JSON:
                    b.put("decode", required ? "super::json_body(&body)?" : "super::optional_json_body(&body)?");
                    b.put("bytes", true);
                    break;
                case TEXT:
                    b.put("decode", required ? "super::text_body(&body)?" : "super::optional_text_body(&body)?");
                    b.put("bytes", true);
                    break;
                case BINARY:
                    // the bytes themselves when required: `body` alone
                    if (!required) {
                        b.put("decode", "super::optional_bytes(body)");
                    }
                    b.put("bytes", true);
                    break;
                case MULTIPART:
                    b.put("multipart", true);
                    b.put("decode", required ? "form_" + bare(op.operationId) + "(&parts)?"
                            : "parts.as_deref().map(form_" + bare(op.operationId) + ").transpose()?");
                    break;
                default:
                    b.put("decode", required ? "form_" + bare(op.operationId) + "(&super::form_pairs(&body))?"
                            : "super::optional_form_pairs(&body).as_deref().map(form_" + bare(op.operationId)
                                    + ").transpose()?");
                    b.put("bytes", true);
                    break;
            }
            b.put("type", required ? type : "Option<" + type + ">");
            return b;
        }

        // more than one kind: an enum, read by the request's Content-Type
        String enumType = op.operationIdCamelCase + "Body";
        List<Map<String, Object>> variants = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<Kind, List<String>> e : kinds.entrySet()) {
            Kind kind = e.getKey();
            String media = e.getValue().get(0);
            String name = camel(ContentTypeMethods.requestSuffix(media));
            if (!names.add(name)) {
                throw new Unsupported("two of its request media types are named '" + name + "'");
            }
            CodegenOperation own = forMedia.apply(media);
            if (own == null) {
                throw new Unsupported("its request body '" + media + "' has no type");
            }
            String type = one(own, op.operationIdCamelCase, kind, media, models, b);
            String value;
            String read;
            switch (kind) {
                case JSON:
                    read = "let bytes = super::request_bytes(request).await?;";
                    value = "super::json_body(&bytes)?";
                    break;
                case TEXT:
                    read = "let bytes = super::request_bytes(request).await?;";
                    value = "super::text_body(&bytes)?";
                    break;
                case BINARY:
                    read = "let bytes = super::request_bytes(request).await?;";
                    value = "bytes";
                    break;
                case MULTIPART:
                    b.put("multipartEnum", true);
                    read = "let parts = super::multipart_parts(request).await?;";
                    value = "form_" + bare(op.operationId) + "(&parts)?";
                    break;
                default:
                    read = "let bytes = super::request_bytes(request).await?;";
                    value = "form_" + bare(op.operationId) + "(&super::form_pairs(&bytes))?";
                    break;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("variant", name);
            v.put("type", type);
            v.put("media", String.join(", ", e.getValue()));
            List<String> quoted = new ArrayList<>();
            for (String m : e.getValue()) {
                quoted.add("\"" + literal(m.toLowerCase(Locale.ROOT).split(";", 2)[0].trim()) + "\"");
            }
            v.put("declared", String.join(", ", quoted));
            v.put("read", read);
            String variant = enumType + "::" + name + "(" + value + ")";
            v.put("value", required ? variant : "Some(" + variant + ")");
            variants.add(v);
        }
        b.put("enum", enumType);
        b.put("variants", variants);
        b.put("missing", required ? "Err(super::RequestError::new(\"body\", None, \"missing\"))" : "Ok(None)");
        b.put("type", required ? enumType : "Option<" + enumType + ">");
        return b;
    }

    /** The type of a body of one kind; a form's struct goes into {@code b}. */
    private static String one(CodegenOperation op, String camel, Kind kind, String media,
            Map<String, CodegenModel> models, Map<String, Object> b) throws Unsupported {
        switch (kind) {
            case JSON:
                if (op.bodyParam == null) {
                    throw new Unsupported("its request body has no type");
                }
                return qualify(op.bodyParam.dataType, models);
            case TEXT:
                return "String";
            case BINARY:
                return "axum::body::Bytes";
            case MULTIPART:
                if (!media.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                    throw new Unsupported("its request body is '" + media + "'");
                }
                b.put("form", form(op, models, false));
                b.put("formMedia", "multipart/form-data");
                b.put("hasForm", true);
                b.put("fileParts", true);
                return camel + "Form";
            case FORM:
                b.put("form", form(op, models, true));
                b.put("formMedia", "application/x-www-form-urlencoded");
                b.put("hasForm", true);
                return camel + "Form";
            default:
                throw new Unsupported("its request body is '" + media + "'");
        }
    }

    /** Whether a body's schema is a string that is not binary: text, whatever its media type. */
    static boolean plainString(OpenAPI openAPI, Schema<?> schema) {
        Schema<?> resolved = schema == null ? null : ModelUtils.getReferencedSchema(openAPI, schema);
        if (resolved == null || !"string".equals(ModelUtils.getType(resolved))) {
            return false;
        }
        String format = resolved.getFormat();
        return format == null || !(format.equals("binary") || format.equals("byte"));
    }

    /** {@code json} as {@code Json}, {@code x_png} as {@code XPng}. */
    static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String name = out.toString();
        return name.isEmpty() || !Character.isLetter(name.charAt(0)) ? "Body" + name : name;
    }

    /**
     * The fields of a form, each read from its parts: the parts of a multipart form, or the pairs of an urlencoded one
     * ({@code urlencoded}), where a file cannot be and a list the client joins is split.
     */
    private static List<Map<String, Object>> form(CodegenOperation op, Map<String, CodegenModel> models,
            boolean urlencoded) throws Unsupported {
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (CodegenParameter p : list(op.formParams)) {
            if (p.vendorExtensions.containsKey(MultipartRules.FILE_UNION)) {
                throw new Unsupported("the multipart field '" + p.baseName + "' is a union with a file variant");
            }
            String dataType = p.dataType == null ? "" : p.dataType;
            boolean array = p.isArray || dataType.startsWith("Vec<");
            boolean file = p.isFile || dataType.contains(UploadParams.PATH) || dataType.contains(UploadParams.UPLOAD);
            if (file && urlencoded) {
                throw new Unsupported("the urlencoded form field '" + p.baseName + "' is a file");
            }
            String name = literal(p.baseName);
            String type;
            String read;
            if (file) {
                type = array ? "Vec<super::FilePart>" : "super::FilePart";
                read = "super::form_file" + (array ? "s" : "") + "(parts, \"" + name + "\")";
            } else {
                String item = qualify(array && p.items != null ? p.items.dataType : dataType, models);
                String parse = array && p.items != null && p.items.isContainer ? null : parse(item, models);
                type = array ? "Vec<" + item + ">" : item;
                String separator = "None";
                if (urlencoded && array && p.vendorExtensions.containsKey(TimvwRustCodegen.FORM_JOINED)) {
                    separator = "Some(\"" + literal(String.valueOf(
                            p.vendorExtensions.getOrDefault(TimvwRustCodegen.FORM_SEPARATOR, ","))) + "\")";
                }
                if (parse != null) {
                    read = array ? "super::form_list(parts, \"" + name + "\", " + separator + ", |v| " + parse + ")?"
                            : "super::form_value(parts, \"" + name + "\", |v| " + parse + ")?";
                } else {
                    read = "super::form_json" + (array ? "_list" : "") + "(parts, \"" + name + "\")?";
                }
            }
            boolean required = p.required && !p.isNullable;
            String field = p.paramName;
            if (!names.add(bare(field))) {
                throw new Unsupported("two form fields are named '" + bare(field) + "'");
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("field", field);
            f.put("name", name);
            f.put("doc", docLine(p.unescapedDescription));
            f.put("type", required ? type : "Option<" + type + ">");
            f.put("decode", required ? "super::required(\"body\", \"" + name + "\", " + read + ")?" : read);
            fields.add(f);
        }
        return fields;
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

    /**
     * The variants of one declared response: one when it has no content or one kind of content, one per kind
     * otherwise.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> response(OpenAPI openAPI, CodegenOperation op, String code,
            ApiResponse raw, Map<String, Object> success, Map<String, Object> errors,
            Function<Schema<?>, String> typeOf) throws Unsupported {
        ApiResponse response = ModelUtils.getReferencedApiResponse(openAPI, raw);
        boolean isDefault = "default".equals(code);
        String upper = code.toUpperCase(Locale.ROOT);
        boolean range = upper.length() == 3 && upper.endsWith("XX");
        if (!isDefault && !range && !code.matches("[1-5][0-9][0-9]")) {
            throw new Unsupported("the response code '" + code + "' is not a status");
        }
        String doc = docLine(response == null ? null : response.getDescription());
        // the struct of its class: successes 2xx and 3xx (default when there are none), errors the others
        boolean ok = code.startsWith("2") || code.startsWith("3");
        Map<String, Object> struct = ok ? success : errors;
        if (isDefault && success != null && ((List<String>) success.get("codes")).contains(code)) {
            struct = success;
        }
        String headersType = struct != null && ((List<String>) struct.get("codes")).contains(code)
                ? "crate::headers::" + struct.get("name") : null;
        String variant = isDefault ? "Default" : "Status" + upper;

        Content content = response == null ? null : response.getContent();
        List<Map<String, Object>> variants = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            variants.add(variant(code, variant, doc, null, null, null, isDefault || range, headersType));
            return variants;
        }
        // one variant per kind, in the order declared, JSON first
        Map<Kind, String> kinds = new LinkedHashMap<>();
        for (String media : content.keySet()) {
            Kind kind = kind(media);
            if (kind == Kind.MULTIPART || kind == Kind.FORM) {
                kind = Kind.BINARY;
            }
            kinds.putIfAbsent(kind, media);
        }
        List<Kind> order = new ArrayList<>(kinds.keySet());
        if (order.remove(Kind.JSON)) {
            order.add(0, Kind.JSON);
        }
        String firstMedia = content.keySet().iterator().next();
        for (Kind kind : order) {
            String media = kinds.get(kind);
            String name = variant;
            if (kind != order.get(0)) {
                name += switch (kind) {
                    case STREAM -> "Stream";
                    case TEXT -> "Text";
                    default -> "Binary";
                };
            }

            String bodyType;
            String encode;
            switch (kind) {
                case JSON: {
                    String type = null;
                    if (kind(firstMedia) == Kind.JSON) {
                        for (CodegenResponse cr : list(op.responses)) {
                            if (isDefault ? cr.isDefault : code.equalsIgnoreCase(cr.code)) {
                                type = cr.dataType;
                            }
                        }
                    } else {
                        type = schemaType(content.get(media).getSchema(), typeOf);
                    }
                    bodyType = type == null || type.isEmpty() ? "serde_json::Value" : type;
                    encode = "super::json_response(%s, %s, &body)";
                    break;
                }
                case STREAM: {
                    Schema<?> schema = content.get(media).getSchema();
                    String type = schemaType(schema, typeOf);
                    bodyType = "super::EventStream<" + (type == null ? "serde_json::Value" : type) + ">";
                    String event = eventName(openAPI, schema);
                    encode = "super::event_stream_response(%s, %s, body, "
                            + (event == null ? "None" : "Some(\"" + literal(event).replace("%", "%%") + "\")") + ")";
                    break;
                }
                case TEXT:
                    bodyType = "String";
                    encode = "super::text_response(%s, %s, body, \"" + literal(media) + "\")";
                    break;
                default:
                    bodyType = "super::Binary";
                    encode = "super::binary_response(%s, %s, body, \""
                            + literal(media.contains("*") ? "application/octet-stream" : media) + "\")";
                    break;
            }
            Map<String, Object> r = variant(code, name, doc, bodyType, encode, kind, isDefault || range,
                    headersType);
            if (order.size() > 1) {
                r.put("media", media);
            }
            variants.add(r);
        }
        return variants;
    }

    /**
     * The property that names an event of this schema, written as the event's {@code event:} line: its
     * discriminator's, as OpenAI names the events of a Responses stream by their {@code type}. {@code null} when the
     * schema has no discriminator: only {@code data:} lines, as for chat completions.
     */
    static String eventName(OpenAPI openAPI, Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        Schema<?> resolved = ModelUtils.getReferencedSchema(openAPI, schema);
        if (resolved == null || resolved.getDiscriminator() == null) {
            return null;
        }
        String name = resolved.getDiscriminator().getPropertyName();
        return name == null || name.isBlank() ? null : name;
    }

    /** The Rust type of a response's schema, or {@code null} when it has none. */
    private static String schemaType(Schema<?> schema, Function<Schema<?>, String> typeOf) {
        if (schema == null) {
            return null;
        }
        String type = typeOf.apply(schema);
        return type == null || type.isEmpty() ? null : type;
    }

    /** One variant of a response enum: its declaration, the pattern that binds its fields, and its encoding. */
    private static Map<String, Object> variant(String code, String variant, String doc, String bodyType,
            String encode, Kind kind, boolean withStatus, String headersType) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("variant", variant);
        r.put("doc", doc);
        r.put("withStatus", withStatus);
        if (bodyType != null) {
            r.put("bodyType", bodyType);
        }
        if (headersType != null) {
            r.put("headersType", headersType);
        }
        r.put("once", kind == Kind.STREAM || kind == Kind.BINARY);
        // `Status200(body)` when the body is all it holds, `Status204` when it holds nothing, named fields otherwise
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
        r.put("encode", bodyType != null ? String.format(encode, status, headers)
                : "super::empty_response(" + status + ", " + headers + ")");
        return r;
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
