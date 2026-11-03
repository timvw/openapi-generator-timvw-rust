package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Milestone 2.5: how each multipart form parameter is serialized when {@code multipartHelper} is on. Files go through
 * the helper in the template's file branch; for the other parameters this class sets one of the vendor extensions
 * the template switches on, from the parameter's schema and its {@code encoding} entry:
 *
 * <ul>
 *   <li>{@code x-multipart-json-items}: an optional array of scalars or objects, sent as one part per item (the
 *       {@code form}/{@code explode} default); objects and arrays as {@code application/json};</li>
 *   <li>{@code x-multipart-json-value}: an optional object, or a union with an object branch, sent as one JSON
 *       part;</li>
 *   <li>{@code x-multipart-text-media-type}: a scalar with an explicit {@code contentType}, sent with that media
 *       type;</li>
 *   <li>{@code x-multipart-stock}: anything else, rendered by the stock branch.</li>
 * </ul>
 *
 * <p>{@code x-multipart-content-type} carries an explicit {@code contentType}. The rules follow
 * {@code fix_multipart_serialization.py} of openai-client-base, which this replaces.</p>
 */
final class MultipartRules {

    static final String JSON_ITEMS = "x-multipart-json-items";
    static final String JSON_VALUE = "x-multipart-json-value";
    static final String TEXT_MEDIA_TYPE = "x-multipart-text-media-type";
    static final String STOCK = "x-multipart-stock";
    static final String CONTENT_TYPE = "x-multipart-content-type";

    private static final Set<String> SCALARS = new HashSet<>(Arrays.asList("string", "integer", "number", "boolean"));

    private MultipartRules() {
    }

    /** Sets the multipart vendor extensions on every non-file form parameter of a multipart operation. */
    static void classify(OpenAPI openAPI, CodegenOperation op, Consumer<String> warn) {
        Map<String, Schema> properties = null;
        Map<String, Encoding> encodings = null;
        MediaType media = multipartMedia(openAPI, op);
        if (media != null) {
            Schema<?> root = resolve(openAPI, media.getSchema());
            if (root != null) {
                properties = root.getProperties();
            }
            encodings = media.getEncoding();
        }
        for (CodegenParameter p : op.formParams) {
            if (p.isFile) {
                continue;
            }
            Schema<?> schema = properties == null ? null : resolve(openAPI, properties.get(p.baseName));
            Encoding encoding = encodings == null ? null : encodings.get(p.baseName);
            String rule = rule(openAPI, schema, encoding, p.required);
            String contentType = encoding == null ? null : encoding.getContentType();
            if (JSON_ITEMS.equals(rule) && encoding != null && encoding.getStyle() != null) {
                contentType = null; // an explicit style makes contentType inapplicable (OAS 3.1)
            }
            p.vendorExtensions.put(rule, true);
            if (contentType != null && !STOCK.equals(rule)) {
                p.vendorExtensions.put(CONTENT_TYPE, contentType);
            }
            if (STOCK.equals(rule) && contentType != null) {
                warn.accept(op.operationId + "." + p.baseName + ": encoding contentType " + contentType
                        + " is not applied to this parameter shape");
            }
        }
    }

    private static String rule(OpenAPI openAPI, Schema<?> schema, Encoding encoding, boolean required) {
        if (schema == null || hasBinary(openAPI, schema, new HashSet<>())) {
            return STOCK;
        }
        Set<String> shape = leafShapes(openAPI, schema, new HashSet<>());
        if (hasType(schema, "array")) {
            if (required) {
                return STOCK;
            }
            Schema<?> items = resolve(openAPI, schema.getItems());
            Set<String> itemShapes = items == null ? Set.of("unknown") : leafShapes(openAPI, items, new HashSet<>());
            boolean scalarItems = itemShapes.size() == 1 && SCALARS.contains(itemShapes.iterator().next());
            if (!scalarItems && !itemShapes.equals(Set.of("object"))) {
                return STOCK;
            }
            boolean explode = encoding == null || encoding.getExplode() == null || encoding.getExplode();
            return explode ? JSON_ITEMS : STOCK;
        }
        if (shape.contains("object")) {
            Set<String> allowed = new HashSet<>(SCALARS);
            allowed.add("object");
            allowed.add("null");
            return !required && allowed.containsAll(shape) ? JSON_VALUE : STOCK;
        }
        Set<String> scalarOrNull = new HashSet<>(SCALARS);
        scalarOrNull.add("null");
        if (scalarOrNull.containsAll(shape) && encoding != null && encoding.getContentType() != null) {
            return TEXT_MEDIA_TYPE;
        }
        return STOCK;
    }

    private static MediaType multipartMedia(OpenAPI openAPI, CodegenOperation op) {
        if (openAPI == null || openAPI.getPaths() == null || op.path == null || op.httpMethod == null) {
            return null;
        }
        PathItem item = openAPI.getPaths().get(op.path);
        if (item == null) {
            return null;
        }
        Operation operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(op.httpMethod.toUpperCase()));
        if (operation == null || operation.getRequestBody() == null) {
            return null;
        }
        RequestBody body = operation.getRequestBody();
        if (body.get$ref() != null && openAPI.getComponents() != null
                && openAPI.getComponents().getRequestBodies() != null) {
            body = openAPI.getComponents().getRequestBodies().get(SpecRules.refName(body.get$ref()));
        }
        if (body == null || body.getContent() == null) {
            return null;
        }
        return body.getContent().get("multipart/form-data");
    }

    @SuppressWarnings("rawtypes")
    static Schema<?> resolve(OpenAPI openAPI, Schema<?> schema) {
        Set<String> seen = new HashSet<>();
        while (schema != null && schema.get$ref() != null) {
            String name = SpecRules.refName(schema.get$ref());
            if (!seen.add(name)) {
                return schema;
            }
            Schema target = SpecRules.schemas(openAPI).get(name);
            if (target == null) {
                return schema;
            }
            schema = target;
        }
        return schema;
    }

    static boolean hasType(Schema<?> schema, String type) {
        return type.equals(schema.getType()) || (schema.getTypes() != null && schema.getTypes().contains(type));
    }

    @SuppressWarnings("rawtypes")
    private static Set<String> leafShapes(OpenAPI openAPI, Schema<?> schema, Set<Schema<?>> active) {
        if (schema == null) {
            return Set.of("unknown");
        }
        if ("binary".equals(schema.getFormat())) {
            return Set.of("binary");
        }
        for (List<Schema> composition : Arrays.asList(schema.getOneOf(), schema.getAnyOf(), schema.getAllOf())) {
            if (composition != null) {
                Set<String> result = new LinkedHashSet<>();
                if (!active.add(schema)) {
                    return Set.of("unknown");
                }
                for (Schema alternative : composition) {
                    result.addAll(leafShapes(openAPI, resolve(openAPI, alternative), active));
                }
                active.remove(schema);
                return result;
            }
        }
        if (hasTypeOnly(schema, "array")) {
            return Set.of("array");
        }
        if (hasTypeOnly(schema, "object") || schema.getProperties() != null || schema.getAdditionalProperties() != null) {
            return Set.of("object");
        }
        if (schema.getTypes() != null && schema.getTypes().size() > 1) {
            Set<String> result = new LinkedHashSet<>();
            for (String t : schema.getTypes()) {
                if (SCALARS.contains(t) || "null".equals(t) || "object".equals(t) || "array".equals(t)) {
                    result.add(t);
                }
            }
            return result;
        }
        String single = SpecRules.singleType(schema);
        if (single != null && (SCALARS.contains(single) || "null".equals(single))) {
            return Set.of(single);
        }
        return Set.of("unknown");
    }

    /** {@code type} is exactly the given type (a single-element 3.1 list counts). */
    private static boolean hasTypeOnly(Schema<?> schema, String type) {
        return type.equals(SpecRules.singleType(schema));
    }

    @SuppressWarnings("rawtypes")
    private static boolean hasBinary(OpenAPI openAPI, Schema<?> schema, Set<Schema<?>> active) {
        if (schema == null || !active.add(schema)) {
            return false;
        }
        try {
            if ("binary".equals(schema.getFormat())) {
                return true;
            }
            if (hasBinary(openAPI, resolve(openAPI, schema.getItems()), active)) {
                return true;
            }
            for (List<Schema> list : Arrays.asList(schema.getOneOf(), schema.getAnyOf(), schema.getAllOf())) {
                if (list != null) {
                    for (Schema s : list) {
                        if (hasBinary(openAPI, resolve(openAPI, s), active)) {
                            return true;
                        }
                    }
                }
            }
            if (schema.getProperties() != null) {
                for (Object s : schema.getProperties().values()) {
                    if (hasBinary(openAPI, resolve(openAPI, (Schema) s), active)) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            active.remove(schema);
        }
    }
}
