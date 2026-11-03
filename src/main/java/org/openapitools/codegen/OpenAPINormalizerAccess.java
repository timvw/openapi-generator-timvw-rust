package org.openapitools.codegen;

import io.swagger.v3.oas.models.OpenAPI;

import java.util.Map;

/**
 * Same-package access to {@link OpenAPINormalizer#normalize()}, which is protected. The timvw-rust generator runs the
 * normalizer itself, after its spec rules, instead of letting {@link DefaultGenerator} run it first.
 */
public final class OpenAPINormalizerAccess {

    private OpenAPINormalizerAccess() {
    }

    public static void normalize(OpenAPI openAPI, Map<String, String> rules) {
        OpenAPINormalizer.createNormalizer(openAPI, rules).normalize();
    }
}
