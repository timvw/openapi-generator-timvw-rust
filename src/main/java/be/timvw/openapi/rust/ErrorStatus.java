package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the variant of an operation's error enum by the response status (decision 5 in docs/decisions.md).
 *
 * <p>The api template matches {@code status.as_u16()}: an exact code ({@code 404}) first, then a range ({@code 4XX}
 * as the guard {@code code if (400..500).contains(&code)}: a range pattern after a code inside it is an overlap
 * clippy rejects), and decodes the body as that response's type; any other status decodes as the {@code default}
 * response's type, if the operation has one. A body that does not decode as the chosen type (or a
 * status no response lists, without {@code default}) gives {@code UnknownValue} when it is JSON. A response without a
 * body has a variant without a value ({@code Status409()}), which its status gives whatever the body. A
 * {@code String} response typed only as text ({@code text/plain}) holds the body as it is
 * ({@code Status503(content.clone())}): the body is the string, not JSON.</p>
 *
 * <p>Marks each method with what the template needs: the match arms in order ({@link #ARMS}, each a {@code pattern},
 * the {@code variant}, whether it {@code decodes} as JSON or is {@code text}), and the {@code default} variant ({@link #DEFAULT}). Every map
 * names the error enum ({@code enum}), as a method that is not the operation's own returns its operation's enum.</p>
 */
final class ErrorStatus {

    /** The match arms of an operation's error statuses: exact codes first, then ranges. */
    static final String ARMS = "x-rust-error-arms";
    /** The {@code default} response's variant; absent when the operation has none. */
    static final String DEFAULT = "x-rust-error-default";
    /** Set when there is at least one arm, so the template renders a {@code match}. */
    static final String MATCH = "x-rust-error-match";

    private ErrorStatus() {
    }

    /**
     * Marks {@code method} with the arms of {@code owner}'s error responses ({@code owner} is the method itself, or the
     * operation whose error enum a content-type method shares).
     */
    static void mark(CodegenOperation method, CodegenOperation owner, String errorEnum) {
        List<Map<String, Object>> exact = new ArrayList<>();
        List<Map<String, Object>> ranges = new ArrayList<>();
        Map<String, Object> fallback = null;
        if (owner.responses != null) {
            for (CodegenResponse r : owner.responses) {
                if (r.isDefault) {
                    fallback = variant(errorEnum, "DefaultResponse", r);
                } else if (r.is4xx || r.is5xx) {
                    Map<String, Object> arm = variant(errorEnum, "Status" + r.code, r);
                    if (r.isRange()) {
                        int first = (r.code.charAt(0) - '0') * 100;
                        arm.put("pattern", "code if (" + first + ".." + (first + 100) + ").contains(&code)");
                        ranges.add(arm);
                    } else {
                        arm.put("pattern", r.code);
                        exact.add(arm);
                    }
                }
            }
        }
        List<Map<String, Object>> arms = new ArrayList<>(exact);
        arms.addAll(ranges);
        method.vendorExtensions.put(ARMS, arms);
        if (!arms.isEmpty()) {
            method.vendorExtensions.put(MATCH, true);
        } else {
            method.vendorExtensions.remove(MATCH);
        }
        if (fallback != null) {
            method.vendorExtensions.put(DEFAULT, fallback);
        } else {
            method.vendorExtensions.remove(DEFAULT);
        }
    }

    private static Map<String, Object> variant(String errorEnum, String name, CodegenResponse r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("enum", errorEnum);
        v.put("variant", name);
        boolean text = isText(r);
        v.put("text", text);
        v.put("decodes", r.dataType != null && !text);
        return v;
    }

    /**
     * A {@code String} response whose media types are text and none JSON ({@code text/plain}): its body is the string
     * itself, not a JSON string.
     */
    static boolean isText(CodegenResponse r) {
        if (!"String".equals(r.dataType) || r.getContent() == null || r.getContent().isEmpty()) {
            return false;
        }
        boolean text = false;
        for (String mediaType : r.getContent().keySet()) {
            String m = mediaType.toLowerCase(java.util.Locale.ROOT);
            if (m.contains("json")) {
                return false;
            }
            text |= m.startsWith("text/");
        }
        return text;
    }
}
