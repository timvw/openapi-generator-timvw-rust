package org.openapitools.codegen;

import io.swagger.v3.oas.models.OpenAPI;

import java.util.Map;

/**
 * Runs openapi-generator's {@link InlineModelResolver} from outside its package. {@code flatten} is package-private,
 * and {@code be.timvw.openapi.rust.TimvwRustCodegen} needs to call it after adjusting the spec (see its
 * {@code titleNaming} option), so this class lives in the generator's package on the same classpath.
 */
public final class InlineModelResolverAccess {

    private InlineModelResolverAccess() {
    }

    /** Resolves inline schemas into components, as the generator itself does before {@code preprocessOpenAPI}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void flatten(OpenAPI openAPI, Map<String, String> inlineSchemaNameMapping, Map inlineSchemaOptions) {
        InlineModelResolver resolver = new InlineModelResolver();
        resolver.setInlineSchemaNameMapping(inlineSchemaNameMapping);
        resolver.setInlineSchemaOptions(inlineSchemaOptions);
        resolver.flatten(openAPI);
    }
}
