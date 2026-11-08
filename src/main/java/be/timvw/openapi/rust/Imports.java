package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;

import java.util.List;
import java.util.Map;

/**
 * Decides which imports a generated file needs, so that no file imports what it does not use. The stock templates
 * import {@code crate::models}, {@code ContentType}, {@code de::Error as _} and the file stream types into every file
 * and rely on {@code #![allow(unused_imports)]}; the templates print these imports only where the flags set here say
 * so. This replaced the text pruning of {@code fix_unused_imports.py} (formerly part of {@code legacyModelRewrites}).
 *
 * <ul>
 *   <li>{@value #USES_MODELS} on a model: its file refers to another model through {@code models::}, so it needs
 *       {@code use crate::models;}. With {@code modelUnions} off the stock union and discriminator renderings are used
 *       and every model keeps the import, as before.</li>
 *   <li>{@value #CONTENT_TYPE} on an API module: an operation decodes a response body by its content type
 *       ({@code ContentType}).</li>
 *   <li>{@value #SERDE_ERROR} on an API module: an operation builds a {@code serde_json::Error::custom} (response
 *       decoding, or an event stream that got another content type), which needs {@code de::Error as _}.</li>
 *   <li>{@value #FILE_STREAM} on an API module: an operation streams a file from disk ({@code TokioFile},
 *       {@code FramedRead}): a file request body, or a multipart file part when {@code multipartHelper} is off.</li>
 * </ul>
 */
final class Imports {

    /** On a model: the file needs {@code use crate::models;}. */
    static final String USES_MODELS = "x-rust-uses-models";
    /** On an API bundle: import {@code ContentType}. */
    static final String CONTENT_TYPE = "x-rust-import-content-type";
    /** On an API bundle: import {@code serde::de::Error as _}. */
    static final String SERDE_ERROR = "x-rust-import-serde-error";
    /** On an API bundle: import {@code TokioFile}, {@code BytesCodec} and {@code FramedRead}. */
    static final String FILE_STREAM = "x-rust-import-file-stream";

    private static final String MODELS = "models::";

    private Imports() {
    }

    /** Marks every model that refers to another model with {@value #USES_MODELS}. */
    static void markModels(Map<String, ModelsMap> allModels, boolean modelUnions) {
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                CodegenModel cm = mm.getModel();
                if (usesModels(cm, modelUnions)) {
                    cm.vendorExtensions.put(USES_MODELS, true);
                } else {
                    cm.vendorExtensions.remove(USES_MODELS);
                }
            }
        }
    }

    /** Whether the rendered model mentions {@code models::}, as the model template prints it. */
    static boolean usesModels(CodegenModel cm, boolean modelUnions) {
        if (!modelUnions) {
            return true;
        }
        if (cm.vendorExtensions.containsKey(UnionRules.DISPATCH)) {
            // The generated Deserialize calls models::union_dispatch.
            return true;
        }
        if (UnionRules.isUnion(cm)) {
            Object variants = cm.vendorExtensions.get(UnionRules.VARIANTS);
            if (variants instanceof List<?> list) {
                for (Object v : list) {
                    if (v instanceof CodegenProperty p && String.valueOf(p.vendorExtensions.get(UnionRules.PAYLOAD))
                            .contains(MODELS)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (cm.isEnum) {
            return false;
        }
        if (cm.discriminator != null && cm.discriminator.getMappedModels() != null
                && !cm.discriminator.getMappedModels().isEmpty()) {
            // The stock internally tagged enum names each mapped model as models::X.
            return true;
        }
        for (CodegenProperty v : cm.vars) {
            // A property enum is printed by its local enum name, everything else by its data type.
            if (!v.isEnum && v.dataType != null && v.dataType.contains(MODELS)) {
                return true;
            }
        }
        return false;
    }

    /** Sets the import flags of one API module from its operations. */
    static void markApi(OperationsMap objs, boolean multipartHelper, boolean multipleResponses) {
        boolean contentType = false;
        boolean serdeError = false;
        boolean fileStream = false;
        if (objs.getOperations() != null && objs.getOperations().getOperation() != null) {
            for (CodegenOperation op : objs.getOperations().getOperation()) {
                boolean stream = op.vendorExtensions.containsKey(TimvwRustCodegen.EVENT_STREAM);
                boolean decodes = !stream && !multipleResponses && op.returnType != null && !op.isResponseFile;
                contentType |= decodes;
                serdeError |= decodes || stream;
                fileStream |= hasFile(op.bodyParams) || (!multipartHelper && op.isMultipart && hasFile(op.formParams));
            }
        }
        put(objs, CONTENT_TYPE, contentType);
        put(objs, SERDE_ERROR, serdeError);
        put(objs, FILE_STREAM, fileStream);
    }

    private static boolean hasFile(List<CodegenParameter> params) {
        return params != null && params.stream().anyMatch(p -> p.isFile);
    }

    private static void put(OperationsMap objs, String key, boolean value) {
        if (value) {
            objs.put(key, true);
        } else {
            objs.remove(key);
        }
    }
}
