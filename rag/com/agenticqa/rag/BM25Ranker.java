package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BM25Ranker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /*
     * Phrase matches are deliberately a bonus rather than a replacement
     * for BM25. A phrase should improve an already relevant document,
     * but should not completely bypass normal BM25 relevance.
     */
    private static final double PHRASE_BOOST = 0.50;

    private BM25Ranker() {
    }

    /**
     * Backward-compatible ranking entry point.
     *
     * It performs query analysis without repository-specific synonyms.
     */
    public static List<RankedChunk> rank(
            String query,
            List<SourceFile> chunks,
            Map<SourceFile, String> normalizedTexts) {

        QueryAnalysis analysis =
                QueryAnalyzer.analyze(query);

        return rank(
                analysis,
                chunks,
                normalizedTexts);
    }

    /**
     * Rank repository chunks using a pre-analyzed query.
     *
     * The analysis provides:
     * - normalized terms
     * - synonym-expanded terms
     * - candidate phrases
     *
     * BM25 remains responsible for relevance scoring.
     */
    public static List<RankedChunk> rank(
            QueryAnalysis analysis,
            List<SourceFile> chunks,
            Map<SourceFile, String> normalizedTexts) {

        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        if (analysis == null) {
            return rankWithZeroScores(chunks);
        }

        List<String> queryTerms =
                tokenizeExpandedTerms(
                        analysis.expandedTerms());

        if (queryTerms.isEmpty()) {
            return rankWithZeroScores(chunks);
        }

        List<String> phrases =
                analysis.phrases();

        int documentCount = chunks.size();

        List<DocumentStats> documents =
                new ArrayList<>();

        Map<String, Integer> documentFrequency =
                new HashMap<>();

        int totalDocumentLength = 0;

        for (SourceFile chunk : chunks) {

            String text =
                    normalizedTexts == null
                            ? ""
                            : normalizedTexts.getOrDefault(
                                    chunk,
                                    "");

            List<String> terms =
                    tokenize(text);

            Map<String, Integer> termFrequency =
                    new HashMap<>();

            for (String term : terms) {
                termFrequency.merge(
                        term,
                        1,
                        Integer::sum);
            }

            Set<String> uniqueTerms =
                    new HashSet<>(terms);

            for (String term : uniqueTerms) {
                documentFrequency.merge(
                        term,
                        1,
                        Integer::sum);
            }

            DocumentStats stats =
                    new DocumentStats(
                            chunk,
                            termFrequency,
                            terms.size(),
                            text);

            documents.add(stats);
            totalDocumentLength += terms.size();
        }

        double averageDocumentLength =
                documentCount == 0
                        ? 0.0
                        : (double) totalDocumentLength
                        / documentCount;

        List<RankedChunk> ranked =
                new ArrayList<>();

        for (int index = 0;
             index < documents.size();
             index++) {

            DocumentStats document =
                    documents.get(index);

            double score = 0.0;

            for (String term : queryTerms) {

                int tf =
                        document.termFrequency
                                .getOrDefault(
                                        term,
                                        0);

                if (tf == 0) {
                    continue;
                }

                int df =
                        documentFrequency
                                .getOrDefault(
                                        term,
                                        0);

                double idf =
                        Math.log(
                                1.0
                                        + (documentCount
                                        - df
                                        + 0.5)
                                        / (df + 0.5));

                double lengthNormalization =
                        averageDocumentLength == 0.0
                                ? 1.0
                                : 1.0 - B
                                + B
                                * document.documentLength
                                / averageDocumentLength;

                double termScore =
                        idf
                                * ((tf * (K1 + 1.0))
                                / (tf
                                + K1
                                * lengthNormalization));

                score += termScore;
            }

            /*
             * Phrase boosting happens after the normal BM25 score.
             */
            score += calculatePhraseBoost(
                    document.normalizedText,
                    phrases);

            ranked.add(
                    new RankedChunk(
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

        return Collections.unmodifiableList(
                ranked);
    }

    /**
     * Convert expanded terms into individual BM25 tokens.
     *
     * Example:
     *
     * ["login", "sign in"]
     *
     * becomes:
     *
     * ["login", "sign", "in"]
     *
     * LinkedHashSet prevents synonym expansion from
     * artificially duplicating terms.
     */
    private static List<String> tokenizeExpandedTerms(
            List<String> expandedTerms) {

        if (expandedTerms == null
                || expandedTerms.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> unique =
                new LinkedHashSet<>();

        for (String expandedTerm : expandedTerms) {

            if (expandedTerm == null
                    || expandedTerm.isEmpty()) {
                continue;
            }

            String normalized =
                    TextNormalizer.normalize(
                            expandedTerm);

            if (normalized.isEmpty()) {
                continue;
            }

            String[] parts =
                    normalized.split("\\s+");

            for (String part : parts) {
                if (!part.isEmpty()) {
                    unique.add(part);
                }
            }
        }

        return new ArrayList<>(unique);
    }

    /**
     * Calculate a small deterministic bonus for exact phrase
     * occurrences in the normalized document.
     *
     * A phrase can contribute once for each occurrence.
     */
    private static double calculatePhraseBoost(
            String normalizedText,
            List<String> phrases) {

        if (normalizedText == null
                || normalizedText.isEmpty()
                || phrases == null
                || phrases.isEmpty()) {
            return 0.0;
        }

        double boost = 0.0;

        Set<String> uniquePhrases =
                new LinkedHashSet<>();

        for (String phrase : phrases) {

            if (phrase == null
                    || phrase.isEmpty()) {
                continue;
            }

            String normalizedPhrase =
                    TextNormalizer.normalize(
                            phrase);

            if (normalizedPhrase.isEmpty()) {
                continue;
            }

            uniquePhrases.add(
                    normalizedPhrase);
        }

        for (String phrase : uniquePhrases) {

            int occurrences =
                    countOccurrences(
                            normalizedText,
                            phrase);

            boost += occurrences
                    * PHRASE_BOOST;
        }

        return boost;
    }

    /**
     * Count non-overlapping occurrences of a phrase.
     */
    private static int countOccurrences(
            String text,
            String phrase) {

        int count = 0;
        int start = 0;

        while (true) {

            int position =
                    text.indexOf(
                            phrase,
                            start);

            if (position < 0) {
                break;
            }

            count++;

            start =
                    position
                            + phrase.length();
        }

        return count;
    }

    private static List<RankedChunk> rankWithZeroScores(
            List<SourceFile> chunks) {

        List<RankedChunk> ranked =
                new ArrayList<>();

        for (int index = 0;
             index < chunks.size();
             index++) {

            ranked.add(
                    new RankedChunk(
                            chunks.get(index),
                            0.0,
                            index));
        }

        return Collections.unmodifiableList(
                ranked);
    }

    private static List<String> tokenize(
            String text) {

        String normalized =
                text == null
                        ? ""
                        : text.trim();

        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts =
                normalized.split("\\s+");

        List<String> tokens =
                new ArrayList<>();

        for (String part : parts) {

            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }

        return tokens;
    }

    private static final class DocumentStats {

        private final SourceFile chunk;
        private final Map<String, Integer> termFrequency;
        private final int documentLength;
        private final String normalizedText;

        private DocumentStats(
                SourceFile chunk,
                Map<String, Integer> termFrequency,
                int documentLength,
                String normalizedText) {

            this.chunk = chunk;
            this.termFrequency = termFrequency;
            this.documentLength = documentLength;
            this.normalizedText = normalizedText;
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