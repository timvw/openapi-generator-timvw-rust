package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code serverWebhooks} (docs/design/server-webhooks.md): the webhooks of the spec the server sends, as the variants
 * of {@code server::webhooks::Webhook}: a variant per webhook whose request body is a JSON model, named after the
 * webhook, holding that model.
 */
final class ServerWebhooks {

    private ServerWebhooks() {
    }

    /**
     * The webhooks, in the spec's order, as {@code name}, {@code variant}, {@code type}, {@code doc} and, with API
     * features, the {@code cfg} of their model.
     *
     * @param typeOf   the Rust type of a schema, as the models name it ({@code models::<Model>})
     * @param modelCfg the cfg of a schema's model by its name, or {@code null} without API features
     */
    static List<Map<String, Object>> plan(OpenAPI openAPI, Function<Schema<?>, String> typeOf,
            Function<String, String> modelCfg, Consumer<String> warn) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (openAPI.getWebhooks() == null) {
            return out;
        }
        Set<String> variants = new HashSet<>();
        for (Map.Entry<String, PathItem> e : openAPI.getWebhooks().entrySet()) {
            String name = e.getKey();
            PathItem item = e.getValue();
            Operation op = item == null ? null : item.getPost();
            if (op == null) {
                warn.accept("the webhook '" + name + "' is not a POST: it is not sent");
                continue;
            }
            RequestBody body = op.getRequestBody() == null ? null
                    : ModelUtils.getReferencedRequestBody(openAPI, op.getRequestBody());
            Schema<?> schema = null;
            if (body != null && body.getContent() != null) {
                for (Map.Entry<String, MediaType> c : body.getContent().entrySet()) {
                    if (ServerRules.isJson(c.getKey()) && c.getValue() != null) {
                        schema = c.getValue().getSchema();
                        break;
                    }
                }
            }
            if (schema == null || schema.get$ref() == null) {
                warn.accept("the webhook '" + name + "' has no JSON body of a schema of components: it is not sent");
                continue;
            }
            String type = typeOf.apply(schema);
            if (type == null || !type.startsWith("models::")) {
                type = "models::" + type;
            }
            String variant = ServerAuth.variant(name);
            while (!variants.add(variant)) {
                variant = variant + "_";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ServerRules.literal(name));
            m.put("variant", variant);
            m.put("type", type);
            String doc = op.getSummary() != null ? op.getSummary() : op.getDescription();
            m.put("doc", ServerRules.docLine(doc));
            if (modelCfg != null) {
                String cfg = modelCfg.apply(ModelUtils.getSimpleRef(schema.get$ref()));
                if (cfg != null) {
                    m.put("cfg", cfg);
                }
            }
            out.add(m);
        }
        return out;
    }
}
