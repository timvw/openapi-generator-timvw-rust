package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
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
import java.util.Map;
import java.util.Set;

/**
 * Repairs spec constructs that are invalid in OpenAPI but that the generator would otherwise render wrongly.
 *
 * <p>Only semantics-preserving repairs belong here: a schema is never invented and nothing is added that the
 * upstream document lacks. Anything else must fail generation, so that the fix lands upstream.</p>
 */
final class SpecNormalizer {

    private SpecNormalizer() {
    }

    /**
     * Rewrites {@code type: integer} with {@code format: float|double} to {@code type: number}, the type OpenAPI
     * defines for those formats. Left alone, the Rust generator treats the schema as an integer and, for enums,
     * can produce duplicate variants.
     *
     * @return the JSON-pointer-like locations that were rewritten, in document order
     */
    static List<String> integerWithFloatFormatToNumber(OpenAPI openAPI) {
        List<String> fixed = new ArrayList<>();
        Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (openAPI.getComponents() != null) {
            forEach(openAPI.getComponents().getSchemas(), (name, s) -> visit(s, "#/components/schemas/" + name, seen, fixed));
            forEach(openAPI.getComponents().getParameters(), (name, p) -> visitParameter(p, "#/components/parameters/" + name, seen, fixed));
            forEach(openAPI.getComponents().getRequestBodies(), (name, b) -> visitRequestBody(b, "#/components/requestBodies/" + name, seen, fixed));
            forEach(openAPI.getComponents().getResponses(), (name, r) -> visitResponse(r, "#/components/responses/" + name, seen, fixed));
            forEach(openAPI.getComponents().getHeaders(), (name, h) -> visitHeader(h, "#/components/headers/" + name, seen, fixed));
        }
        if (openAPI.getPaths() != null) {
            for (Map.Entry<String, PathItem> path : openAPI.getPaths().entrySet()) {
                String where = "#/paths/" + path.getKey();
                PathItem item = path.getValue();
                if (item.getParameters() != null) {
                    for (Parameter p : item.getParameters()) {
                        visitParameter(p, where + "/parameters/" + p.getName(), seen, fixed);
                    }
                }
                for (Map.Entry<PathItem.HttpMethod, Operation> entry : item.readOperationsMap().entrySet()) {
                    Operation op = entry.getValue();
                    String opWhere = where + "/" + entry.getKey().name().toLowerCase();
                    if (op.getParameters() != null) {
                        for (Parameter p : op.getParameters()) {
                            visitParameter(p, opWhere + "/parameters/" + p.getName(), seen, fixed);
                        }
                    }
                    visitRequestBody(op.getRequestBody(), opWhere + "/requestBody", seen, fixed);
                    if (op.getResponses() != null) {
                        forEach(op.getResponses(), (code, r) -> visitResponse(r, opWhere + "/responses/" + code, seen, fixed));
                    }
                }
            }
        }
        return fixed;
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

    private static void visitParameter(Parameter p, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (p == null) {
            return;
        }
        visit(p.getSchema(), where + "/schema", seen, fixed);
        visitContent(p.getContent(), where + "/content", seen, fixed);
    }

    private static void visitRequestBody(RequestBody b, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (b != null) {
            visitContent(b.getContent(), where + "/content", seen, fixed);
        }
    }

    private static void visitResponse(ApiResponse r, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (r == null) {
            return;
        }
        visitContent(r.getContent(), where + "/content", seen, fixed);
        forEach(r.getHeaders(), (name, h) -> visitHeader(h, where + "/headers/" + name, seen, fixed));
    }

    private static void visitHeader(Header h, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (h != null) {
            visit(h.getSchema(), where + "/schema", seen, fixed);
            visitContent(h.getContent(), where + "/content", seen, fixed);
        }
    }

    private static void visitContent(Content content, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (content == null) {
            return;
        }
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            visit(e.getValue().getSchema(), where + "/" + e.getKey() + "/schema", seen, fixed);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void visit(Schema<?> schema, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        if (isIntegerWithFloatFormat(schema)) {
            schema.setType("number");
            if (schema.getTypes() != null && schema.getTypes().remove("integer")) {
                schema.addType("number");
            }
            fixed.add(where);
        }
        forEach(schema.getProperties(), (name, s) -> visit(s, where + "/properties/" + name, seen, fixed));
        visit(schema.getItems(), where + "/items", seen, fixed);
        if (schema.getAdditionalProperties() instanceof Schema) {
            visit((Schema) schema.getAdditionalProperties(), where + "/additionalProperties", seen, fixed);
        }
        visitAll(schema.getAllOf(), where + "/allOf", seen, fixed);
        visitAll(schema.getOneOf(), where + "/oneOf", seen, fixed);
        visitAll(schema.getAnyOf(), where + "/anyOf", seen, fixed);
        visit(schema.getNot(), where + "/not", seen, fixed);
    }

    @SuppressWarnings("rawtypes")
    private static void visitAll(List<Schema> schemas, String where, Set<Schema<?>> seen, List<String> fixed) {
        if (schemas != null) {
            for (int i = 0; i < schemas.size(); i++) {
                visit(schemas.get(i), where + "/" + i, seen, fixed);
            }
        }
    }

    private static boolean isIntegerWithFloatFormat(Schema<?> schema) {
        String format = schema.getFormat();
        if (!"float".equals(format) && !"double".equals(format)) {
            return false;
        }
        return "integer".equals(schema.getType())
                || (schema.getTypes() != null && schema.getTypes().contains("integer"));
    }
}
