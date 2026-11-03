package be.timvw.openapi.rust.legacy;

import java.util.regex.Matcher;

import java.util.Locale;
import java.util.regex.Pattern;

/** Port of the helpers in openai-client-base {@code scripts/utils.py} that the ported rewrites use. */
final class Utils {

    private static final Pattern LOWER_THEN_UPPER = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern CAPITALS_THEN_WORD = Pattern.compile("([A-Z]+)([A-Z][a-z])");

    private Utils() {
    }

    /** {@code convert_to_rust_type_name}: turns acronyms into PascalCase ({@code MCPTool} becomes {@code McpTool}). */
    static String convertToRustTypeName(String name) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int n = name.length();
        while (i < n) {
            if (i < n - 1 && isUpper(name.charAt(i)) && isUpper(name.charAt(i + 1))) {
                int acronymStart = i;
                while (i < n && isUpper(name.charAt(i))) {
                    i++;
                }
                String acronym = name.substring(acronymStart, i);
                if (i < n && isLower(name.charAt(i))) {
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
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("_", -1)) {
            sb.append(capitalize(word));
        }
        return sb.toString();
    }

    /** {@code pascal_to_snake}. */
    static String pascalToSnake(String name) {
        String s1 = LOWER_THEN_UPPER.matcher(name).replaceAll("$1_$2");
        String s2 = CAPITALS_THEN_WORD.matcher(s1).replaceAll("$1_$2");
        return lower(s2);
    }

    /** Python {@code str.capitalize()}: first character upper-cased, the rest lower-cased. */
    static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + lower(word.substring(1));
    }

    /** Python {@code str.lower()}. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Python {@code str.isupper()} on one character. */
    static boolean isUpper(char c) {
        return Character.isUpperCase(c);
    }

    /** Python {@code str.islower()} on one character. */
    static boolean isLower(char c) {
        return Character.isLowerCase(c);
    }

    /**
     * Expands a string passed as {@code re.sub} replacement the way Python does: backslash escapes are processed.
     * Only the escapes that can appear in literal text are supported; anything else (group references, unknown
     * escapes) fails fast, as it would be a template the scripts did not mean.
     */
    static String pythonReplacementTemplate(String repl) {
        if (repl.indexOf('\\') < 0) {
            return repl;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repl.length(); i++) {
            char c = repl.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char next = i + 1 < repl.length() ? repl.charAt(i + 1) : '\0';
            switch (next) {
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                default -> {
                    if (next == '\0' || Character.isLetterOrDigit(next)) {
                        throw new IllegalArgumentException("unsupported escape in re.sub template: \\" + next);
                    }
                    // Python leaves unknown escapes of non-alphanumerics alone
                    sb.append('\\').append(next);
                }
            }
            i++;
        }
        return sb.toString();
    }

    /** The index of the brace that closes the one at {@code open}, or -1. */
    static int closingBrace(String content, int open) {
        int depth = 0;
        for (int i = open; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Removes every block that starts with a match of {@code header} (ending in its opening brace) and runs to the
     * matching closing brace; with {@code trailingWhitespace} the whitespace after the block goes too. A header
     * without a closing brace is left alone.
     */
    static String removeBlocks(String content, Pattern header, boolean trailingWhitespace) {
        StringBuilder out = new StringBuilder();
        Matcher m = header.matcher(content);
        int from = 0;
        while (from <= content.length() && m.find(from)) {
            int end = closingBrace(content, m.end() - 1);
            if (end < 0) {
                break;
            }
            out.append(content, from, m.start());
            from = end + 1;
            if (trailingWhitespace) {
                while (from < content.length() && Character.isWhitespace(content.charAt(from))) {
                    from++;
                }
            }
        }
        return out.append(content.substring(Math.min(from, content.length()))).toString();
    }
}
