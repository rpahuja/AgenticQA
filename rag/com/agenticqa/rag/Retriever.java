package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.List;

/**
 * Picks the most relevant pieces for a question, within a token budget.
 *
 * TODO Day 2-5 - implement. Ranking that values evidence quality over file
 * size, wording-tolerant matching, near-duplicate control, and a hard token
 * budget. Zero AI tokens for retrieval itself.
 *
 * Signature contract - the orchestrator calls exactly this; keep it.
 */
public final class Retriever {

    private Retriever() {
    }

    public static List<SourceFile> retrieve(List<SourceFile> chunks, String query, int topK, int tokenBudget) {
        throw new UnsupportedOperationException("TODO Day 2-5 - Retriever not implemented yet");
    }
}
