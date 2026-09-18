package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.Collections;
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

        Map<String, Integer> frequencies =
                new LinkedHashMap<>();

        for (SourceFile chunk : chunks) {

            if (chunk == null) {
                continue;
            }

            addTermFrequencies(
                    frequencies,
                    chunk.title
            );

            addTermFrequencies(
                    frequencies,
                    chunk.path
            );

            addTermFrequencies(
                    frequencies,
                    chunk.content
            );
        }

        return Collections.unmodifiableMap(frequencies);
    }

   
    private static void addTerms(
            Set<String> vocabulary,
            String text) {

        String normalized =
                TextNormalizer.normalize(text);

        if (normalized.isEmpty()) {
            return;
        }

        String[] terms =
                normalized.split(" ");

        for (String term : terms) {

            if (!term.isEmpty()) {
                vocabulary.add(term);
            }
        }
    }


    private static void addTermFrequencies(
            Map<String, Integer> frequencies,
            String text) {

        String normalized =
                TextNormalizer.normalize(text);

        if (normalized.isEmpty()) {
            return;
        }

        String[] terms =
                normalized.split(" ");

        for (String term : terms) {

            if (!term.isEmpty()) {
                frequencies.merge(
                        term,
                        1,
                        Integer::sum
                );
            }
        }
    }
}