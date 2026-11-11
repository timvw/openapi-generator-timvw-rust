package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the schemas that hold only {@code null}, or any value or {@code null}, as {@code {}} (any value), which the
 * generator renders as {@code serde_json::Value} ({@code schema.type.null}, {@code schema.oneOf.any-or-null} in the
 * mapping). Left alone, the base generator references models that are never written: {@code models::Null} for
 * {@code type: 'null'} and {@code models::OneOfLessThanGreaterThan} for {@code oneOf: [{}, {type: 'null'}]}, and a
 * component of the latter is an empty struct, which {@code null} does not decode to.
 *
 * <ul>
 *   <li>A {@code oneOf} or {@code anyOf} whose branches are all {@code {}} or {@code type: 'null'} (and at least one
 *       {@code {}}), on a schema that declares nothing else, is {@code {}}: {@code serde_json::Value} holds the value
 *       and {@code null} ({@code Value::Null}). Read strictly, a {@code oneOf} of the two does not match {@code null}
 *       (both branches do); it is read as the "any value or null" it is written for.</li>
 *   <li>A schema of {@code type: 'null'} alone ({@code type: [null]}, or a union of only {@code null} branches) is
 *       {@code {}} too, as a property, list item, map value, component, request body or response: the JSON is
 *       {@code null}, which {@code serde_json::Value::Null} decodes and encodes; the Rust type does not restrict it
 *       to {@code null}. A {@code null} branch of a union with other branches is left as it is: it makes the union
 *       nullable ({@code schema.oneOf.null-branch}).</li>
 *   <li>A query, path, header or cookie parameter, or a form field (urlencoded or multipart), of {@code type: 'null'}
 *       alone (or a list of it) fails generation: such a value has no {@code null}, so it could never be sent.</li>
 * </ul>
 */
final class NullSchemas {

    private static final String NULL = "null";

    private NullSchemas() {
    }

    /**
     * Rewrites the schemas of the document in place.
     *
     * @return the locations that were rewritten, in document order
     * @throws IllegalArgumentException for a parameter or form field that can only be {@code null}
     */
    static List<String> rewrite(OpenAPI openAPI) {
        Walker walker = new Walker(openAPI);
        walker.document();
        return walker.rewritten;
    }

    /** Whether {@code s} is {@code {}}: no type, reference, structure, composition or value constraint. */
    @SuppressWarnings("rawtypes")
    static boolean isAny(Schema s) {
        return s != null && s.get$ref() == null && s.getType() == null && empty(s.getTypes())
                && empty(s.getProperties()) && s.getItems() == null && s.getAdditionalProperties() == null
                && empty(s.getAllOf()) && empty(s.getOneOf()) && empty(s.getAnyOf()) && s.getNot() == null
                && s.getEnum() == null && s.getConst() == null && s.getFormat() == null && empty(s.getPrefixItems());
    }

    /** Whether {@code s} is {@code type: 'null'} (3.1 {@code type: [null]}) and nothing else. */
    @SuppressWarnings("rawtypes")
    static boolean isNullOnly(Schema s) {
        if (s == null || s.get$ref() != null) {
            return false;
        }
        boolean typeNull = NULL.equals(s.getType()) && (empty(s.getTypes()) || onlyNull(s.getTypes()));
        boolean typesNull = s.getType() == null && onlyNull(s.getTypes());
        return (typeNull || typesNull) && empty(s.getProperties()) && s.getItems() == null
                && s.getAdditionalProperties() == null && empty(s.getAllOf()) && empty(s.getOneOf())
                && empty(s.getAnyOf()) && empty(s.getPrefixItems());
    }

    @SuppressWarnings("rawtypes")
    private static boolean onlyNull(Set types) {
        return types != null && types.size() == 1 && types.contains(NULL);
    }

    private static boolean empty(java.util.Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private static boolean empty(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Walker {
        private final OpenAPI openAPI;
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<String> rewritten = new ArrayList<>();
        /** The first pass only checks the parameters and form fields, before any schema is rewritten. */
        private boolean checking;

        Walker(OpenAPI openAPI) {
            this.openAPI = openAPI;
        }

        void document() {
            checking = true;
            walk();
            checking = false;
            seen.clear();
            walk();
        }

        private void walk() {
            Components c = openAPI.getComponents();
            if (c != null) {
                forEach(c.getSchemas(), (name, s) -> schema(s, "#/components/schemas/" + name, false));
                forEach(c.getParameters(), (name, p) -> parameter(p, "#/components/parameters/" + name));
                forEach(c.getRequestBodies(), (name, b) -> requestBody(b, "#/components/requestBodies/" + name));
                forEach(c.getResponses(), (name, r) -> response(r, "#/components/responses/" + name));
                forEach(c.getHeaders(), (name, h) -> header(h, "#/components/headers/" + name));
                forEach(c.getPathItems(), (name, p) -> pathItem(p, "#/components/pathItems/" + name));
                forEach(c.getCallbacks(), (name, cb) -> callback(cb, "#/components/callbacks/" + name));
            }
            forEach(openAPI.getPaths(), (path, item) -> pathItem(item, "#/paths/" + path));
            forEach(openAPI.getWebhooks(), (name, item) -> pathItem(item, "#/webhooks/" + name));
        }

        private void pathItem(PathItem item, String where) {
            if (item == null || !seen.add(item)) {
                return;
            }
            if (item.getParameters() != null) {
                for (Parameter p : item.getParameters()) {
                    parameter(p, where + "/parameters/" + p.getName());
                }
            }
            for (Map.Entry<PathItem.HttpMethod, Operation> e : item.readOperationsMap().entrySet()) {
                Operation op = e.getValue();
                String opWhere = where + "/" + e.getKey().name().toLowerCase(Locale.ROOT);
                if (op.getParameters() != null) {
                    for (Parameter p : op.getParameters()) {
                        parameter(p, opWhere + "/parameters/" + p.getName());
                    }
                }
                requestBody(op.getRequestBody(), opWhere + "/requestBody");
                forEach(op.getResponses(), (code, r) -> response(r, opWhere + "/responses/" + code));
                forEach(op.getCallbacks(), (name, cb) -> callback(cb, opWhere + "/callbacks/" + name));
            }
        }

        private void callback(Callback cb, String where) {
            forEach(cb, (expr, item) -> pathItem(item, where + "/" + expr));
        }

        private void parameter(Parameter p, String where) {
            if (p == null) {
                return;
            }
            Parameter resolved = p.get$ref() != null ? component(p.get$ref(), openAPI.getComponents() == null
                    ? null : openAPI.getComponents().getParameters()) : p;
            if (resolved == null) {
                return;
            }
            if (checking) {
                if (!"body".equals(resolved.getIn()) && onlyNullValue(resolved.getSchema())) {
                    throw new IllegalArgumentException(String.format("%s: %s: parameter '%s' can only be null ("
                                    + "type: 'null'), and a %s parameter has no null: it could never be sent; "
                                    + "remove it or give it a type",
                            TimvwRustCodegen.GENERATOR_NAME, where, resolved.getName(), resolved.getIn()));
                }
                return;
            }
            schema(resolved.getSchema(), where + "/schema", false);
            content(resolved.getContent(), where + "/content");
        }

        private void requestBody(RequestBody b, String where) {
            if (b == null) {
                return;
            }
            RequestBody resolved = b.get$ref() != null ? component(b.get$ref(), openAPI.getComponents() == null
                    ? null : openAPI.getComponents().getRequestBodies()) : b;
            if (resolved == null) {
                return;
            }
            if (!checking) {
                content(resolved.getContent(), where + "/content");
                return;
            }
            if (resolved.getContent() == null) {
                return;
            }
            for (Map.Entry<String, MediaType> e : resolved.getContent().entrySet()) {
                String mediaType = e.getKey().toLowerCase(Locale.ROOT);
                if (!mediaType.startsWith("application/x-www-form-urlencoded")
                        && !mediaType.startsWith("multipart/form-data")) {
                    continue;
                }
                Schema form = resolve(e.getValue().getSchema());
                if (form == null || form.getProperties() == null) {
                    continue;
                }
                for (Map.Entry<String, Schema> field : ((Map<String, Schema>) form.getProperties()).entrySet()) {
                    if (onlyNullValue(field.getValue())) {
                        throw new IllegalArgumentException(String.format("%s: %s/content/%s: form field '%s' can "
                                        + "only be null (type: 'null'), and a form field has no null: it could "
                                        + "never be sent; remove it or give it a type",
                                TimvwRustCodegen.GENERATOR_NAME, where, e.getKey(), field.getKey()));
                    }
                }
            }
        }

        private void response(ApiResponse r, String where) {
            if (r == null) {
                return;
            }
            content(r.getContent(), where + "/content");
            forEach(r.getHeaders(), (name, h) -> header(h, where + "/headers/" + name));
        }

        private void header(Header h, String where) {
            if (h != null) {
                schema(h.getSchema(), where + "/schema", false);
                content(h.getContent(), where + "/content");
            }
        }

        private void content(Content content, String where) {
            forEach(content, (mediaType, m) -> {
                if (m != null) {
                    schema(m.getSchema(), where + "/" + mediaType + "/schema", false);
                }
            });
        }

        /**
         * Rewrites {@code s} and the schemas in it. A branch of a union ({@code branch}) of {@code type: 'null'}
         * stays: it makes the union nullable.
         */
        private void schema(Schema s, String where, boolean branch) {
            if (checking || s == null || !seen.add(s)) {
                return;
            }
            forEach((Map<String, Schema>) s.getProperties(), (name, p) -> schema(p, where + "/properties/" + name,
                    false));
            schema(s.getItems(), where + "/items", false);
            if (s.getAdditionalProperties() instanceof Schema additional) {
                schema(additional, where + "/additionalProperties", false);
            }
            branches(s.getAllOf(), where + "/allOf");
            branches(s.getOneOf(), where + "/oneOf");
            branches(s.getAnyOf(), where + "/anyOf");
            schema(s.getNot(), where + "/not", true);

            if (collapse(s, s.getOneOf()) || collapse(s, s.getAnyOf())) {
                boolean onlyNulls = all(s.getOneOf() != null ? s.getOneOf() : s.getAnyOf(), true);
                s.setOneOf(null);
                s.setAnyOf(null);
                if (onlyNulls) {
                    s.setTypes(new java.util.LinkedHashSet<>(List.of(NULL)));
                    s.setType(NULL);
                } else {
                    rewritten.add(where);
                }
            }
            if (!branch && isNullOnly(s)) {
                s.setType(null);
                s.setTypes(null);
                s.setNullable(null);
                rewritten.add(where);
            }
        }

        private void branches(List<Schema> list, String where) {
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    schema(list.get(i), where + "/" + i, true);
                }
            }
        }

        /** Whether {@code union}, a branch list of {@code s}, is only {@code {}} and {@code null} branches. */
        private boolean collapse(Schema s, List<Schema> union) {
            if (empty(union) || !empty(s.getOneOf()) && !empty(s.getAnyOf()) || !empty(s.getAllOf())
                    || s.getType() != null || !empty(s.getTypes()) || !empty(s.getProperties())
                    || s.getItems() != null || s.getAdditionalProperties() != null || s.getDiscriminator() != null) {
                return false;
            }
            return union.stream().allMatch(b -> isAny(b) || isNullOnly(b));
        }

        private boolean all(List<Schema> union, boolean nullOnly) {
            return union.stream().allMatch(b -> nullOnly ? isNullOnly(b) : isAny(b));
        }

        /** Whether a parameter's or field's value can only be null: {@code type: 'null'}, or a list of it. */
        private boolean onlyNullValue(Schema s) {
            Schema resolved = resolve(s);
            if (resolved == null) {
                return false;
            }
            if (isNullOnly(resolved)) {
                return true;
            }
            List<Schema> union = !empty(resolved.getOneOf()) ? resolved.getOneOf() : resolved.getAnyOf();
            if (!empty(union) && union.stream().allMatch(b -> isNullOnly(resolve(b)))) {
                return true;
            }
            return resolved.getItems() != null && onlyNullValue(resolved.getItems());
        }

        private Schema resolve(Schema s) {
            Schema current = s;
            for (int depth = 0; current != null && current.get$ref() != null && depth < 32; depth++) {
                current = component(current.get$ref(), openAPI.getComponents() == null ? null
                        : openAPI.getComponents().getSchemas());
            }
            return current;
        }

        private static <T> T component(String ref, Map<String, T> components) {
            if (ref == null || components == null) {
                return null;
            }
            return components.get(ref.substring(ref.lastIndexOf('/') + 1));
        }
    }

    private interface Visitor<T> {
        void accept(String key, T value);
    }

    private static <T> void forEach(Map<String, T> map, Visitor<T> visitor) {
        if (map != null) {
            for (Map.Entry<String, T> e : map.entrySet()) {
                visitor.accept(e.getKey(), e.getValue());
            }
        }
    }
}
