package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single public entry point for RAG retrieval.
 *
 * Topic 1 implementation:
 *
 *   Feature files -> Scenario / Scenario Outline chunks
 *   Java files    -> method / constructor chunks
 *
 * Later topics will add normalization, ranking, query expansion,
 * deduplication, reuse-first retrieval, evaluation and caching.
 */
public final class RagEngine {

    private RagEngine() {
    }

    public static RagContext buildContext(
            Path featureDir,
            Path testSourceRoot,
            String prompt,
            int topK,
            int tokenBudget) throws IOException {

        /*
         * Topic 1 intentionally focuses on structured chunking.
         *
         * Ranking is introduced in Topic 3, so at this stage we collect
         * semantic chunks deterministically.
         */
        FeatureChunker.Result featureResult =
            FeatureChunker.chunkDirectory(featureDir);

        JavaChunker.Result javaResult =
            JavaChunker.chunkDirectory(testSourceRoot);

        List<SourceFile> allChunks = new ArrayList<>(
            featureResult.chunks.size()
                + javaResult.chunks.size()
        );

        allChunks.addAll(featureResult.chunks);
        allChunks.addAll(javaResult.chunks);

        int corpusFiles =
            featureResult.fileCount
                + javaResult.fileCount;

        int corpusTokens =
            estimateTokens(allChunks);

        /*
         * There is no ranking yet in Topic 1.
         *
         * Returning all chunks would potentially exceed the token budget,
         * so apply a deterministic first-fit budget as temporary plumbing.
         *
         * Topic 3 will replace this with BM25-ranked token-budget selection.
         */
        List<SourceFile> selected =
            selectWithinBudget(allChunks, topK, tokenBudget);

        int injectedTokens =
            estimateTokens(selected);

        return new RagContext(
            selected,
            corpusFiles,
            allChunks.size(),
            corpusTokens,
            injectedTokens
        );
    }

    private static List<SourceFile> selectWithinBudget(
            List<SourceFile> chunks,
            int topK,
            int tokenBudget) {

        if (chunks.isEmpty()
                || topK <= 0
                || tokenBudget <= 0) {

            return Collections.emptyList();
        }

        List<SourceFile> selected = new ArrayList<>();

        int usedTokens = 0;

        for (SourceFile chunk : chunks) {

            if (selected.size() >= topK) {
                break;
            }

            int tokens = estimateTokens(chunk);

            if (usedTokens + tokens > tokenBudget) {
                continue;
            }

            selected.add(chunk);
            usedTokens += tokens;
        }

        return selected;
    }

    /**
     * Existing project code estimates tokens at approximately
     * one token per four characters, so Topic 1 follows the same
     * convention for consistent statistics.
     */
    private static int estimateTokens(
            List<SourceFile> files) {

        int total = 0;

        for (SourceFile file : files) {
            total += estimateTokens(file);
        }

        return total;
    }

    private static int estimateTokens(
            SourceFile file) {

        if (file == null
                || file.content == null
                || file.content.isEmpty()) {

            return 0;
        }

        return Math.max(
            1,
            file.content.length() / 4
        );
    }

    public static final class RagContext {

        public final List<SourceFile> chunks;

        public final int corpusFiles;

        public final int corpusChunks;

        public final int corpusTokens;

        public final int injectedTokens;

        public RagContext(
                List<SourceFile> chunks,
                int corpusFiles,
                int corpusChunks,
                int corpusTokens,
                int injectedTokens) {

            this.chunks =
                Collections.unmodifiableList(
                    new ArrayList<>(chunks)
                );

            this.corpusFiles = corpusFiles;
            this.corpusChunks = corpusChunks;
            this.corpusTokens = corpusTokens;
            this.injectedTokens = injectedTokens;
        }
    }
}