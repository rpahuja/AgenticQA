package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChunkSelector {

    private ChunkSelector() {
    }
    public static List<SourceFile> select(
            List<SourceFile> rankedChunks,
            Map<SourceFile, String> normalizedTexts,
            int topK,
            double similarityThreshold,
            int maxPerSource) {

        if (rankedChunks == null || rankedChunks.isEmpty() || topK <= 0) {
            return List.of();
        }

        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be between 0.0 and 1.0"
            );
        }

        if (maxPerSource <= 0) {
            throw new IllegalArgumentException(
                    "maxPerSource must be greater than zero"
            );
        }

        Map<SourceFile, String> texts =
                normalizedTexts == null
                        ? Map.of()
                        : normalizedTexts;

        List<SourceFile> selected = new ArrayList<>();
        List<SourceFile> deferred = new ArrayList<>();

        Map<String, Integer> sourceCounts = new LinkedHashMap<>();

        for (SourceFile candidate : rankedChunks) {

            if (candidate == null) {
                continue;
            }

            if (isDuplicate(candidate, selected, texts, similarityThreshold)) {
                continue;
            }

            String source = sourceKey(candidate);

            int currentSourceCount =
                    sourceCounts.getOrDefault(source, 0);

            if (currentSourceCount < maxPerSource) {
                selected.add(candidate);
                sourceCounts.put(source, currentSourceCount + 1);

                if (selected.size() >= topK) {
                    return List.copyOf(selected);
                }
            } else {
             
                deferred.add(candidate);
            }
        }

        for (SourceFile candidate : deferred) {

            if (selected.size() >= topK) {
                break;
            }

            if (isDuplicate(candidate, selected, texts, similarityThreshold)) {
                continue;
            }

            selected.add(candidate);
        }

        return List.copyOf(selected);
    }


    private static boolean isDuplicate(
            SourceFile candidate,
            List<SourceFile> selected,
            Map<SourceFile, String> normalizedTexts,
            double similarityThreshold) {

        String candidateText =
                normalizedText(candidate, normalizedTexts);

        for (SourceFile existing : selected) {

            String existingText =
                    normalizedText(existing, normalizedTexts);

            if (candidateText.equals(existingText)) {
                return true;
            }

            double similarity =
                    jaccardSimilarity(candidateText, existingText);

            if (similarity >= similarityThreshold) {
                return true;
            }
        }

        return false;
    }

    private static String normalizedText(
            SourceFile chunk,
            Map<SourceFile, String> normalizedTexts) {

        String normalized = normalizedTexts.get(chunk);

        if (normalized != null) {
            return normalized;
        }

        String title = chunk.title == null ? "" : chunk.title;
        String content = chunk.content == null ? "" : chunk.content;

        return TextNormalizer.normalize(title + " " + content);
    }


    private static double jaccardSimilarity(
            String first,
            String second) {

        Set<String> firstTerms = terms(first);
        Set<String> secondTerms = terms(second);

        if (firstTerms.isEmpty() && secondTerms.isEmpty()) {
            return 1.0;
        }

        if (firstTerms.isEmpty() || secondTerms.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(firstTerms);
        intersection.retainAll(secondTerms);

        Set<String> union = new HashSet<>(firstTerms);
        union.addAll(secondTerms);

        return (double) intersection.size() / union.size();
    }

  
    private static Set<String> terms(String text) {

        Set<String> terms = new HashSet<>();

        if (text == null || text.isBlank()) {
            return terms;
        }

        String normalized = TextNormalizer.normalize(text);

        if (normalized.isBlank()) {
            return terms;
        }

        for (String term : normalized.split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }

        return terms;
    }

    private static String sourceKey(SourceFile chunk) {

        if (chunk.path == null) {
            return "";
        }

        return chunk.path;
    }
}