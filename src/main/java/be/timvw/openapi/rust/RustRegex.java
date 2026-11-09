package be.timvw.openapi.rust;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a schema {@code pattern} (ECMA-262) is checked with the Rust {@code regex} crate. It accepts only
 * the constructs both engines read the same way and the crate compiles, and rejects everything else, so a pattern it
 * accepts never fails to compile (which clippy's deny-by-default {@code invalid_regex} lint would report) and never
 * changes meaning silently. A rejected pattern is not checked; the generator warns with the reason.
 *
 * <p>Accepted: literals; {@code .}, {@code ^}, {@code $}, {@code |}, groups {@code (...)}, {@code (?:...)} and named
 * groups {@code (?<name>...)}; the quantifiers {@code * + ?}, {@code {n}}, {@code {n,}}, {@code {n,m}} (each
 * optionally lazy) after something to repeat; the escapes {@code \d \D \w \W \s \S \b \B \n \r \t \f \v}, hex and
 * Unicode code points ({@code \xHH}, {@code \x{H..}}, {@code &#92;uHHHH}, {@code &#92;u{H..}}), the general categories
 * {@code \p{L}}, {@code \pL}, ..., and an escaped ASCII punctuation character other than {@code <} and {@code >};
 * character classes {@code [...]} and {@code [^...]} of those, with ranges between single characters.</p>
 *
 * <p>Rejected, among others: lookaround ({@code (?=}, {@code (?!}, {@code (?<=}, {@code (?<!}), atomic groups,
 * backreferences and octal escapes ({@code \1}, {@code \k<..>}), {@code \c}, {@code \Z}, {@code \e} and every other
 * escaped letter, {@code \<} and {@code \>} (word boundaries in Rust, literals in ECMA), the empty classes {@code []}
 * and {@code [^]}, a {@code [} inside a class and {@code &&}, {@code --}, {@code ~~} (Rust's nested classes and set
 * operations), a {@code -} after a class escape or a range, a {@code \b} or a class escape as a range end, {@code {,m}} and any other {@code {} that is no
 * counted repetition or counts above 1000, an unescaped {@code ]} or {@code }} outside a class, a quantifier with nothing to repeat or
 * after another quantifier, inline flags, and unbalanced parentheses.</p>
 */
final class RustRegex {

    private static final Set<Character> SIMPLE_ESCAPES = Set.of('n', 'r', 't', 'f', 'v');
    private static final Set<Character> CLASS_ESCAPES = Set.of('d', 'D', 'w', 'W', 's', 'S');
    private static final Set<String> CATEGORIES = Set.of("L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me",
            "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So", "Z", "Zs",
            "Zl", "Zp", "C", "Cc", "Cf", "Co", "Cn");
    private static final Pattern COUNTED = Pattern.compile("\\{(\\d+)(,(\\d*))?}");
    private static final Pattern GROUP_NAME = Pattern.compile("\\?P?<([A-Za-z_][A-Za-z0-9_]*)>");

    private final String p;
    private int i;

    private RustRegex(String pattern) {
        this.p = pattern;
    }

    /** Why the {@code regex} crate cannot check {@code pattern} as ECMA-262 means it, or {@code null} when it can. */
    static String unsupported(String pattern) {
        try {
            new RustRegex(pattern).parse();
            return null;
        } catch (Unsupported e) {
            return e.getMessage();
        }
    }

    private static final class Unsupported extends Exception {
        Unsupported(String message) {
            super(message);
        }
    }

    private void parse() throws Unsupported {
        int depth = 0;
        boolean repeatable = false;
        while (i < p.length()) {
            char c = p.charAt(i);
            switch (c) {
                case '\\' -> repeatable = escape(false) != Escape.ASSERTION;
                case '[' -> {
                    charClass();
                    repeatable = true;
                }
                case '(' -> {
                    group();
                    depth++;
                    repeatable = false;
                }
                case ')' -> {
                    if (--depth < 0) {
                        throw new Unsupported("an unbalanced ')'");
                    }
                    i++;
                    repeatable = true;
                }
                case '|', '^', '$' -> {
                    i++;
                    repeatable = false;
                }
                case '*', '+', '?' -> {
                    quantifier(repeatable, 1);
                    repeatable = false;
                }
                case '{' -> {
                    Matcher m = COUNTED.matcher(p).region(i, p.length());
                    if (!m.lookingAt()) {
                        throw new Unsupported("a '{' that is no counted repetition {n}, {n,} or {n,m}");
                    }
                    String max = m.group(3) == null || m.group(3).isEmpty() ? m.group(1) : m.group(3);
                    if (m.group(1).length() > 4 || max.length() > 4 || Integer.parseInt(max) > 1000) {
                        throw new Unsupported("a repetition count above 1000");
                    }
                    if (Integer.parseInt(m.group(1)) > Integer.parseInt(max)) {
                        throw new Unsupported("a counted repetition whose minimum exceeds its maximum");
                    }
                    quantifier(repeatable, m.end() - i);
                    repeatable = false;
                }
                case ']', '}' -> throw new Unsupported("an unescaped '" + c + "' outside a character class");
                default -> {
                    i++;
                    repeatable = true;
                }
            }
        }
        if (depth != 0) {
            throw new Unsupported("an unclosed '('");
        }
    }

    private void quantifier(boolean repeatable, int length) throws Unsupported {
        if (!repeatable) {
            throw new Unsupported("a quantifier with nothing to repeat, or after another quantifier");
        }
        i += length;
        if (i < p.length() && p.charAt(i) == '?') {
            i++;
        }
    }

    private void group() throws Unsupported {
        i++;
        if (i >= p.length() || p.charAt(i) != '?') {
            return;
        }
        if (p.startsWith("?:", i)) {
            i += 2;
            return;
        }
        Matcher name = GROUP_NAME.matcher(p).region(i, p.length());
        if (name.lookingAt()) {
            i = name.end();
            return;
        }
        throw new Unsupported(p.startsWith("?=", i) || p.startsWith("?!", i) || p.startsWith("?<=", i)
                || p.startsWith("?<!", i) ? "lookaround" : "the group '(" + p.substring(i, Math.min(p.length(), i + 3))
                + "' (only (...), (?:...) and (?<name>...) are checked)");
    }

    private enum Escape { CHARACTER, CLASS, ASSERTION }

    /** Reads the escape at {@code i}, inside a character class or not. */
    private Escape escape(boolean inClass) throws Unsupported {
        i++;
        if (i >= p.length()) {
            throw new Unsupported("a trailing '\\'");
        }
        char e = p.charAt(i++);
        if (SIMPLE_ESCAPES.contains(e)) {
            return Escape.CHARACTER;
        }
        if (CLASS_ESCAPES.contains(e)) {
            return Escape.CLASS;
        }
        if ((e == 'b' || e == 'B') && !inClass) {
            return Escape.ASSERTION;
        }
        if (e == 'x') {
            return hex(2);
        }
        if (e == 'u') {
            return hex(4);
        }
        if (e == 'p' || e == 'P') {
            String category;
            if (i < p.length() && p.charAt(i) == '{') {
                int close = p.indexOf('}', i);
                if (close < 0) {
                    throw new Unsupported("an unclosed \\" + e + "{");
                }
                category = p.substring(i + 1, close);
                i = close + 1;
            } else if (i < p.length()) {
                category = String.valueOf(p.charAt(i++));
            } else {
                category = "";
            }
            if (!CATEGORIES.contains(category)) {
                throw new Unsupported("the Unicode property \\" + e + "{" + category + "} (only general categories "
                        + "are checked)");
            }
            return Escape.CLASS;
        }
        if (e < 128 && !Character.isLetterOrDigit(e) && e != '<' && e != '>' && e > ' ' && e != 127) {
            return Escape.CHARACTER;
        }
        throw new Unsupported(Character.isDigit(e) ? "a backreference or octal escape \\" + e
                : "the escape \\" + e);
    }

    private Escape hex(int digits) throws Unsupported {
        if (i < p.length() && p.charAt(i) == '{') {
            int close = p.indexOf('}', i);
            if (close < 0 || close == i + 1 || !p.substring(i + 1, close).matches("[0-9A-Fa-f]{1,6}")) {
                throw new Unsupported("a malformed code point escape");
            }
            i = close + 1;
            return Escape.CHARACTER;
        }
        if (i + digits > p.length() || !p.substring(i, i + digits).matches("[0-9A-Fa-f]+")) {
            throw new Unsupported("a malformed code point escape");
        }
        i += digits;
        return Escape.CHARACTER;
    }

    /** Reads a character class starting at {@code i}. */
    private void charClass() throws Unsupported {
        i++;
        if (i < p.length() && p.charAt(i) == '^') {
            i++;
        }
        if (i < p.length() && p.charAt(i) == ']') {
            throw new Unsupported("an empty class or a ']' first in a class ([] and [^] differ in Rust)");
        }
        boolean previousSingle = false;
        boolean first = true;
        while (true) {
            if (i >= p.length()) {
                throw new Unsupported("an unclosed '['");
            }
            char c = p.charAt(i);
            if (c == ']') {
                i++;
                return;
            }
            if (c == '[') {
                throw new Unsupported("a '[' inside a class (a nested class in Rust)");
            }
            if ((c == '&' || c == '~' || c == '-') && i + 1 < p.length() && p.charAt(i + 1) == c) {
                throw new Unsupported("'" + c + c + "' inside a class (a set operation in Rust)");
            }
            if (c == '-' && i + 1 < p.length() && p.charAt(i + 1) != ']' && !first) {
                if (!previousSingle) {
                    throw new Unsupported("a '-' after a class escape or a range (a range in Rust, a literal in ECMA)");
                }
                i++;
                if (p.charAt(i) == '\\') {
                    if (escape(true) != Escape.CHARACTER) {
                        throw new Unsupported("a range ending in a class escape");
                    }
                } else if (p.charAt(i) == '[') {
                    throw new Unsupported("a '[' inside a class (a nested class in Rust)");
                } else {
                    i++;
                }
                previousSingle = false;
                first = false;
                continue;
            }
            if (c == '\\') {
                previousSingle = escape(true) == Escape.CHARACTER;
            } else {
                i++;
                previousSingle = true;
            }
            first = false;
        }
    }
}
