package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RepositoryVocabulary {

    private RepositoryVocabulary() {
        // Utility class.
    }

    public static Set<String> extract(List<SourceFile> chunks) {

        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> vocabulary = new LinkedHashSet<>();

        for (SourceFile chunk : chunks) {

            if (chunk == null) {
                continue;
            }

            addTerms(vocabulary, chunk.title);
            addTerms(vocabulary, chunk.path);
            addTerms(vocabulary, chunk.content);
        }

        return Collections.unmodifiableSet(vocabulary);
    }

    public static Map<String, Integer> termFrequencies(
            List<SourceFile> chunks) {

        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> frequencies = new LinkedHashMap<>();

        for (SourceFile chunk : chunks) {

            if (chunk == null) {
                continue;
            }

            addTermFrequencies(
                    frequencies,
                    chunk.title);

            addTermFrequencies(
                    frequencies,
                    chunk.path);

            addTermFrequencies(
                    frequencies,
                    chunk.content);
        }

        return Collections.unmodifiableMap(frequencies);
    }

    private static void addTerms(
            Set<String> vocabulary,
            String text) {

        String normalized = TextNormalizer.normalize(text);

        if (normalized.isEmpty()) {
            return;
        }

        String[] terms = normalized.split(" ");

        for (String term : terms) {

            if (!term.isEmpty()) {
                vocabulary.add(term);
            }
        }
    }

    private static void addTermFrequencies(
            Map<String, Integer> frequencies,
            String text) {

        String normalized = TextNormalizer.normalize(text);

        if (normalized.isEmpty()) {
            return;
        }

        String[] terms = normalized.split(" ");

        for (String term : terms) {

            if (!term.isEmpty()) {
                frequencies.merge(
                        term,
                        1,
                        Integer::sum);
            }
        }
    }

    public static List<SourceFile> rankByRelevance(
            List<SourceFile> chunks,
            String query) {

        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTerms = queryTerms(query);

        if (queryTerms.isEmpty()) {
            return Collections.unmodifiableList(
                    new ArrayList<>(chunks));
        }

        int documentCount = chunks.size();

        Map<String, Integer> documentFrequency = new HashMap<>();

        for (SourceFile chunk : chunks) {

            if (chunk == null) {
                continue;
            }

            for (String term : uniqueTerms(chunk)) {
                documentFrequency.merge(
                        term,
                        1,
                        Integer::sum);
            }
        }

        List<ScoredChunk> scored = new ArrayList<>();

        for (int index = 0; index < chunks.size(); index++) {

            SourceFile chunk = chunks.get(index);

            double score = 0.0;

            if (chunk != null) {

                Set<String> terms = uniqueTerms(chunk);

                for (String term : queryTerms) {

                    if (!terms.contains(term)) {
                        continue;
                    }

                    int df = documentFrequency.getOrDefault(
                            term,
                            0);

                    double idf = Math.log(
                            1.0
                                    + (documentCount - df + 0.5)
                                            / (df + 0.5));

                    score += idf;
                }
            }

            scored.add(new ScoredChunk(chunk, score, index));
        }

        scored.sort(
                Comparator.comparingDouble(
                        ScoredChunk::score)
                        .reversed()
                        .thenComparingInt(
                                ScoredChunk::originalIndex));

        List<SourceFile> ranked = new ArrayList<>();

        for (ScoredChunk entry : scored) {
            ranked.add(entry.chunk());
        }

        return Collections.unmodifiableList(ranked);
    }

    private static List<String> queryTerms(String query) {

        String normalized = TextNormalizer.normalize(query);

        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> unique = new LinkedHashSet<>();

        for (String term : normalized.split(" ")) {

            if (!term.isEmpty()) {
                unique.add(term);
            }
        }

        return new ArrayList<>(unique);
    }

    private static Set<String> uniqueTerms(SourceFile chunk) {

        Set<String> terms = new LinkedHashSet<>();

        addTerms(terms, chunk.title);
        addTerms(terms, chunk.path);
        addTerms(terms, chunk.content);

        return terms;
    }

    private static final class ScoredChunk {

        private final SourceFile chunk;
        private final double score;
        private final int originalIndex;

        private ScoredChunk(
                SourceFile chunk,
                double score,
                int originalIndex) {

            this.chunk = chunk;
            this.score = score;
            this.originalIndex = originalIndex;
        }

        private SourceFile chunk() {
            return chunk;
        }

        private double score() {
            return score;
        }

        private int originalIndex() {
            return originalIndex;
        }
    }
}