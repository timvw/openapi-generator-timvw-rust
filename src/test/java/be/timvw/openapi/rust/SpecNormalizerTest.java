package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecNormalizerTest {

    private static IntegerSchema integerWithFormat(String format) {
        IntegerSchema s = new IntegerSchema();
        s.setFormat(format);
        return s;
    }

    @Test
    void rewritesIntegerWithFloatFormatWhereverItAppears() {
        IntegerSchema property = integerWithFormat("float");
        IntegerSchema item = integerWithFormat("double");
        IntegerSchema parameter = integerWithFormat("float");
        IntegerSchema nested = integerWithFormat("double");

        ObjectSchema model = new ObjectSchema();
        model.addProperty("score", property);
        model.addProperty("scores", new ArraySchema().items(item));
        ObjectSchema wrapper = new ObjectSchema();
        wrapper.addProperty("inner", new ObjectSchema().addProperty("deep", nested));

        OpenAPI openAPI = new OpenAPI()
                .components(new Components().addSchemas("Model", model).addSchemas("Wrapper", wrapper))
                .paths(new Paths().addPathItem("/things", new PathItem().get(new Operation()
                        .addParametersItem(new QueryParameter().name("min").schema(parameter)))));

        List<String> fixed = SpecNormalizer.integerWithFloatFormatToNumber(openAPI);

        assertEquals(Arrays.asList(
                "#/components/schemas/Model/properties/score",
                "#/components/schemas/Model/properties/scores/items",
                "#/components/schemas/Wrapper/properties/inner/properties/deep",
                "#/paths//things/get/parameters/min/schema"), fixed);
        for (Schema<?> s : Arrays.asList(property, item, parameter, nested)) {
            assertEquals("number", s.getType());
        }
        assertEquals("float", property.getFormat());
    }

    @Test
    void leavesValidSchemasAlone() {
        IntegerSchema int32 = integerWithFormat("int32");
        IntegerSchema plain = new IntegerSchema();
        NumberSchema number = new NumberSchema();
        number.setFormat("float");
        OpenAPI openAPI = new OpenAPI().components(new Components()
                .addSchemas("A", int32).addSchemas("B", plain).addSchemas("C", number));

        assertTrue(SpecNormalizer.integerWithFloatFormatToNumber(openAPI).isEmpty());
        assertEquals("integer", int32.getType());
        assertEquals("integer", plain.getType());
        assertEquals("number", number.getType());
    }

    @Test
    void toleratesCyclesAndEmptyDocuments() {
        ObjectSchema node = new ObjectSchema();
        node.addProperty("next", node);
        OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Node", node));
        assertTrue(SpecNormalizer.integerWithFloatFormatToNumber(openAPI).isEmpty());
        assertTrue(SpecNormalizer.integerWithFloatFormatToNumber(new OpenAPI()).isEmpty());
    }
}
