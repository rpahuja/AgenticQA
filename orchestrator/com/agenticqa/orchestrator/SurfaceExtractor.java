package com.agenticqa.orchestrator;

/**
 * Deterministic "repository surface" extraction.
 *
 * TODO Day 6 - implement: collect the hard facts of the repository the AI
 * must not invent - the exact wording of every existing step definition, the
 * shared test-world state fields, hooks, and the real signatures of the
 * services/models the generated steps may call. Extraction must cost zero
 * AI tokens. The text is attached to the model prompt by the orchestrator.
 */
public final class SurfaceExtractor {

    private SurfaceExtractor() {
    }

    public static Surface extract(RepoLayout layout) {
        return new Surface("", 0, 0, 0);
    }

    /** What the extractor produced: the prompt text plus its stats. */
    public static final class Surface {
        public final String text;
        public final int steps;
        public final int fields;
        public final int tokenEstimate;

        public Surface(String text, int steps, int fields, int tokenEstimate) {
            this.text = text;
            this.steps = steps;
            this.fields = fields;
            this.tokenEstimate = tokenEstimate;
        }
    }
}
