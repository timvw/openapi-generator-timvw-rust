package be.timvw.openapi.rust;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code targets}: what the generator renders into the crate, a list from {@code models}, {@code client} and
 * {@code server} (see docs/design/targets-and-server.md and docs/guides/server.md). {@code client} and {@code server}
 * imply {@code models}; the default is {@code ["client"]}. There is no {@code all}: a target added later would change
 * what an existing configuration renders.
 *
 * <p>Every option belongs to the models (any target), to the client or to the server ({@link #OPTION_TARGETS}). An
 * option of a target that is not in {@code targets} fails generation, naming the option and its target.</p>
 */
record Targets(boolean client, boolean server) {

    static final String MODELS = "models";
    static final String CLIENT = "client";
    static final String SERVER = "server";

    /** The default: the client and its models, what the generator rendered before the option existed. */
    static final Targets DEFAULT = new Targets(true, false);

    /**
     * The options that belong to one target, by option name. An option not listed belongs to the models (every
     * target): the model options, {@code apiFeatures}, the crate options and the debugging files.
     */
    static final Map<String, String> OPTION_TARGETS = optionTargets();

    private static Map<String, String> optionTargets() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String option : List.of(
                TimvwRustCodegen.RUNTIME_VERSION,
                TimvwRustCodegen.RUNTIME_PATH,
                RustCodegenBase.REQWEST_DEFAULT_FEATURES,
                TimvwRustCodegen.RESPONSE_FUNCTIONS,
                RustCodegenBase.SUPPORT_MULTIPLE_RESPONSES,
                RustCodegenBase.USE_SERDE_PATH_TO_ERROR,
                TimvwRustCodegen.CONTENT_TYPE_METHODS,
                TimvwRustCodegen.TYPED_EVENT_STREAMS,
                TimvwRustCodegen.TYPED_ENUM_PARAMETERS,
                TimvwRustCodegen.DEEP_OBJECT_QUERY_PARAMETERS,
                TimvwRustCodegen.RESET_QUERY_PARAMETER_STYLE,
                TimvwRustCodegen.MULTIPART_HELPER,
                TimvwRustCodegen.USER_AGENT_FROM_PACKAGE,
                TimvwRustCodegen.API_LINT_ALLOWS,
                // The webhooks are client functions (the client receives and verifies them); a server sending them
                // is later work. webhookModule itself belongs to the models: it names the API group, and so the
                // Cargo feature, of the webhook payloads, which a server with modelsCrate must name the same way.
                TimvwRustCodegen.WEBHOOK_MODULE_REEXPORT)) {
            m.put(option, CLIENT);
        }
        m.put(TimvwRustCodegen.SERVER_FRAMEWORK, SERVER);
        m.put(TimvwRustCodegen.SERVER_VALIDATION, SERVER);
        m.put(TimvwRustCodegen.SERVER_DEFAULT_METHODS, SERVER);
        m.put(TimvwRustCodegen.MODELS_CRATE, SERVER);
        m.put(TimvwRustCodegen.MODELS_CRATE_VERSION, SERVER);
        m.put(TimvwRustCodegen.MODELS_CRATE_PATH, SERVER);
        return m;
    }

    /** More than one target: the crate gets the Cargo features {@code client} and {@code server}. */
    boolean features() {
        return client && server;
    }

    /** The targets as the option spells them, models first. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        if (!client && !server) {
            names.add(MODELS);
        }
        if (client) {
            names.add(CLIENT);
        }
        if (server) {
            names.add(SERVER);
        }
        return names;
    }

    /**
     * Reads the option: a list (a JSON or YAML array in a config file) or a string of entries separated by {@code |},
     * {@code ;}, {@code ,} or whitespace; {@code null} is the default.
     */
    static Targets parse(Object value) {
        if (value == null) {
            return DEFAULT;
        }
        List<String> entries = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object o : list) {
                entries.add(String.valueOf(o).trim());
            }
        } else {
            for (String s : String.valueOf(value).trim().split("[|;,\\s]+")) {
                if (!s.isEmpty()) {
                    entries.add(s);
                }
            }
        }
        if (entries.isEmpty()) {
            throw invalid(value, "it is empty");
        }
        boolean client = false;
        boolean server = false;
        for (String entry : entries) {
            switch (entry.toLowerCase(Locale.ROOT)) {
                case MODELS -> { }
                case CLIENT -> client = true;
                case SERVER -> server = true;
                case "all" -> throw invalid(value, "there is no 'all': list the targets, so that a target added "
                        + "later does not change what this configuration renders");
                default -> throw invalid(value, "'" + entry + "' is not a target");
            }
        }
        return new Targets(client, server);
    }

    private static IllegalArgumentException invalid(Object value, String why) {
        return new IllegalArgumentException(String.format("%s: option '%s' is invalid (it was set to '%s'): %s; use "
                        + "a list from 'models', 'client' and 'server', e.g. [\"client\", \"server\"]",
                TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.TARGETS, value, why));
    }

    /**
     * Fails on the first option of {@code keys} (the options that are set) whose target is not one of these targets.
     */
    void check(Collection<String> keys) {
        for (String key : keys) {
            String target = OPTION_TARGETS.get(key);
            if (target == null) {
                continue;
            }
            boolean on = CLIENT.equals(target) ? client : server;
            if (!on) {
                throw new IllegalArgumentException(String.format("%s: option '%s' applies to the %s, and '%s' is not "
                                + "in %s %s; remove the option or add '%s' to %s", TimvwRustCodegen.GENERATOR_NAME,
                        key, target, target, TimvwRustCodegen.TARGETS, names(), target, TimvwRustCodegen.TARGETS));
            }
        }
    }
}
