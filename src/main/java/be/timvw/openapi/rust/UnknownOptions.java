package be.timvw.openapi.rust;

import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.DefaultCodegen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fails generation on an additional property that is not an option. openapi-generator keeps every
 * {@code --additional-properties} key, and every key of a {@code -c} file, in one free-form map; timvw-rust renders only
 * its own templates, so a key nothing reads can only be a mistake (a misspelt option would otherwise change the output
 * silently).
 *
 * <p>A key is known when it is
 * <ul>
 *   <li>an option of the generator (its {@code cliOptions}, which {@code config-help -g timvw-rust} lists),</li>
 *   <li>one of openapi-generator's general options: those of {@link DefaultCodegen} ({@code cliOptions} that
 *       timvw-rust removes from its own) and the other keys {@link DefaultCodegen#processOpts} reads,</li>
 *   <li>a key openapi-generator itself puts in the map ({@link #OPENAPI_GENERATOR_KEYS}),</li>
 *   <li>a key the templates read that is not advertised ({@link #TEMPLATE_KEYS}), or</li>
 *   <li>a key the generator answers with a message of its own (fixed, always-on, rejected and removed options), which
 *       is checked before this one.</li>
 * </ul>
 * Only the generator's own options are suggested as the closest match.
 */
final class UnknownOptions {

    private UnknownOptions() {
    }

    /**
     * The keys openapi-generator puts in {@code additionalProperties} itself, before or around {@code processOpts},
     * taken from runs of the CLI ({@code generate} with and without {@code -c}, with {@code --additional-properties},
     * {@code --git-user-id}, {@code --package-name} and the other flags, {@code --global-property}) and of
     * {@code CodegenConfigurator} (the path the Maven plugin takes as well), openapi-generator 7.27.0:
     */
    static final Set<String> OPENAPI_GENERATOR_KEYS = Set.of(
            // DefaultCodegen's constructor
            "lambda", "openbrace", "closebrace",
            // DefaultGenerator#configureGeneratorProperties, right before processOpts (excludeTests only when both
            // apiTests and modelTests are off)
            CodegenConstants.GENERATE_APIS, CodegenConstants.GENERATE_MODELS,
            CodegenConstants.GENERATE_SUPPORTING_FILES, CodegenConstants.GENERATE_WEBHOOKS,
            CodegenConstants.GENERATE_API_TESTS, CodegenConstants.GENERATE_MODEL_TESTS,
            CodegenConstants.GENERATE_API_DOCS, CodegenConstants.GENERATE_MODEL_DOCS,
            CodegenConstants.GENERATE_RECURSIVE_DEPENDENT_MODELS, CodegenConstants.EXCLUDE_TESTS,
            // GeneratorSettings, from the CLI flags (--api-package ...), a -c file's settings keys or the Maven
            // plugin's parameters; gitHost, gitUserId, gitRepoId and releaseNote are always set (they have defaults)
            CodegenConstants.API_PACKAGE, CodegenConstants.MODEL_PACKAGE, CodegenConstants.INVOKER_PACKAGE,
            CodegenConstants.GROUP_ID, CodegenConstants.ARTIFACT_ID, CodegenConstants.ARTIFACT_VERSION,
            CodegenConstants.API_NAME_SUFFIX, CodegenConstants.MODEL_NAME_PREFIX, CodegenConstants.MODEL_NAME_SUFFIX,
            CodegenConstants.GIT_HOST, CodegenConstants.GIT_USER_ID, CodegenConstants.GIT_REPO_ID,
            CodegenConstants.RELEASE_NOTE, CodegenConstants.HTTP_USER_AGENT,
            // DefaultGenerator, after processOpts, unless already set (so it can be set to pin the README's line)
            "generatorVersion");

    /**
     * The keys {@link DefaultCodegen#processOpts} reads besides its {@code cliOptions} (general options that are only
     * set as additional properties, or through a CLI flag that ends up there).
     */
    static final Set<String> DEFAULT_CODEGEN_KEYS = Set.of(
            CodegenConstants.API_NAME_PREFIX,
            CodegenConstants.REMOVE_OPERATION_ID_PREFIX,
            CodegenConstants.REMOVE_OPERATION_ID_PREFIX_DELIMITER,
            CodegenConstants.REMOVE_OPERATION_ID_PREFIX_COUNT,
            CodegenConstants.SKIP_OPERATION_EXAMPLE,
            CodegenConstants.DOCEXTENSION,
            CodegenConstants.ENABLE_POST_PROCESS_FILE,
            CodegenConstants.GENERATE_ALIAS_AS_MODEL,
            CodegenConstants.REMOVE_ENUM_VALUE_PREFIX,
            CodegenConstants.AUTOSET_CONSTANTS,
            CodegenConstants.DEFAULT_TO_EMPTY_CONTAINER);

    /** Keys {@code Cargo.mustache} reads (as stock {@code -g rust}'s does) that are not advertised options. */
    static final Set<String> TEMPLATE_KEYS = Set.of(
            "publishRustRegistry", "repositoryUrl", "documentationUrl", "homePageUrl");

    /** The {@code cliOptions} of {@link DefaultCodegen}: openapi-generator's general options. */
    private static final Set<String> GENERAL_OPTIONS = names(new DefaultCodegen().cliOptions());

    /**
     * Fails when {@code keys} holds a key that is neither in {@code advertised} nor in {@code handled} nor one of the
     * keys above, naming every such key and, for each, the closest advertised option when it is close enough.
     */
    static void check(String generator, Collection<String> keys, List<CliOption> advertised,
                      Collection<String> handled) {
        Set<String> options = names(advertised);
        Set<String> unknown = new TreeSet<>();
        for (String key : keys) {
            if (!options.contains(key) && !handled.contains(key) && !GENERAL_OPTIONS.contains(key)
                    && !DEFAULT_CODEGEN_KEYS.contains(key) && !OPENAPI_GENERATOR_KEYS.contains(key)
                    && !TEMPLATE_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        List<String> described = new ArrayList<>();
        for (String key : unknown) {
            String closest = closest(key, advertised);
            described.add("'" + key + "'" + (closest == null ? "" : " (did you mean '" + closest + "'?)"));
        }
        boolean one = unknown.size() == 1;
        throw new IllegalArgumentException(String.format(
                "%s: unknown option%s %s; %s (config-help -g %s lists the options)",
                generator, one ? "" : "s", String.join(", ", described),
                one ? "remove it or correct its name" : "remove them or correct their names", generator));
    }

    /**
     * The advertised option closest to {@code key} by edit distance, ignoring case, when it is at most
     * {@code max(2, length / 4)} edits away; the first in {@code advertised} order on a tie, {@code null} when none is
     * that close.
     */
    static String closest(String key, List<CliOption> advertised) {
        String lower = key.toLowerCase(Locale.ROOT);
        int limit = Math.max(2, key.length() / 4);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (CliOption option : advertised) {
            int d = distance(lower, option.getOpt().toLowerCase(Locale.ROOT));
            if (d <= limit && d < bestDistance) {
                best = option.getOpt();
                bestDistance = d;
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static Set<String> names(List<CliOption> options) {
        Set<String> names = new HashSet<>();
        for (CliOption option : options) {
            names.add(option.getOpt());
        }
        return names;
    }
}
