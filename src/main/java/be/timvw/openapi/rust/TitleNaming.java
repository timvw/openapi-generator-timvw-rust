package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps schema {@code title}s out of type naming.
 *
 * <p>Since openapi-generator 7.2x the inline-model resolver treats a title as a type name: two component schemas
 * with the same title and structure are merged into one model, an inline schema that matches a titled component is
 * replaced by it, and an inline array's title names its item type ({@code ArrayOfItemsInner5}). Specs that use
 * titles as display text (OpenAI's has 214 titles shared by several schemas) lose public types that way. With
 * {@code titleNaming=false} the generator removes the titles that take part in naming before the resolver runs,
 * so that type names stay derived from the schema key and the property path, as on openapi-generator 7.15.</p>
 *
 * <p>Titles on inline object and composed schemas are kept: there the title has always been the name.</p>
 */
final class TitleNaming {

    private TitleNaming() {
    }

    /**
     * Removes the title from every component schema and from every array schema in the document.
     *
     * @return the JSON-pointer-like locations whose title was removed, in document order
     */
    static List<String> stripNamingTitles(OpenAPI openAPI) {
        List<String> stripped = new ArrayList<>();
        Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return stripped;
        }
        for (Map.Entry<String, Schema> entry : openAPI.getComponents().getSchemas().entrySet()) {
            String where = "#/components/schemas/" + entry.getKey();
            Schema<?> schema = entry.getValue();
            if (schema.getTitle() != null) {
                schema.setTitle(null);
                stripped.add(where);
            }
            visit(schema, where, seen, stripped);
        }
        return stripped;
    }

    private static boolean isArray(Schema<?> schema) {
        return "array".equals(schema.getType())
                || (schema.getTypes() != null && schema.getTypes().contains("array"))
                || schema.getItems() != null;
    }

    @SuppressWarnings("rawtypes")
    private static void visit(Schema<?> schema, String where, Set<Schema<?>> seen, List<String> stripped) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        if (schema.getTitle() != null && isArray(schema) && schema.get$ref() == null) {
            schema.setTitle(null);
            stripped.add(where);
        }
        if (schema.getProperties() != null) {
            for (Map.Entry<String, Schema> e : schema.getProperties().entrySet()) {
                visit(e.getValue(), where + "/properties/" + e.getKey(), seen, stripped);
            }
        }
        visit(schema.getItems(), where + "/items", seen, stripped);
        if (schema.getAdditionalProperties() instanceof Schema) {
            visit((Schema) schema.getAdditionalProperties(), where + "/additionalProperties", seen, stripped);
        }
        visitAll(schema.getAllOf(), where + "/allOf", seen, stripped);
        visitAll(schema.getOneOf(), where + "/oneOf", seen, stripped);
        visitAll(schema.getAnyOf(), where + "/anyOf", seen, stripped);
        visit(schema.getNot(), where + "/not", seen, stripped);
    }

    @SuppressWarnings("rawtypes")
    private static void visitAll(List<Schema> schemas, String where, Set<Schema<?>> seen, List<String> stripped) {
        if (schemas != null) {
            for (int i = 0; i < schemas.size(); i++) {
                visit(schemas.get(i), where + "/" + i, seen, stripped);
            }
        }
    }
}
