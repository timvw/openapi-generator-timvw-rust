package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreeFormUnionTest {

    @Test
    void aFreeFormObjectReferenceIsAMap() {
        Map<String, Object> schemas = Map.of(
                "JsonObject", Map.of("type", "object", "additionalProperties", true),
                "Thing", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"))),
                "Counts", Map.of("type", "object", "additionalProperties", Map.of("type", "integer")));
        List<Object> items = List.of(
                Map.of("$ref", "#/components/schemas/JsonObject"),
                Map.of("type", "array", "items", Map.of("$ref", "#/components/schemas/JsonObject")),
                Map.of("$ref", "#/components/schemas/Thing"),
                Map.of("$ref", "#/components/schemas/Counts"));
        FixUntaggedUnions.Analysis a = FixUntaggedUnions.analyzeUnionVariants(items, schemas, "Data");
        assertEquals(List.of(FixUntaggedUnions.FREE_FORM_MAP, "Vec<" + FixUntaggedUnions.FREE_FORM_MAP + ">",
                "models::Thing", "models::Counts"), a.variants().stream().map(FixUntaggedUnions.Variant::type).toList());
    }
}
