package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The types the generated docs ({@code docs/*.md}) show, and where they link. A type links to the doc of the one
 * model it names ({@code Vec<models::Pet>} to {@code Pet.md}); a type that names no model of the crate
 * ({@code String}, {@code Vec<String>}, {@code std::path::PathBuf}, {@code serde_json::Value}) or several
 * ({@code HashMap<String, Vec<models::A>>} names one, a tuple of two would not) is shown without a link. Stock
 * linked the {@code complexType}, which is the container ({@code Vec.md}), the primitive ({@code String.md}) or a
 * schema name that is not the model's file ({@code admin_api_keys_delete_200_response.md}).
 *
 * <ul>
 *   <li>{@value #DOC_TYPE} on a model property: the type the model doc shows, as rendered.</li>
 *   <li>{@value #DOC_LINK} on a model property or a parameter: the file the type links to; absent for no link.</li>
 *   <li>{@value #RETURN_LINK} on an operation: the file the return type links to; absent for no link.</li>
 * </ul>
 */
final class DocLinks {

    static final String DOC_TYPE = "x-doc-type";
    static final String DOC_LINK = "x-doc-link";
    static final String RETURN_LINK = "x-doc-return-link";

    private static final Pattern MODEL = Pattern.compile("models::(\\w+)");

    private DocLinks() {
    }

    /** The classnames of the models that get a doc: every model but an alias, which the base generator skips. */
    static Set<String> documented(Collection<ModelMap> models) {
        Set<String> names = new HashSet<>();
        for (ModelMap mm : models) {
            CodegenModel cm = mm.getModel();
            if (cm != null && !cm.isAlias) {
                names.add(cm.classname);
            }
        }
        return names;
    }

    static Set<String> documented(Map<String, ModelsMap> allModels) {
        Set<String> names = new HashSet<>();
        for (ModelsMap models : allModels.values()) {
            names.addAll(documented(models.getModels()));
        }
        return names;
    }

    /** The doc a Rust type links to: that of the one model it names, if that model has a doc; else {@code null}. */
    static String target(String rustType, Set<String> documented) {
        if (rustType == null) {
            return null;
        }
        Set<String> named = new LinkedHashSet<>();
        Matcher m = MODEL.matcher(rustType);
        while (m.find()) {
            named.add(m.group(1));
        }
        if (named.isEmpty() && documented.contains(rustType)) {
            named.add(rustType);
        }
        if (named.size() != 1) {
            return null;
        }
        String name = named.iterator().next();
        return documented.contains(name) ? name + ".md" : null;
    }

    /**
     * Sets the documented type and link of every property of every model, once the types are final.
     *
     * @return the models that get a doc
     */
    static Set<String> markModels(Map<String, ModelsMap> allModels) {
        Set<String> documented = documented(allModels);
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                for (CodegenProperty cp : mm.getModel().vars) {
                    markProperty(cp, documented);
                }
            }
        }
        return documented;
    }

    private static void markProperty(CodegenProperty cp, Set<String> documented) {
        String docType;
        if (cp.isEnum) {
            // an inline enum is no model of its own: no link
            docType = RustCodegenBase.inlineEnumType(cp).replace("std::collections::HashSet<", "HashSet<");
            cp.vendorExtensions.put(DOC_TYPE, docType);
            cp.vendorExtensions.remove(DOC_LINK);
            return;
        }
        docType = cp.datatypeWithEnum != null && !cp.datatypeWithEnum.isEmpty() ? cp.datatypeWithEnum : cp.dataType;
        if (Boolean.TRUE.equals(cp.getUniqueItems()) && docType.startsWith("Vec<")) {
            docType = docType.replace("Vec<", "HashSet<");
        }
        cp.vendorExtensions.put(DOC_TYPE, docType);
        put(cp.vendorExtensions, DOC_LINK, target(docType, documented));
    }

    /** Sets the link of every parameter and of the return type of the operations. */
    static void markOperations(List<CodegenOperation> operations, Set<String> documented) {
        for (CodegenOperation op : operations) {
            for (CodegenParameter p : op.allParams) {
                put(p.vendorExtensions, DOC_LINK, target(p.dataType, documented));
            }
            put(op.vendorExtensions, RETURN_LINK, target(op.returnType, documented));
        }
    }

    private static void put(Map<String, Object> extensions, String key, String value) {
        if (value == null) {
            extensions.remove(key);
        } else {
            extensions.put(key, value);
        }
    }
}
