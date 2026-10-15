package com.agenticqa.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class QueryAnalyzer {

    /**
     * Function words that carry no retrieval signal. Without filtering,
     * chunks that happen to contain "the" / "a" (feature texts) outrank
     * short code chunks that only share the meaningful terms, because
     * BM25 sums one score per matching query term.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "for", "of", "in", "on", "to",
            "with", "by", "at", "from", "into", "about", "as", "that",
            "this", "these", "those", "it", "its", "is", "are", "was",
            "were", "be", "been", "do", "does", "did", "can", "could",
            "should", "would", "will", "what", "which", "who", "whom",
            "how", "when", "where", "why", "find", "get", "please",
            "me", "my", "our", "your");

    private QueryAnalyzer() {
    }

    public static QueryAnalysis analyze(String query) {
        return analyze(
                query,
                Collections.emptySet(),
                Collections.emptyMap());
    }

    
    public static QueryAnalysis analyze(
            String query,
            Set<String> repositoryVocabulary,
            Map<String, List<String>> synonyms) {

        String originalQuery = query;

        String normalizedQuery =
                TextNormalizer.normalize(query);

        if (normalizedQuery.isEmpty()) {
            return new QueryAnalysis(
                    originalQuery,
                    "",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        List<String> terms =
                extractUniqueTerms(normalizedQuery);

        List<String> phrases =
                extractTwoWordPhrases(terms);

        Set<String> expandedTerms =
                new LinkedHashSet<>(terms);

        expandSynonyms(
                terms,
                repositoryVocabulary,
                synonyms,
                expandedTerms);

        return new QueryAnalysis(
                originalQuery,
                normalizedQuery,
                terms,
                phrases,
                new ArrayList<>(expandedTerms));
    }

   
    private static List<String> extractUniqueTerms(
            String normalizedQuery) {

        if (normalizedQuery == null
                || normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts =
                normalizedQuery.split("\\s+");

        LinkedHashSet<String> all =
                new LinkedHashSet<>();

        for (String part : parts) {
            if (!part.isEmpty()) {
                all.add(part);
            }
        }

        List<String> filtered =
                new ArrayList<>();

        for (String term : all) {
            if (!STOPWORDS.contains(term)) {
                filtered.add(term);
            }
        }

        if (filtered.isEmpty()) {
            // Stopword-only prompt ("find the login"): keep the original
            // terms rather than ranking everything with an empty query.
            return new ArrayList<>(all);
        }

        return filtered;
    }

    private static List<String> extractTwoWordPhrases(
            List<String> terms) {

        if (terms == null
                || terms.size() < 2) {
            return Collections.emptyList();
        }

        List<String> phrases = new ArrayList<>();

        for (int index = 0;
             index < terms.size() - 1;
             index++) {

            String first = terms.get(index);
            String second = terms.get(index + 1);

            if (first.isEmpty() || second.isEmpty()) {
                continue;
            }

            phrases.add(first + " " + second);
        }

        return phrases;
    }

    
    private static void expandSynonyms(
            List<String> terms,
            Set<String> repositoryVocabulary,
            Map<String, List<String>> synonyms,
            Set<String> expandedTerms) {

        if (terms == null
                || terms.isEmpty()
                || synonyms == null
                || synonyms.isEmpty()) {
            return;
        }

        for (String term : terms) {

            if (term == null || term.isEmpty()) {
                continue;
            }

            List<String> candidates =
                    findSynonyms(term, synonyms);

            for (String candidate : candidates) {

                String normalizedCandidate =
                        TextNormalizer.normalize(candidate);

                if (normalizedCandidate.isEmpty()) {
                    continue;
                }

                if (!isAllowedByRepositoryVocabulary(
                        normalizedCandidate,
                        repositoryVocabulary)) {
                    continue;
                }

                expandedTerms.add(normalizedCandidate);
            }
        }
    }

   
    private static List<String> findSynonyms(
            String normalizedTerm,
            Map<String, List<String>> synonyms) {

        List<String> result =
                new ArrayList<>();

        for (Map.Entry<String, List<String>> entry
                : synonyms.entrySet()) {

            String normalizedKey =
                    TextNormalizer.normalize(entry.getKey());

            if (!normalizedTerm.equals(normalizedKey)) {
                continue;
            }

            List<String> values =
                    entry.getValue();

            if (values == null) {
                continue;
            }

            result.addAll(values);
        }

        return result;
    }


    private static boolean isAllowedByRepositoryVocabulary(
            String normalizedCandidate,
            Set<String> repositoryVocabulary) {

        if (repositoryVocabulary == null
                || repositoryVocabulary.isEmpty()) {
            return true;
        }

        Set<String> normalizedVocabulary =
                normalizeVocabulary(repositoryVocabulary);

        String[] candidateTerms =
                normalizedCandidate.split("\\s+");

        for (String candidateTerm : candidateTerms) {

            if (candidateTerm.isEmpty()) {
                continue;
            }

            if (!normalizedVocabulary.contains(candidateTerm)) {
                return false;
            }
        }

        return true;
    }

    
    private static Set<String> normalizeVocabulary(
            Set<String> repositoryVocabulary) {

        Set<String> normalized =
                new LinkedHashSet<>();

        for (String term : repositoryVocabulary) {

            if (term == null || term.isEmpty()) {
                continue;
            }

            String normalizedTerm =
                    TextNormalizer.normalize(term);

            if (!normalizedTerm.isEmpty()) {
                normalized.add(normalizedTerm);
            }
        }

        return normalized;
    }
}