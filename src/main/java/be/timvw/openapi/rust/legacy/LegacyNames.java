package be.timvw.openapi.rust.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faithful ports of the naming helpers in openai-client-base's {@code scripts/utils.py}, plus the few Python string
 * methods they rely on ({@code str.capitalize}, {@code str.lower}, {@code str.split}).
 */
final class LegacyNames {

    private LegacyNames() {
    }

    /** Python {@code str.capitalize()}: first character upper case, all the others lower case. */
    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Python {@code str.lower()}. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Python {@code str.split(sep)}: keeps empty pieces, including leading and trailing ones. */
    static List<String> split(String s, String sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int at;
        while ((at = s.indexOf(sep, start)) >= 0) {
            parts.add(s.substring(start, at));
            start = at + sep.length();
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** Python {@code str.split()} without arguments: splits on runs of whitespace, dropping empty pieces. */
    static List<String> splitWhitespace(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (word.length() > 0) {
                    parts.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (word.length() > 0) {
            parts.add(word.toString());
        }
        return parts;
    }

    /**
     * {@code convert_to_rust_type_name}: turns acronyms (2+ consecutive upper case letters) into Pascal case, e.g.
     * {@code MCPTool -> McpTool}, {@code HTTP -> Http}.
     */
    static String convertToRustTypeName(String name) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int n = name.length();
        while (i < n) {
            if (i < n - 1 && Character.isUpperCase(name.charAt(i)) && Character.isUpperCase(name.charAt(i + 1))) {
                int acronymStart = i;
                while (i < n && Character.isUpperCase(name.charAt(i))) {
                    i++;
                }
                String acronym = name.substring(acronymStart, i);
                if (i < n && Character.isLowerCase(name.charAt(i))) {
                    if (acronym.length() > 1) {
                        result.append(acronym.charAt(0)).append(lower(acronym.substring(1, acronym.length() - 1)));
                        i--;
                    } else {
                        result.append(acronym);
                    }
                } else {
                    result.append(acronym.charAt(0)).append(lower(acronym.substring(1)));
                }
            } else {
                result.append(name.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    /** {@code snake_to_pascal}: {@code ''.join(word.capitalize() for word in name.split('_'))}. */
    static String snakeToPascal(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : split(name, "_")) {
            out.append(capitalize(word));
        }
        return out.toString();
    }

    /** {@code pascal_to_snake}: underscores before capitals that follow lower case letters or digits, and inside capital runs. */
    static String pascalToSnake(String name) {
        String s1 = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        String s2 = s1.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        return lower(s2);
    }

    /** {@code sanitize_variant_name}: drops separators and brackets, then Pascal-cases the {@code _}-separated parts. */
    static String sanitizeVariantName(String name) {
        name = name.replace(" ", "_").replace("-", "_").replace(",", "_").replace(".", "_");
        name = name.replace("(", "").replace(")", "").replace("[", "").replace("]", "");
        StringBuilder out = new StringBuilder();
        for (String part : split(name, "_")) {
            if (!part.isEmpty()) {
                out.append(capitalize(part));
            }
        }
        return out.toString();
    }
}
