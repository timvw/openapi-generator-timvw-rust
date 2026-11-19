package be.timvw.openapi.rust;

/**
 * The names {@code bon} gives the items it generates, for the templates that refer to them.
 *
 * <p>bon's finish function ({@code build()} of a model's builder, {@code call()} of a function's) requires
 * {@code S: <state module>::IsComplete}, a trait with a blanket impl, one per builder. That bound makes the trait
 * reachable from the public API, and rustdoc then tries every such blanket impl on every documented struct and enum
 * of the crate: the number of builders times the number of types. For openai-client-base (about 3,200 builders and
 * 8,700 types) that was more than 40 minutes and 24 GB, where docs.rs has 15 minutes and 6 GB.</p>
 *
 * <p>So bon's finish function is private ({@code build_internal}, {@code call_internal}) and the template adds the
 * public {@code build()} or {@code call()}, bounded by the state of each required member ({@code S::Name: IsSet})
 * instead of by {@code IsComplete}. The trait is not reachable any more, and rustdoc skips its blanket impl. The
 * template names the builder type, its state module and the associated types of its state the way bon does, through
 * the lambdas of this class.</p>
 */
final class BonNames {

    /** Mustache lambda: a snake_case identifier in PascalCase, as bon names a builder and the members of its state. */
    static final String PASCAL_LAMBDA = "bonPascal";
    /** Mustache lambda: a PascalCase identifier in snake_case, as bon names a builder's state module. */
    static final String SNAKE_LAMBDA = "bonSnake";

    private BonNames() {
    }

    /**
     * {@code ident_case::RenameRule::PascalCase.apply_to_field}, which bon applies to a member or function name: the
     * {@code r#} prefix dropped, each {@code _} dropped and the character after it upper-cased; {@code Self} becomes
     * {@code Self_}.
     */
    static String pascal(String ident) {
        String name = ident.startsWith("r#") ? ident.substring(2) : ident;
        StringBuilder out = new StringBuilder(name.length());
        boolean capitalize = true;
        for (char ch : name.toCharArray()) {
            if (ch == '_') {
                capitalize = true;
            } else if (capitalize) {
                out.append(Character.toUpperCase(ch));
                capitalize = false;
            } else {
                out.append(ch);
            }
        }
        String pascal = out.toString();
        return "Self".equals(pascal) ? "Self_" : pascal;
    }

    /**
     * {@code ident_case::RenameRule::SnakeCase.apply_to_variant}, which bon applies to a builder type's name for its
     * state module: an {@code _} before each upper-case character but the first, everything lower-cased.
     */
    static String snake(String ident) {
        StringBuilder out = new StringBuilder(ident.length() + 8);
        for (int i = 0; i < ident.length(); i++) {
            char ch = ident.charAt(i);
            if (i > 0 && Character.isUpperCase(ch)) {
                out.append('_');
            }
            out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }
}
