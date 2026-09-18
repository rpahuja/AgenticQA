package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RagEngine {

    private RagEngine() {
    }

    public static RagContext buildContext(
            Path featureDir,
            Path testSourceRoot,
            String prompt,
            int topK,
            int tokenBudget) throws IOException {

        return buildContext(featureDir, testSourceRoot, prompt, topK, tokenBudget, false);
    }

    /**
     * Same as {@link #buildContext(Path, Path, String, int, int)} but with
     * optional normalization/vocabulary diagnostics on stdout.
     *
     * @param debug when {@code true}, dump every chunk's original and
     *              normalized text plus the repository vocabulary.
     */
    public static RagContext buildContext(
            Path featureDir,
            Path testSourceRoot,
            String prompt,
            int topK,
            int tokenBudget,
            boolean debug) throws IOException {

        FeatureChunker.Result featureResult = FeatureChunker.chunkDirectory(featureDir);

        JavaChunker.Result javaResult = JavaChunker.chunkDirectory(testSourceRoot);

        List<SourceFile> allChunks = new ArrayList<>(
                featureResult.chunks.size()
                        + javaResult.chunks.size());

        allChunks.addAll(featureResult.chunks);
        allChunks.addAll(javaResult.chunks);

        Map<SourceFile, String> normalizedTexts = new LinkedHashMap<>();

        for (SourceFile chunk : allChunks) {
            normalizedTexts.put(
                    chunk,
                    TextNormalizer.normalize(
                            chunk.title + " " + chunk.content));
        }

        Set<String> vocabulary = RepositoryVocabulary.extract(allChunks);

        int corpusFiles = featureResult.fileCount
                + javaResult.fileCount;

        int corpusTokens = estimateTokens(allChunks);

        List<SourceFile> selected = selectWithinBudget(allChunks, topK, tokenBudget);

        int injectedTokens = estimateTokens(selected);

        if (debug) {
            System.out.println();
            System.out.println("=== NORMALIZATION DEBUG ===");

            for (SourceFile chunk : allChunks) {
                String normalized = normalizedTexts.get(chunk);

                System.out.println();
                System.out.println("File: " + chunk.path);
                System.out.println("Title: " + chunk.title);
                System.out.println("Original:");
                System.out.println(chunk.content);
                System.out.println("Normalized:");
                System.out.println(normalized);
            }

            System.out.println();
            System.out.println("Repository vocabulary:");
            System.out.println(vocabulary);
            System.out.println("=== END NORMALIZATION DEBUG ===");
        }

        return new RagContext(
                selected,
                corpusFiles,
                allChunks.size(),
                corpusTokens,
                injectedTokens,
                normalizedTexts,
                vocabulary);
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
                file.content.length() / 4);
    }

    public static final class RagContext {

        public final List<SourceFile> chunks;

        public final int corpusFiles;

        public final int corpusChunks;

        public final int corpusTokens;

        public final int injectedTokens;

        public final Map<SourceFile, String> normalizedTexts;
        public final Set<String> vocabulary;

        public RagContext(
                List<SourceFile> chunks,
                int corpusFiles,
                int corpusChunks,
                int corpusTokens,
                int injectedTokens,
                Map<SourceFile, String> normalizedTexts,
                Set<String> vocabulary) {

            this.chunks = Collections.unmodifiableList(
                    new ArrayList<>(chunks));

            this.corpusFiles = corpusFiles;
            this.corpusChunks = corpusChunks;
            this.corpusTokens = corpusTokens;
            this.injectedTokens = injectedTokens;
            this.normalizedTexts = Collections.unmodifiableMap(new LinkedHashMap<>(normalizedTexts));
            this.vocabulary = Collections.unmodifiableSet(new LinkedHashSet<>(vocabulary));
        }
    }
}