package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluation metrics for the AgenticQA RAG retrieval pipeline.
 *
 * Chunk IDs are derived from path, title and content. If the chunking algorithm
 * changes, IDs can change too; review/update the golden set when that happens.
 */
public final class RetrievalMetrics {
    private RetrievalMetrics() {
    }

    public static String chunkId(SourceFile chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
        return chunkId(
                chunk.path == null ? "" : chunk.path.toString(),
                chunk.title == null ? "" : chunk.title,
                chunk.content == null ? "" : chunk.content);
    }

    public static String chunkId(String path, String title, String content) {
        String stableValue = nullToEmpty(path) + "\n"
                + nullToEmpty(title) + "\n"
                + nullToEmpty(content);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stableValue.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("chunk-");
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /**
     * Precision@K = relevant retrieved / retrieved results.
     * Recall@K = relevant retrieved / all known relevant results.
     * Reciprocal rank is 1/rank of the first relevant retrieved chunk, or 0.
     * Token-budget usage = injected tokens / budget * 100.
     * Corpus injection = injected tokens / corpus tokens * 100.
     */
    public static Metrics calculate(
            Set<String> expectedRelevantIds,
            List<String> retrievedIds,
            int k,
            int injectedTokens,
            int tokenBudget,
            int corpusTokens) {

        Set<String> expected = expectedRelevantIds == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(expectedRelevantIds);
        List<String> retrieved = retrievedIds == null
                ? Collections.<String>emptyList()
                : retrievedIds;

        int limit = Math.min(Math.max(k, 0), retrieved.size());
        int relevantRetrieved = 0;
        double reciprocalRank = 0.0;

        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrieved.get(i))) {
                relevantRetrieved++;
                if (reciprocalRank == 0.0) {
                    reciprocalRank = 1.0 / (i + 1);
                }
            }
        }

        double precision = limit == 0 ? 0.0 : (double) relevantRetrieved / limit;
        double recall = expected.isEmpty()
                ? 0.0
                : (double) relevantRetrieved / expected.size();

        double tokenUsage = tokenBudget <= 0
                ? (injectedTokens == 0 ? 0.0 : 100.0)
                : (double) injectedTokens * 100.0 / tokenBudget;
        double corpusInjection = corpusTokens <= 0
                ? (injectedTokens == 0 ? 0.0 : 100.0)
                : (double) injectedTokens * 100.0 / corpusTokens;

        return new Metrics(
                precision,
                recall,
                reciprocalRank,
                tokenUsage,
                injectedTokens <= tokenBudget,
                corpusInjection,
                relevantRetrieved,
                limit,
                expected.size(),
                injectedTokens,
                tokenBudget,
                corpusTokens);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static final class Metrics {
        public final double precisionAtK;
        public final double recallAtK;
        public final double reciprocalRank;
        public final double tokenBudgetUsagePercent;
        public final boolean withinTokenBudget;
        public final double corpusInjectionPercent;
        public final int relevantRetrieved;
        public final int retrievedCountAtK;
        public final int expectedRelevantCount;
        public final int injectedTokens;
        public final int tokenBudget;
        public final int corpusTokens;

        private Metrics(
                double precisionAtK,
                double recallAtK,
                double reciprocalRank,
                double tokenBudgetUsagePercent,
                boolean withinTokenBudget,
                double corpusInjectionPercent,
                int relevantRetrieved,
                int retrievedCountAtK,
                int expectedRelevantCount,
                int injectedTokens,
                int tokenBudget,
                int corpusTokens) {
            this.precisionAtK = precisionAtK;
            this.recallAtK = recallAtK;
            this.reciprocalRank = reciprocalRank;
            this.tokenBudgetUsagePercent = tokenBudgetUsagePercent;
            this.withinTokenBudget = withinTokenBudget;
            this.corpusInjectionPercent = corpusInjectionPercent;
            this.relevantRetrieved = relevantRetrieved;
            this.retrievedCountAtK = retrievedCountAtK;
            this.expectedRelevantCount = expectedRelevantCount;
            this.injectedTokens = injectedTokens;
            this.tokenBudget = tokenBudget;
            this.corpusTokens = corpusTokens;
        }
    }
}
