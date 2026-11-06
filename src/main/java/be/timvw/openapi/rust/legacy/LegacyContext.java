package be.timvw.openapi.rust.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the ported scripts read besides the rendered files.
 *
 * <ul>
 *   <li>{@code root}: the crate root (the generator's output folder); files live under {@code root/src}.</li>
 *   <li>{@code rawSpec}: the input spec as parsed data (the scripts' {@code stainless.yaml}), maps in document
 *       order, as Python's {@code yaml.safe_load} gives it.</li>
 *   <li>{@code preprocessedSpec}: the spec after the generator's spec rules (the scripts' {@code SPEC_OUT},
 *       {@code target/specs/l2_rust_compatible.json}).</li>
 *   <li>{@code boxPolicy}: which model payloads a rewrite that writes them boxes ({@code boxOnlyCycles}).</li>
 *   <li>{@code defaultOnlyWhereValid}: the generator decides {@code Default} from the required fields, so the
 *       rewrites that infer it from the rendered text stay out and the ones that write an enum give it no
 *       {@code Default} impl.</li>
 *   <li>{@code integersAsI64}: integers a rewrite writes are {@code i64} unless {@code format: int32}.</li>
 * </ul>
 */
public record LegacyContext(Path root, Map<String, Object> rawSpec, Map<String, Object> preprocessedSpec,
                            BoxPolicy boxPolicy, boolean defaultOnlyWhereValid, boolean integersAsI64) {

    /** A context in which every model payload a rewrite writes is boxed, as in the scripts. */
    public LegacyContext(Path root, Map<String, Object> rawSpec, Map<String, Object> preprocessedSpec) {
        this(root, rawSpec, preprocessedSpec, BoxPolicy.ALWAYS);
    }

    /** A context in which the rewrites keep the scripts' {@code Default} handling. */
    public LegacyContext(Path root, Map<String, Object> rawSpec, Map<String, Object> preprocessedSpec,
                         BoxPolicy boxPolicy) {
        this(root, rawSpec, preprocessedSpec, boxPolicy, false);
    }

    /** A context in which every integer a rewrite writes is {@code i32}, as in the scripts. */
    public LegacyContext(Path root, Map<String, Object> rawSpec, Map<String, Object> preprocessedSpec,
                         BoxPolicy boxPolicy, boolean defaultOnlyWhereValid) {
        this(root, rawSpec, preprocessedSpec, boxPolicy, defaultOnlyWhereValid, false);
    }

    public Path src() {
        return root.resolve("src");
    }

    public Path models() {
        return root.resolve("src/models");
    }

    public Path apis() {
        return root.resolve("src/apis");
    }

    /** Parses a JSON or YAML document into nested {@code LinkedHashMap}/{@code ArrayList} values. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(Path file) throws IOException {
        String name = file.getFileName().toString();
        ObjectMapper mapper = name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(yamlFactory());
        Object value = mapper.readValue(file.toFile(), Object.class);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** Parses a JSON document held in memory, as {@link #load(Path)} does for a file. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJson(String json) throws IOException {
        Object value = new ObjectMapper().readValue(json, Object.class);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** A YAML factory without SnakeYAML's 3 MB default document limit (the stainless spec is larger). */
    private static YAMLFactory yamlFactory() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        return YAMLFactory.builder().loaderOptions(options).build();
    }
}
