package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;

import java.util.List;

/**
 * File parameters with {@code httpStack=tower}: a {@code timvw_rust_runtime::Upload} (a file streamed from disk and
 * opened again by a retry, bytes, a reader or a stream) instead of a {@code std::path::PathBuf}. With
 * {@code bonBuilders} the builder's setter takes anything that converts into one, so a caller that passes a
 * {@code PathBuf} keeps compiling: {@code #[builder(into)]} for one file, and a conversion of each item for an array.
 *
 * <p>Models keep {@code PathBuf}: a union with a file variant (the {@code x-multipart-file-union} parameters of
 * {@link MultipartRules}) converts its path when the part is added.</p>
 */
final class UploadParams {

    static final String PATH = "std::path::PathBuf";
    static final String UPLOAD = "timvw_rust_runtime::Upload";
    /** The attribute in front of the parameter of the API function, for the bon builder. */
    static final String BUILDER_ATTR = "x-rust-builder-attr";

    /** The crate depends on the runtime's {@code fs} feature: {@code Upload::path} and its {@code From<PathBuf>}. */
    static final String RUNTIME_FS = "runtimeFs";

    private UploadParams() {
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
