package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File parameters with {@code httpStack=tower}: a {@code timvw_rust_runtime::Upload} (a file streamed from disk and
 * opened again by a retry, bytes, a reader or a stream) instead of a {@code std::path::PathBuf}. With
 * {@code bonBuilders} the builder's setter takes anything that converts into one, so a caller that passes a
 * {@code PathBuf} keeps compiling: {@code #[builder(into)]} for one file, and a conversion of each item for an array.
 *
 * <p>A union with a file variant (the {@code x-multipart-file-union} parameters of {@link MultipartRules}) holds an
 * {@code Upload} too, and an array of files a {@code Vec<Upload>}: see {@link #markUnions}. So does a file field of a
 * model: see {@link #markModels}.</p>
 */
final class UploadParams {

    static final String PATH = "std::path::PathBuf";
    static final String UPLOAD = "timvw_rust_runtime::Upload";
    /** The attribute in front of the parameter of the API function, for the bon builder. */
    static final String BUILDER_ATTR = "x-rust-builder-attr";

    /** The crate depends on the runtime's {@code fs} feature: {@code Upload::path} and its {@code From<PathBuf>}. */
    static final String RUNTIME_FS = "runtimeFs";
    /**
     * With {@code apiFeatures}: an API feature group whose models (its {@code -types} feature) hold an upload, so that
     * feature depends on the runtime.
     */
    static final String TYPES_NEED_RUNTIME = "typesNeedRuntime";
    /** With {@code apiFeatures}: a model every feature set compiles holds an upload, so the runtime is not optional. */
    static final String RUNTIME_REQUIRED = "runtimeRequired";

    static final String UPLOADS = "Vec<" + UPLOAD + ">";
    /** On a union variant holding a file: it is skipped by serde (an upload has no JSON form). */
    static final String FILE_VARIANT = "x-rust-enum-file";
    /** On a union variant holding one file: its {@code Display} prints the file name. */
    static final String UPLOAD_VARIANT = "x-rust-enum-upload";
    /** On a union variant holding an array of files: its {@code Display} prints the file names. */
    static final String UPLOADS_VARIANT = "x-rust-enum-uploads";
    /**
     * On a union with a file variant: {@code file} and {@code files}, the variants its constructors and
     * {@code From} conversions build (either may be missing).
     */
    static final String FILE_UNION = "x-rust-file-union";

    /** On a model holding a file in a field ({@link #markModels}). */
    static final String UPLOAD_MODEL = "x-rust-upload-model";
    /**
     * A required field holds one upload: {@code models/mod.rs} defines {@code upload_has_no_json}, its
     * {@code deserialize_with}.
     */
    static final String UPLOAD_NO_JSON = "uploadNoJson";
    /** On a field holding a file: its serde attribute. */
    static final String UPLOAD_SERDE = "x-rust-upload-serde";
    /** On a field holding a file: the doc line on its JSON form. */
    static final String UPLOAD_DOC = "x-rust-upload-doc";
    /** On a field holding one file or an array of files: the bon attribute of its setter. */
    static final String UPLOAD_BUILDER = "x-rust-upload-builder";
    /** On a required field holding one file or an array of files: the type of the parameter of {@code new}. */
    static final String UPLOAD_NEW_TYPE = "x-rust-upload-new-type";
    /** On such a field: the expression {@code new} sets it to. */
    static final String UPLOAD_NEW_INIT = "x-rust-upload-new-init";

    private UploadParams() {
    }

    /**
     * Makes the file fields of the models ({@code PathBuf}, in an array, a map or an {@code Option}) hold
     * {@code Upload}s, as the file parameters and the file variants of the unions do: a multipart body schema that is
     * a model too (it is a JSON body as well, or a property refers to it) takes bytes and streams as well as paths, on
     * WebAssembly too. An upload has no JSON form, so serde skips the field: the model's JSON leaves it out, and JSON
     * never sets it (it decodes as {@code None} or empty). A required field holding one upload has no such value: it is
     * left out of the JSON too, and decoding the model from JSON is an error. {@code Upload} implements
     * {@code PartialEq}, {@code Clone} and {@code Debug}, so the models keep their derives. {@code new} takes anything
     * that converts into an upload ({@code PathBuf}, bytes) for a required file, and the bon setter converts too.
     *
     * @return whether any model has a file field
     */
    static boolean markModels(Map<String, ModelsMap> allModels, Map<String, CodegenModel> unions,
            Map<String, Object> additionalProperties) {
        boolean any = false;
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                CodegenModel cm = mm.getModel();
                if (cm == null || cm.isEnum || unions.containsKey(cm.classname)) {
                    continue;
                }
                boolean holds = false;
                boolean noJson = false;
                for (List<CodegenProperty> vars : Arrays.asList(cm.vars, cm.allVars, cm.requiredVars,
                        cm.optionalVars, cm.readOnlyVars, cm.readWriteVars, cm.parentVars, cm.nonNullableVars)) {
                    if (vars == null) {
                        continue;
                    }
                    for (CodegenProperty p : vars) {
                        if (markField(p)) {
                            holds = true;
                            noJson |= p.vendorExtensions.containsKey(UPLOAD_NEW_INIT) && !p.isArray;
                        }
                    }
                }
                if (holds) {
                    cm.vendorExtensions.put(UPLOAD_MODEL, true);
                    if (noJson) {
                        additionalProperties.put(UPLOAD_NO_JSON, true);
                    }
                    any = true;
                }
            }
        }
        return any;
    }

    /** Turns a file field into an upload one; whether it holds a file. */
    private static boolean markField(CodegenProperty p) {
        if (p.dataType == null || !p.dataType.contains(PATH)) {
            return false;
        }
        p.dataType = p.dataType.replace(PATH, UPLOAD);
        if (p.datatypeWithEnum != null) {
            p.datatypeWithEnum = p.datatypeWithEnum.replace(PATH, UPLOAD);
        }
        for (CodegenProperty items = p.items; items != null; items = items.items) {
            if (items.dataType != null) {
                items.dataType = items.dataType.replace(PATH, UPLOAD);
            }
            if (items.datatypeWithEnum != null) {
                items.datatypeWithEnum = items.datatypeWithEnum.replace(PATH, UPLOAD);
            }
        }
        boolean one = UPLOAD.equals(p.dataType);
        boolean many = UPLOADS.equals(p.dataType);
        // A required, non-nullable field holding one upload has no value to decode to: it rejects JSON.
        boolean noDefault = one && p.required && !p.isNullable;
        p.vendorExtensions.put(UPLOAD_SERDE, noDefault
                ? "#[serde(rename = \"" + p.baseName + "\", skip_serializing, "
                        + "deserialize_with = \"crate::models::upload_has_no_json\")]"
                : "#[serde(rename = \"" + p.baseName + "\", skip)]");
        p.vendorExtensions.put(UPLOAD_DOC, noDefault
                ? "An upload has no JSON form: the model's JSON leaves it out, and decoding the model from JSON is an "
                        + "error."
                : "An upload has no JSON form: the model's JSON leaves it out, and JSON never sets it.");
        if (one) {
            p.vendorExtensions.put(UPLOAD_BUILDER, "#[builder(into)]");
        } else if (many) {
            p.vendorExtensions.put(UPLOAD_BUILDER, "#[builder(with = |files: impl IntoIterator<Item = impl Into<"
                    + UPLOAD + ">>| files.into_iter().map(Into::into).collect())]");
        }
        if (p.required && !p.isNullable && (one || many)) {
            p.vendorExtensions.put(UPLOAD_NEW_TYPE, one ? "impl Into<" + UPLOAD + ">"
                    : "impl IntoIterator<Item = impl Into<" + UPLOAD + ">>");
            p.vendorExtensions.put(UPLOAD_NEW_INIT, one ? p.name + ".into()"
                    : p.name + ".into_iter().map(Into::into).collect()");
        }
        return true;
    }

    /**
     * Makes the file variants of the unions ({@code PathBuf}, {@code Vec<PathBuf>}) hold {@code Upload}s, so that a
     * multipart union takes bytes and streams as well as paths, on WebAssembly too. Such a variant is
     * {@code #[serde(skip)]}: an upload has no JSON form, and multipart unions are request-only (they are sent part by
     * part, the file variants through the multipart helper), so serializing one is an error and JSON never decodes to
     * one. {@code Upload} implements {@code PartialEq}, {@code Clone} and {@code Debug}, so the union and the models that
     * hold it keep their derives. The union gets {@code file(..)}/{@code files(..)} constructors taking anything that
     * converts into an upload, and {@code From} an {@code Upload} and a {@code PathBuf} (or their {@code Vec}s).
     *
     * @return whether any union has a file variant
     */
    static boolean markUnions(Map<String, CodegenModel> unions) {
        boolean any = false;
        for (CodegenModel union : unions.values()) {
            List<CodegenProperty> variants = UnionRules.variants(union);
            if (variants == null || union.vendorExtensions.containsKey(UnionRules.DISPATCH)) {
                continue;
            }
            Map<String, Object> constructors = new LinkedHashMap<>();
            for (CodegenProperty v : variants) {
                boolean file = PATH.equals(v.dataType);
                boolean files = ("Vec<" + PATH + ">").equals(v.dataType);
                if (!file && !files) {
                    continue;
                }
                v.dataType = file ? UPLOAD : UPLOADS;
                v.datatypeWithEnum = v.dataType;
                v.vendorExtensions.put(UnionRules.PAYLOAD, v.dataType);
                v.vendorExtensions.put(FILE_VARIANT, true);
                v.vendorExtensions.put(file ? UPLOAD_VARIANT : UPLOADS_VARIANT, true);
                constructors.putIfAbsent(file ? "file" : "files", v.name);
            }
            if (!constructors.isEmpty()) {
                union.vendorExtensions.put(FILE_UNION, constructors);
                // The variant types the model's doc lists.
                for (Set<String> types : List.of(union.oneOf, union.anyOf)) {
                    if (types.remove(PATH)) {
                        types.add(UPLOAD);
                    }
                    if (types.remove("Vec<" + PATH + ">")) {
                        types.add(UPLOADS);
                    }
                }
                any = true;
            }
        }
        return any;
    }

    /** Whether the operation uploads a file: a file parameter, or a multipart union with a file variant. */
    static boolean uploads(CodegenOperation op) {
        for (List<CodegenParameter> params : List.of(op.bodyParams, op.formParams)) {
            if (params != null && params.stream().anyMatch(p -> p.isFile
                    || p.vendorExtensions.containsKey(MultipartRules.FILE_UNION))) {
                return true;
            }
        }
        return false;
    }

    /**
     * With {@code bonBuilders}: the setter of a multipart union with a file variant takes anything that converts into
     * the union ({@code #[builder(into)]}), so it takes an {@code Upload} or a {@code PathBuf} as well as the union.
     */
    static void markUnionSetters(CodegenOperation op) {
        Set<String> unions = new HashSet<>();
        for (CodegenParameter p : op.formParams) {
            if (p.vendorExtensions.containsKey(MultipartRules.FILE_UNION)) {
                unions.add(p.baseName);
            }
        }
        for (List<CodegenParameter> params : List.of(op.allParams, op.formParams, op.requiredParams,
                op.optionalParams)) {
            for (CodegenParameter p : params) {
                if (p.isFormParam && unions.contains(p.baseName)) {
                    p.vendorExtensions.put(BUILDER_ATTR, "#[builder(into)]");
                }
            }
        }
    }

    /** Turns a file parameter ({@code PathBuf} or {@code Vec<PathBuf>}) into an {@code Upload} one. */
    static void mark(CodegenParameter p, boolean bonBuilders) {
        if (!p.isFile || p.dataType == null || !p.dataType.contains(PATH)) {
            return;
        }
        boolean array = p.isArray || p.dataType.startsWith("Vec<");
        p.dataType = p.dataType.replace(PATH, UPLOAD);
        if (p.items != null && p.items.dataType != null) {
            p.items.dataType = p.items.dataType.replace(PATH, UPLOAD);
        }
        if (bonBuilders) {
            p.vendorExtensions.put(BUILDER_ATTR, array
                    ? "#[builder(with = |files: impl IntoIterator<Item = impl Into<" + UPLOAD + ">>| "
                            + "files.into_iter().map(Into::into).collect())]"
                    : "#[builder(into)]");
        }
    }
}
