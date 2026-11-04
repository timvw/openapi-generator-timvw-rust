package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The transitional model rewrites of Milestone 2.4, in the order openai-client-base's {@code generate.sh} ran the
 * scripts they port. They run over the rendered crate after generation ({@code legacyModelRewrites=true}) and keep
 * the client's public API identical while its scripts are removed; Milestone 3 replaces them with model-level rules
 * (tagged unions, {@code Default} only where valid, boxing only on cycles).
 *
 * <p>Each step is checked against the scripted pipeline's output by {@code LegacyOracleTest}.</p>
 */
public final class LegacyModelRewrites {

    record Named(String script, Rewrite rewrite) {
    }

    static List<Named> pipeline() {
        return pipeline(true);
    }

    static List<Named> pipeline(boolean structDisplay) {
        FixGeneratedCode fixGeneratedCode = new FixGeneratedCode(structDisplay);
        FixHelperImplMismatches helperImpls = new FixHelperImplMismatches();
        return Arrays.asList(
                new Named("patch_generated.rs.sh (models)", new PatchGeneratedModels()),
                new Named("fix_generated_code.py", fixGeneratedCode),
                new Named("fix_empty_enums.py", new FixEmptyEnums()),
                new Named("fix_enum_attributes.py", new FixEnumAttributes()),
                new Named("fix_tool_enum_tagging.py", new FixToolEnumTagging()),
                new Named("fix_untagged_unions.py", new FixUntaggedUnions()),
                new Named("fix_helper_impl_mismatches.py", helperImpls),
                new Named("fix_default_issues.py", new FixDefaultIssues()),
                new Named("fix_generated_code.py", fixGeneratedCode),
                new Named("fix_generator_artifacts.py", new FixGeneratorArtifacts()),
                new Named("fix_helper_impl_mismatches.py", helperImpls),
                new Named("fix_generated_code.py", fixGeneratedCode),
                new Named("doc-link fix (perl)", new DocLinkFix()),
                new Named("fix_unused_imports.py", new FixUnusedImports()));
    }

    private LegacyModelRewrites() {
    }

    public static void run(LegacyContext context, Consumer<String> log) throws IOException {
        run(context, true, log);
    }

    /** @param structDisplay whether structs get the JSON {@code Display} the scripts gave them */
    public static void run(LegacyContext context, boolean structDisplay, Consumer<String> log) throws IOException {
        for (Named step : pipeline(structDisplay)) {
            log.accept("legacy rewrite: " + step.script());
            step.rewrite().apply(context);
        }
    }
}
