package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BM25Ranker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private BM25Ranker() {
    }

    public static List<RankedChunk> rank(
            String query,
            List<SourceFile> chunks,
            Map<SourceFile, String> normalizedTexts) {

        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTerms = tokenizeQuery(query);

        if (queryTerms.isEmpty()) {
            return rankWithZeroScores(chunks);
        }

        int documentCount = chunks.size();

        List<DocumentStats> documents = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();

        int totalDocumentLength = 0;

        for (SourceFile chunk : chunks) {
            String text = normalizedTexts == null
                    ? ""
                    : normalizedTexts.getOrDefault(chunk, "");

            List<String> terms = tokenize(text);

            Map<String, Integer> termFrequency = new HashMap<>();

            for (String term : terms) {
                termFrequency.merge(term, 1, Integer::sum);
            }

            Set<String> uniqueTerms = new HashSet<>(terms);

            for (String term : uniqueTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }

            DocumentStats stats = new DocumentStats(
                    chunk,
                    termFrequency,
                    terms.size());

            documents.add(stats);
            totalDocumentLength += terms.size();
        }

        double averageDocumentLength =
                documentCount == 0
                        ? 0.0
                        : (double) totalDocumentLength / documentCount;

        List<RankedChunk> ranked = new ArrayList<>();

        for (int index = 0; index < documents.size(); index++) {
            DocumentStats document = documents.get(index);

            double score = 0.0;

            for (String term : queryTerms) {
                int tf = document.termFrequency.getOrDefault(term, 0);

                if (tf == 0) {
                    continue;
                }

                int df = documentFrequency.getOrDefault(term, 0);

                double idf = Math.log(
                        1.0
                                + (documentCount - df + 0.5)
                                / (df + 0.5));

                double lengthNormalization =
                        averageDocumentLength == 0.0
                                ? 1.0
                                : 1.0 - B
                                + B * document.documentLength
                                / averageDocumentLength;

                double termScore =
                        idf
                                * ((tf * (K1 + 1.0))
                                / (tf + K1 * lengthNormalization));

                score += termScore;
            }

            ranked.add(new RankedChunk(
                    document.chunk,
                    score,
                    index));
        }

        ranked.sort(
                Comparator.comparingDouble(
                        RankedChunk::score)
                        .reversed()
                        .thenComparingInt(
                                RankedChunk::originalIndex));

        return Collections.unmodifiableList(ranked);
    }

    private static List<RankedChunk> rankWithZeroScores(
            List<SourceFile> chunks) {

        List<RankedChunk> ranked = new ArrayList<>();

        for (int index = 0; index < chunks.size(); index++) {
            ranked.add(new RankedChunk(
                    chunks.get(index),
                    0.0,
                    index));
        }

        return Collections.unmodifiableList(ranked);
    }

    private static List<String> tokenizeQuery(String query) {
        return uniqueTokens(
                TextNormalizer.normalize(query));
    }

    private static List<String> tokenize(String text) {
        String normalized = text == null
                ? ""
                : text.trim();

        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = normalized.split("\\s+");

        List<String> tokens = new ArrayList<>();

        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }

        return tokens;
    }

    private static List<String> uniqueTokens(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = normalized.split("\\s+");

        Set<String> unique = new HashSet<>();

        for (String part : parts) {
            if (!part.isEmpty()) {
                unique.add(part);
            }
        }

        return new ArrayList<>(unique);
    }

    private static final class DocumentStats {

        private final SourceFile chunk;
        private final Map<String, Integer> termFrequency;
        private final int documentLength;

        private DocumentStats(
                SourceFile chunk,
                Map<String, Integer> termFrequency,
                int documentLength) {

            this.chunk = chunk;
            this.termFrequency = termFrequency;
            this.documentLength = documentLength;
        }
    }

    public static final class RankedChunk {

        private final SourceFile chunk;
        private final double score;
        private final int originalIndex;

        private RankedChunk(
                SourceFile chunk,
                double score,
                int originalIndex) {

            this.chunk = chunk;
            this.score = score;
            this.originalIndex = originalIndex;
        }

        public SourceFile chunk() {
            return chunk;
        }

        public double score() {
            return score;
        }

        private int originalIndex() {
            return originalIndex;
        }
    }
}