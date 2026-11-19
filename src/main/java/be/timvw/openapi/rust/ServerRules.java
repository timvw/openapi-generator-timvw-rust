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
import org.openapitools.codegen.CodegenProperty;
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
 * <p>A deepObject query parameter ({@code name[key]=value}) is read into its type, as the client's
 * {@code parse_deep_object} writes it; a multipart field whose union has a file variant is an
 * {@code <Operation><Field>} enum of a {@code FilePart} (a part with a file name), its parts or one of the union's other
 * variants; a path with a fixed query ({@code /responses/{id}?beta=true}) is routed on its path, and the operations that
 * share a path and method are told apart by their fixed query ({@code serverDispatch} in {@code TimvwRustCodegen}).</p>
 *
 * <p>Left out, with a comment in the server module and a warning: a body that is both a multipart and an urlencoded
 * form, a query parameter whose type has no text form, and a path parameter that is not a whole path segment.</p>
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
            if (cm == null) {
                return null;
            }
            if (!cm.isEnum) {
                // a union (an order that is "asc" or "desc"): the text as a JSON string, else as JSON
                boolean union = UnionRules.variantsOf(cm) != null || !cm.oneOf.isEmpty() || !cm.anyOf.isEmpty();
                return union ? "serde_json::from_value::<" + type + ">(serde_json::Value::String(v.to_owned())).ok()"
                        + ".or_else(|| serde_json::from_str::<" + type + ">(v).ok())" : null;
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
     * @param checks  with {@code serverValidation}, the checks of the requests; {@code null} without
     * @throws Unsupported when the server cannot serve it yet, with the reason
     */
    static Map<String, Object> plan(OpenAPI openAPI, CodegenOperation op, Operation spec,
            Map<String, CodegenModel> models, Map<String, Object> success, Map<String, Object> errors, String cfg,
            Function<Schema<?>, String> typeOf, Function<String, CodegenOperation> forMedia,
            ValidateRules.RequestChecks checks) throws Unsupported {
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

        // a fixed query (`/responses/{id}?beta=true`) is not part of the route: the operations that share the route
        // are told apart by it
        String path = op.path;
        List<Map<String, Object>> fixed = new ArrayList<>();
        int question = path.indexOf('?');
        if (question >= 0) {
            for (String pair : path.substring(question + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", literal(kv[0]));
                f.put("value", literal(kv.length > 1 ? kv[1] : ""));
                fixed.add(f);
            }
            path = path.substring(0, question);
        }
        if (!fixed.isEmpty()) {
            fixed.get(fixed.size() - 1).put("last", true);
        }
        m.put("fixed", fixed);
        m.put("fixedText", question >= 0 ? op.path.substring(question + 1) : "");

        // the route: every path parameter is a whole segment, named by its position
        Map<String, String> positions = new LinkedHashMap<>();
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
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
        boolean deep = false;
        for (CodegenParameter p : list(op.queryParams)) {
            boolean required = p.required && !p.isNullable;
            String name = literal(p.baseName);
            if (p.isDeepObject) {
                // name[key]=value, nested name[a][b], array items name[a][0]: what the client's parse_deep_object writes
                Map<String, Object> f = field(p, fields);
                String type = qualify(p.dataType, models);
                f.put("type", required ? type : "Option<" + type + ">");
                String read = "super::query_deep(query, \"" + name + "\")?";
                f.put("read", required ? "super::required(\"query\", \"" + name + "\", " + read + ")?" : read);
                queryParams.add(f);
                deep = true;
                continue;
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
            f.put("type", required ? type : "Option<" + type + ">");
            String read;
            if (array) {
                String separator = p.vendorExtensions.containsKey(TimvwRustCodegen.REPEAT) ? "None"
                        : "Some(\"" + literal(String.valueOf(
                                p.vendorExtensions.getOrDefault(TimvwRustCodegen.SEPARATOR, ","))) + "\")";
                read = "super::query_list(query, \"" + name + "\", " + separator + ", |v| " + parse + ")?";
            } else {
                read = "super::query_value(query, \"" + name + "\", |v| " + parse + ")?";
            }
            f.put("read", required ? "super::required(\"query\", \"" + name + "\", " + read + ")?" : read);
            queryParams.add(f);
        }
        m.put("deepObject", deep);
        m.put("queryParams", queryParams);
        m.put("hasQuery", !queryParams.isEmpty());

        RequestBody body = spec.getRequestBody() == null ? null
                : ModelUtils.getReferencedRequestBody(openAPI, spec.getRequestBody());
        if (body != null && body.getContent() != null && !body.getContent().isEmpty()) {
            m.put("body", body(openAPI, op, body, models, forMedia));
        }
        if (checks != null) {
            validation(m, checks);
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

    /** {@code crate::models::validation::}, where the generated {@code Validate} is. */
    private static final String VALIDATION = "crate::models::validation::";

    /**
     * The checks of the request ({@code serverValidation}), as the body of {@code validate_into} of its request struct
     * ({@code validate}), and of its form struct ({@code formValidate}) and body enum ({@code enumValidate}): the
     * constraints of each path and query parameter at {@code path.<name>} and {@code query.<name>}, and the body's at
     * {@code body}: its models' own {@code validate()}, a form's fields' constraints. Nothing to check, no impl.
     */
    @SuppressWarnings("unchecked")
    private static void validation(Map<String, Object> m, ValidateRules.RequestChecks checks) {
        List<String> lines = new ArrayList<>();
        for (String where : List.of("path", "query")) {
            for (Map<String, Object> f : (List<Map<String, Object>>) m.get(where + "Params")) {
                lines.addAll(checks.parameter((CodegenParameter) f.get("param"), (String) f.get("field"),
                        (String) f.get("type"), "path.field(\"" + where + "\").field(\"" + f.get("name") + "\")"));
            }
        }
        Map<String, Object> b = (Map<String, Object>) m.get("body");
        if (b != null && bodyChecks(b, (String) m.get("camel") + "Form", checks)) {
            lines.add(VALIDATION + "Validate::validate_into(&self.body, &path.field(\"body\"), errors);");
        }
        if (!lines.isEmpty()) {
            m.put("validate", indent(lines, 12));
            m.put("validates", true);
        }
    }

    /** Whether the body has something to check; the checks of its form struct and body enum go into {@code b}. */
    @SuppressWarnings("unchecked")
    private static boolean bodyChecks(Map<String, Object> b, String formType,
            ValidateRules.RequestChecks checks) {
        boolean form = false;
        if (b.get("form") != null) {
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) b.get("form")) {
                CodegenParameter p = (CodegenParameter) f.get("param");
                if (p != null) {
                    lines.addAll(checks.parameter(p, (String) f.get("field"), (String) f.get("type"),
                            "path.field(\"" + f.get("name") + "\")"));
                }
            }
            if (!lines.isEmpty()) {
                b.put("formValidate", indent(lines, 12));
                b.put("formValidates", true);
                form = true;
            }
        }
        if (b.get("enum") == null) {
            if (b.get("form") != null) {
                return form;
            }
            return !checks.value("body", (String) b.get("type"), "path").isEmpty();
        }
        List<String> arms = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        List<List<String>> bodies = new ArrayList<>();
        int variants = 0;
        for (Map<String, Object> v : (List<Map<String, Object>>) b.get("variants")) {
            variants++;
            String type = (String) v.get("type");
            List<String> lines = type.equals(formType)
                    ? (form ? List.of(VALIDATION + "Validate::validate_into(value, path, errors);") : List.of())
                    : checks.binding("value", type);
            if (!lines.isEmpty()) {
                String pattern = b.get("enum") + "::" + v.get("variant") + "(value)";
                StringBuilder arm = new StringBuilder(pattern + " => {\n");
                lines.forEach(l -> arm.append("    ").append(l).append('\n'));
                arms.add(arm.append("}").toString());
                patterns.add(pattern);
                bodies.add(lines);
            }
        }
        if (arms.isEmpty()) {
            return false;
        }
        if (arms.size() == 1 && variants > 1) {
            // one variant to check of several: an if let, not a match with one arm (clippy::single_match)
            List<String> single = new ArrayList<>();
            single.add("if let " + patterns.get(0) + " = self {");
            bodies.get(0).forEach(l -> single.add("    " + l));
            single.add("}");
            b.put("enumValidate", indent(single, 12));
            b.put("enumValidates", true);
            return true;
        }
        List<String> match = new ArrayList<>();
        match.add("match self {");
        for (String arm : arms) {
            for (String l : arm.split("\n")) {
                match.add("    " + l);
            }
        }
        if (arms.size() < variants) {
            match.add("    _ => {}");
        }
        match.add("}");
        b.put("enumValidate", indent(match, 12));
        b.put("enumValidates", true);
        return true;
    }

    /** The lines, each indented by {@code spaces} and ended by a newline. */
    private static String indent(List<String> lines, int spaces) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(" ".repeat(spaces)).append(line).append('\n');
        }
        return out.toString();
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
            String type = one(openAPI, op, op.operationIdCamelCase, kind, media, models, b);
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
            String type = one(openAPI, own, op.operationIdCamelCase, kind, media, models, b);
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
    private static String one(OpenAPI openAPI, CodegenOperation op, String camel, Kind kind, String media,
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
                List<Map<String, Object>> unions = new ArrayList<>();
                b.put("form", form(openAPI, op, camel, models, false, unions));
                b.put("fileUnions", unions);
                b.put("formMedia", "multipart/form-data");
                b.put("hasForm", true);
                b.put("fileParts", true);
                return camel + "Form";
            case FORM:
                b.put("form", form(openAPI, op, camel, models, true, new ArrayList<>()));
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
     * ({@code urlencoded}), where a file cannot be and a list the client joins is split. A multipart field whose union
     * has a file variant gets an enum of its own, added to {@code unions} ({@link #fileUnion}).
     */
    private static List<Map<String, Object>> form(OpenAPI openAPI, CodegenOperation op, String camel,
            Map<String, CodegenModel> models, boolean urlencoded, List<Map<String, Object>> unions)
            throws Unsupported {
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (CodegenParameter p : list(op.formParams)) {
            String dataType = p.dataType == null ? "" : p.dataType;
            CodegenModel union = models.get(dataType.replaceFirst("^models::", ""));
            if (union != null && union.vendorExtensions.containsKey(UploadParams.FILE_UNION)) {
                if (urlencoded) {
                    throw new Unsupported("the urlencoded form field '" + p.baseName + "' is a file");
                }
                Map<String, Object> u = fileUnion(openAPI, op, camel, p, union, models);
                unions.add(u);
                String read = "form_" + u.get("function") + "(parts)?";
                boolean required = p.required && !p.isNullable;
                String field = p.paramName;
                if (!names.add(bare(field))) {
                    throw new Unsupported("two form fields are named '" + bare(field) + "'");
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("field", field);
                f.put("name", literal(p.baseName));
                f.put("doc", docLine(p.unescapedDescription));
                f.put("type", required ? (String) u.get("enum") : "Option<" + u.get("enum") + ">");
                f.put("decode", required ? "super::required(\"body\", \"" + literal(p.baseName) + "\", " + read
                        + ")?" : read);
                fields.add(f);
                continue;
            }
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
            f.put("param", p);
            fields.add(f);
        }
        return fields;
    }

    /**
     * The enum of a multipart field whose union has a file variant, {@code <Operation><Field>}: the union's variants, a
     * file a {@code FilePart} and an array of files a {@code Vec<FilePart>}, the others their own type. A part with a
     * file name is a file (the client always gives one); several, or parts named as the client names the parts of an
     * array of files ({@code files[]}), are the array. Any other part is the first other variant its JSON, else its text,
     * decodes into.
     */
    private static Map<String, Object> fileUnion(OpenAPI openAPI, CodegenOperation op, String camel,
            CodegenParameter p, CodegenModel union, Map<String, CodegenModel> models) {
        String fieldCamel = camel(bare(p.paramName));
        String enumType = camel + fieldCamel;
        List<Map<String, Object>> variants = new ArrayList<>();
        String file = null;
        String files = null;
        List<String> values = new ArrayList<>();
        for (CodegenProperty v : UnionRules.variants(union)) {
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("variant", v.name);
            variant.put("doc", docLine(v.description));
            if (Boolean.TRUE.equals(v.vendorExtensions.get(UploadParams.UPLOAD_VARIANT))) {
                variant.put("type", "super::FilePart");
                file = v.name;
            } else if (Boolean.TRUE.equals(v.vendorExtensions.get(UploadParams.UPLOADS_VARIANT))) {
                variant.put("type", "Vec<super::FilePart>");
                files = v.name;
            } else {
                String type = qualify(v.dataType, models);
                variant.put("type", type);
                values.add("if let Some(value) = super::part_value::<" + type + ">(part) {\n"
                        + "            return Ok(Some(" + enumType + "::" + v.name + "(value)));\n        }");
            }
            variants.add(variant);
        }
        String name = literal(p.baseName);
        String partsName = literal(MultipartRules.filesPartName(openAPI, op, p.baseName));
        String i = "        ";
        StringBuilder read = new StringBuilder();
        // the file parts: a part with a file name, under the field's name or the name of the parts of an array
        read.append(i).append("let files: Vec<&super::FormPart> = parts\n")
                .append(i).append("    .iter()\n")
                .append(i).append(partsName.equals(name)
                        ? "    .filter(|p| p.file_name.is_some() && p.name == \"" + name + "\")\n"
                        : "    .filter(|p| p.file_name.is_some() && (p.name == \"" + name + "\" || p.name == \""
                                + partsName + "\"))\n")
                .append(i).append("    .collect();\n");
        if (file != null || files != null) {
            read.append(i).append("if !files.is_empty() {\n");
            String all = i + "    return Ok(Some(" + enumType + "::" + files
                    + "(files.iter().map(|p| p.file()).collect())));\n";
            if (files != null && file != null) {
                read.append(i).append("    if files.len() > 1");
                if (!partsName.equals(name)) {
                    read.append(" || files.iter().any(|p| p.name != \"").append(name).append("\")");
                }
                read.append(" {\n").append("    ").append(all).append(i).append("    }\n");
            } else if (files != null) {
                read.append(all);
            }
            if (file != null) {
                read.append(i).append("    return Ok(Some(").append(enumType).append("::").append(file)
                        .append("(files[0].file())));\n");
            }
            read.append(i).append("}\n");
        }
        // any other part: the first other variant it decodes into
        if (values.isEmpty()) {
            read.append(i).append("if parts.iter().any(|p| p.name == \"").append(name).append("\") {\n")
                    .append(i).append("    return Err(super::RequestError::new(\"body\", Some(\"").append(name)
                    .append("\"), \"not a file\"));\n")
                    .append(i).append("}\n")
                    .append(i).append("Ok(None)");
        } else {
            read.append(i).append("let Some(part) = parts.iter().find(|p| p.name == \"").append(name)
                    .append("\" && p.file_name.is_none()) else {\n")
                    .append(i).append("    return Ok(None);\n")
                    .append(i).append("};\n");
            for (String value : values) {
                read.append(i).append(value).append("\n");
            }
            read.append(i).append("Err(super::RequestError::new(\"body\", Some(\"").append(name)
                    .append("\"), \"neither a file nor a value of its union\"))");
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("enum", enumType);
        u.put("name", name);
        u.put("function", bare(op.operationId) + "_" + bare(p.paramName));
        u.put("variants", variants);
        u.put("read", read.toString());
        return u;
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
        f.put("param", p);
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
