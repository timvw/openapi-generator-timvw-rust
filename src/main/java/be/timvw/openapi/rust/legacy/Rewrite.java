package be.timvw.openapi.rust.legacy;

import java.io.IOException;

/**
 * One transitional rewrite of the rendered crate, a faithful port of one openai-client-base post-processing script
 * (Milestone 2.4). Each rewrite reads and writes the files under {@link LegacyContext#root()}{@code /src} exactly as the
 * script did, so that the generator reproduces the client's current public API until Milestone 3 replaces these
 * text rewrites with model-level rules.
 */
@FunctionalInterface
public interface Rewrite {

    void apply(LegacyContext context) throws IOException;
}
