package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.nio.file.Path;
import java.util.List;

/**
 * The single RAG entry point.
 *
 * The orchestrator calls ONLY this method when RAG is enabled and never
 * touches the RAG internals. Implement the whole pipeline in this package:
 * collect the test artifacts, cut them into pieces, rank the pieces against
 * the question, and keep the best few within the token budget.
 *
 * TODO - implement. This is your work area.
 */
public final class RagEngine {

    private RagEngine() {
    }

    public static RagContext buildContext(Path featureDir, Path testSourceRoot,
                                          String prompt, int topK, int tokenBudget) {
        throw new UnsupportedOperationException("TODO - RAG not implemented yet");
    }

    /** What the RAG returns: the pieces that reach the AI + corpus stats for logging. */
    public static final class RagContext {
        public final List<SourceFile> chunks;   // the picked pieces (never null)
        public final int corpusFiles;
        public final int corpusChunks;
        public final int corpusTokens;
        public final int injectedTokens;

        public RagContext(List<SourceFile> chunks, int corpusFiles, int corpusChunks,
                          int corpusTokens, int injectedTokens) {
            this.chunks = chunks;
            this.corpusFiles = corpusFiles;
            this.corpusChunks = corpusChunks;
            this.corpusTokens = corpusTokens;
            this.injectedTokens = injectedTokens;
        }
    }
}
