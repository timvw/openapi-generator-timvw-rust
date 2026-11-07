package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.util.Map;

/**
 * Decides which structs derive {@code Default} (Milestone 3.2, {@code defaultOnlyWhereValid}).
 *
 * <p>A {@code Default} value must be one the API would accept. A struct qualifies only when it has no required field;
 * a required nullable field is required too, since leaving it out is not the same as sending {@code null}. The types of
 * the optional fields do not matter: they are all {@code Option<T>}, which is {@code Default} whatever {@code T} is.
 * No enum has a {@code Default}, as no variant is the API's default; the templates read the option itself for that.</p>
 *
 * <p>The decision is stored per model as the vendor extension {@value #DERIVE_DEFAULT}, which the model template
 * reads. It looks at the model's own fields only, never at type names, so it cannot collide across modules the way the
 * text-based analysis of the removed legacy rewrites did. Without the option every model gets {@code true}, which is the
 * stock rendering.</p>
 */
final class DefaultRules {

    /** Vendor extension on a {@link CodegenModel}: the struct derives {@code Default}. */
    static final String DERIVE_DEFAULT = "x-rust-derive-default";

    private DefaultRules() {
    }

    /** Marks every model with {@value #DERIVE_DEFAULT}. */
    static void mark(Map<String, ModelsMap> allModels, boolean defaultOnlyWhereValid) {
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                CodegenModel cm = mm.getModel();
                cm.vendorExtensions.put(DERIVE_DEFAULT, !defaultOnlyWhereValid || !hasRequiredField(cm));
            }
        }
    }

    /** Whether the rendered struct has a required field: the template prints {@code vars} and their flag. */
    static boolean hasRequiredField(CodegenModel cm) {
        return cm.vars.stream().anyMatch(v -> v.required);
    }
}
