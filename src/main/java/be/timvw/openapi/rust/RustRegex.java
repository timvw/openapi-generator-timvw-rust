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
 * counted repetition, repetition counts that multiply to more than 1000 (100 for {@code \\w}, {@code \\p{..}} and
 * other large Unicode classes, which the crate's size limit rejects), code points that are no Unicode scalar value,
 * reversed ranges, a group name used twice, an unescaped {@code ]} or {@code }} outside a class, a quantifier with nothing to repeat or
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

    /** The most a counted repetition may multiply to, nested repetitions included. */
    static final int MAX_REPETITION = 1000;
    /** The most a large Unicode class ({@code \w}, {@code \p{L}}, ...) may be repeated, nested repetitions included. */
    static final int MAX_CLASS_REPETITION = 100;
    /** Class escapes whose Unicode class compiles large: repeating them often exceeds the crate's size limit. */
    private static final Set<Character> LARGE_CLASSES = Set.of('w', 'W', 'S', 'D', 'p', 'P');

    private final String p;
    private int i;
    /** The code point of the last single-character escape read. */
    private int codePoint;
    /** Whether the last class escape, or the last character class, holds a large Unicode class. */
    private boolean large;
    private final Set<String> names = new java.util.HashSet<>();

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

    /** What a group repeats: the largest repetition product inside it, and whether it holds a large class. */
    private static final class Frame {
        long product = 1;
        boolean large;
    }

    private void parse() throws Unsupported {
        java.util.Deque<Frame> frames = new java.util.ArrayDeque<>();
        frames.push(new Frame());
        boolean repeatable = false;
        long atomProduct = 1;
        boolean atomLarge = false;
        while (i < p.length()) {
            char c = p.charAt(i);
            switch (c) {
                case '\\' -> {
                    Escape kind = escape(false);
                    repeatable = kind != Escape.ASSERTION;
                    atomProduct = 1;
                    atomLarge = kind == Escape.CLASS && large;
                    frames.peek().large |= atomLarge;
                }
                case '[' -> {
                    charClass();
                    repeatable = true;
                    atomProduct = 1;
                    atomLarge = large;
                    frames.peek().large |= atomLarge;
                }
                case '(' -> {
                    group();
                    frames.push(new Frame());
                    repeatable = false;
                }
                case ')' -> {
                    if (frames.size() == 1) {
                        throw new Unsupported("an unbalanced ')'");
                    }
                    Frame inner = frames.pop();
                    frames.peek().product = Math.max(frames.peek().product, inner.product);
                    frames.peek().large |= inner.large;
                    atomProduct = inner.product;
                    atomLarge = inner.large;
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
                    if (m.group(1).length() > 4 || max.length() > 4) {
                        throw new Unsupported("a repetition count above " + MAX_REPETITION);
                    }
                    if (Integer.parseInt(m.group(1)) > Integer.parseInt(max)) {
                        throw new Unsupported("a counted repetition whose minimum exceeds its maximum");
                    }
                    long product = Integer.parseInt(max) * atomProduct;
                    if (product > MAX_REPETITION) {
                        throw new Unsupported("repetition counts that multiply to more than " + MAX_REPETITION
                                + " (beyond the regex crate's size limit)");
                    }
                    if (atomLarge && product > MAX_CLASS_REPETITION) {
                        throw new Unsupported("a large Unicode class (\\w, \\p{..}, ...) repeated more than "
                                + MAX_CLASS_REPETITION + " times (beyond the regex crate's size limit)");
                    }
                    frames.peek().product = Math.max(frames.peek().product, product);
                    quantifier(repeatable, m.end() - i);
                    repeatable = false;
                }
                case ']', '}' -> throw new Unsupported("an unescaped '" + c + "' outside a character class");
                default -> {
                    i++;
                    repeatable = true;
                    atomProduct = 1;
                    atomLarge = false;
                }
            }
        }
        if (frames.size() != 1) {
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
            if (!names.add(name.group(1))) {
                throw new Unsupported("the group name '" + name.group(1) + "' twice");
            }
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
            codePoint = switch (e) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'f' -> '\f';
                default -> 0x0B;
            };
            return Escape.CHARACTER;
        }
        if (CLASS_ESCAPES.contains(e)) {
            large = LARGE_CLASSES.contains(e);
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
            large = true;
            return Escape.CLASS;
        }
        if (e < 128 && !Character.isLetterOrDigit(e) && e != '<' && e != '>' && e > ' ' && e != 127) {
            codePoint = e;
            return Escape.CHARACTER;
        }
        throw new Unsupported(Character.isDigit(e) ? "a backreference or octal escape \\" + e
                : "the escape \\" + e);
    }

    private Escape hex(int digits) throws Unsupported {
        String hex;
        if (i < p.length() && p.charAt(i) == '{') {
            int close = p.indexOf('}', i);
            if (close < 0 || close == i + 1 || !p.substring(i + 1, close).matches("[0-9A-Fa-f]{1,6}")) {
                throw new Unsupported("a malformed code point escape");
            }
            hex = p.substring(i + 1, close);
            i = close + 1;
        } else {
            if (i + digits > p.length() || !p.substring(i, i + digits).matches("[0-9A-Fa-f]+")) {
                throw new Unsupported("a malformed code point escape");
            }
            hex = p.substring(i, i + digits);
            i += digits;
        }
        codePoint = Integer.parseInt(hex, 16);
        if (codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new Unsupported("the code point escape of " + hex + ", which is no Unicode scalar value "
                    + "(a surrogate or beyond U+10FFFF)");
        }
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
        boolean holdsLarge = false;
        int previous = -1;
        while (true) {
            if (i >= p.length()) {
                throw new Unsupported("an unclosed '['");
            }
            char c = p.charAt(i);
            if (c == ']') {
                i++;
                large = holdsLarge;
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
                int end;
                if (p.charAt(i) == '\\') {
                    if (escape(true) != Escape.CHARACTER) {
                        throw new Unsupported("a range ending in a class escape");
                    }
                    end = codePoint;
                } else if (p.charAt(i) == '[') {
                    throw new Unsupported("a '[' inside a class (a nested class in Rust)");
                } else {
                    end = p.codePointAt(i);
                    i += Character.charCount(end);
                }
                if (end < previous) {
                    throw new Unsupported("a range whose start is after its end");
                }
                previousSingle = false;
                first = false;
                continue;
            }
            if (c == '\\') {
                Escape kind = escape(true);
                previousSingle = kind == Escape.CHARACTER;
                previous = codePoint;
                holdsLarge |= kind == Escape.CLASS && large;
            } else {
                previous = p.codePointAt(i);
                i += Character.charCount(previous);
                previousSingle = true;
            }
            first = false;
        }
    }
}
