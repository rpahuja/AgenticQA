package com.agenticqa.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


public final class QueryAnalysis {

    private final String originalQuery;
    private final String normalizedQuery;
    private final List<String> terms;
    private final List<String> phrases;
    private final List<String> expandedTerms;

    public QueryAnalysis(
            String originalQuery,
            String normalizedQuery,
            List<String> terms,
            List<String> phrases,
            List<String> expandedTerms) {

        this.originalQuery = originalQuery;
        this.normalizedQuery = normalizedQuery;

        this.terms = Collections.unmodifiableList(
                new ArrayList<>(terms));

        this.phrases = Collections.unmodifiableList(
                new ArrayList<>(phrases));

        this.expandedTerms = Collections.unmodifiableList(
                new ArrayList<>(expandedTerms));
    }

   
    public String originalQuery() {
        return originalQuery;
    }

    public String normalizedQuery() {
        return normalizedQuery;
    }

   
    public List<String> terms() {
        return terms;
    }

    
    public List<String> phrases() {
        return phrases;
    }

    
    public List<String> expandedTerms() {
        return expandedTerms;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof QueryAnalysis)) {
            return false;
        }

        QueryAnalysis that = (QueryAnalysis) other;

        return Objects.equals(
                    originalQuery,
                    that.originalQuery)
                && Objects.equals(
                    normalizedQuery,
                    that.normalizedQuery)
                && Objects.equals(
                    terms,
                    that.terms)
                && Objects.equals(
                    phrases,
                    that.phrases)
                && Objects.equals(
                    expandedTerms,
                    that.expandedTerms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                originalQuery,
                normalizedQuery,
                terms,
                phrases,
                expandedTerms);
    }

    @Override
    public String toString() {
        return "QueryAnalysis{" +
                "originalQuery='" + originalQuery + '\'' +
                ", normalizedQuery='" + normalizedQuery + '\'' +
                ", terms=" + terms +
                ", phrases=" + phrases +
                ", expandedTerms=" + expandedTerms +
                '}';
    }
}