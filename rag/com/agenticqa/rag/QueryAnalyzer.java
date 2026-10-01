package com.agenticqa.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public final class QueryAnalyzer {

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
                extractTwoWordPhrases(normalizedQuery);

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

        LinkedHashSet<String> unique =
                new LinkedHashSet<>();

        for (String part : parts) {
            if (!part.isEmpty()) {
                unique.add(part);
            }
        }

        return new ArrayList<>(unique);
    }

    private static List<String> extractTwoWordPhrases(
            String normalizedQuery) {

        if (normalizedQuery == null
                || normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts =
                normalizedQuery.split("\\s+");

        if (parts.length < 2) {
            return Collections.emptyList();
        }

        List<String> phrases = new ArrayList<>();

        for (int index = 0;
             index < parts.length - 1;
             index++) {

            String first = parts[index];
            String second = parts[index + 1];

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