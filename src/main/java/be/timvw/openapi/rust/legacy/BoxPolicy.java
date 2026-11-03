package be.timvw.openapi.rust.legacy;

/**
 * Whether a rewrite that writes a model payload itself renders it as {@code Box<models::T>} (Milestone 3.1). The
 * generator decides from the model graph with {@code boxOnlyCycles}; the scripts boxed every payload.
 */
@FunctionalInterface
public interface BoxPolicy {

    /** Every model payload is boxed, as the stock generator and the scripts did. */
    BoxPolicy ALWAYS = (holder, target) -> true;

    /**
     * @param holder the Rust type that holds the payload
     * @param target the Rust type of the payload
     */
    boolean box(String holder, String target);

    /** {@code Box<models::T>} or {@code models::T}. */
    default String type(String holder, String target) {
        return box(holder, target) ? "Box<models::" + target + ">" : "models::" + target;
    }
}
