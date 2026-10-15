package com.agenticqa.rag;

import com.agenticqa.core.model.SourceFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

                return buildContext(
                                featureDir,
                                testSourceRoot,
                                prompt,
                                topK,
                                tokenBudget,
                                false);
        }

        /**
         * Builds the RAG context using:
         * 1. Structured feature chunks
         * 2. Structured Java method chunks
         * 3. Text normalization
         * 4. Repository vocabulary
         * 5. Query analysis and expansion
         * 6. BM25 ranking
         * 7. Top-K and token-budget enforcement
         */
        public static RagContext buildContext(
                        Path featureDir,
                        Path testSourceRoot,
                        String prompt,
                        int topK,
                        int tokenBudget,
                        boolean debug) throws IOException {

                // ------------------------------------------------------------
                // 1. Build structured chunks
                // ------------------------------------------------------------

                FeatureChunker.Result featureResult = FeatureChunker.chunkDirectory(featureDir);

                JavaChunker.Result javaResult = JavaChunker.chunkDirectory(testSourceRoot);

                List<SourceFile> allChunks = new ArrayList<>(
                                featureResult.chunks.size()
                                                + javaResult.chunks.size());

                allChunks.addAll(featureResult.chunks);
                allChunks.addAll(javaResult.chunks);

                // ------------------------------------------------------------
                // 2. Build normalized retrieval representation
                // ------------------------------------------------------------

                Map<SourceFile, String> normalizedTexts = new LinkedHashMap<>();

                for (SourceFile chunk : allChunks) {

                        normalizedTexts.put(
                                        chunk,
                                        TextNormalizer.normalize(
                                                        chunk.title + " " + chunk.content));
                }

                // ------------------------------------------------------------
                // 3. Build repository vocabulary
                // ------------------------------------------------------------

                Set<String> vocabulary = RepositoryVocabulary.extract(allChunks);

                // ------------------------------------------------------------
                // 4. Corpus statistics
                // ------------------------------------------------------------

                int corpusFiles = featureResult.fileCount
                                + javaResult.fileCount;

                int corpusTokens = estimateTokens(allChunks);

                // ------------------------------------------------------------
                // 5. Analyze and expand the query
                // ------------------------------------------------------------

                QueryAnalysis queryAnalysis = QueryAnalyzer.analyze(
                                prompt,
                                vocabulary,
                                QuerySynonyms.defaults());

                // ------------------------------------------------------------
                // 6. BM25 ranking using analyzed query
                // ------------------------------------------------------------

                List<BM25Ranker.RankedChunk> ranked = BM25Ranker.rank(
                                queryAnalysis,
                                allChunks,
                                normalizedTexts);

                // ------------------------------------------------------------
                // 7. Apply top-K and token budget
                // ------------------------------------------------------------

                List<SourceFile> rankedChunks = new ArrayList<>();

                for (BM25Ranker.RankedChunk rankedChunk : ranked) {
                        rankedChunks.add(rankedChunk.chunk());
                }

                List<SourceFile> diversified = ChunkSelector.select(
                                rankedChunks,
                                normalizedTexts,
                                topK,
                                0.80,
                                2);

                List<SourceFile> selected = selectWithinBudget(
                                diversified,
                                topK,
                                tokenBudget);

                int injectedTokens = estimateTokens(selected);

                // ------------------------------------------------------------
                // 8. Optional diagnostics
                // ------------------------------------------------------------

                if (debug) {

                        System.out.println();
                        System.out.println("=== QUERY ANALYSIS ===");

                        System.out.println(
                                        "Original query: "
                                                        + queryAnalysis.originalQuery());

                        System.out.println(
                                        "Normalized query: "
                                                        + queryAnalysis.normalizedQuery());

                        System.out.println(
                                        "Terms: "
                                                        + queryAnalysis.terms());

                        System.out.println(
                                        "Phrases: "
                                                        + queryAnalysis.phrases());

                        System.out.println(
                                        "Expanded terms: "
                                                        + queryAnalysis.expandedTerms());

                        System.out.println(
                                        "=== END QUERY ANALYSIS ===");

                        System.out.println();
                        System.out.println("=== NORMALIZATION DEBUG ===");

                        for (SourceFile chunk : allChunks) {

                                String normalized = normalizedTexts.get(chunk);

                                System.out.println();
                                System.out.println(
                                                "File: " + chunk.path);

                                System.out.println(
                                                "Title: " + chunk.title);

                                System.out.println("Original:");
                                System.out.println(chunk.content);

                                System.out.println("Normalized:");
                                System.out.println(normalized);
                        }

                        System.out.println();
                        System.out.println("Repository vocabulary:");
                        System.out.println(vocabulary);

                        System.out.println(
                                        "=== END NORMALIZATION DEBUG ===");
                }

                // ------------------------------------------------------------
                // 9. Return immutable RAG context
                // ------------------------------------------------------------

                return new RagContext(
                                selected,
                                corpusFiles,
                                allChunks.size(),
                                corpusTokens,
                                injectedTokens,
                                normalizedTexts,
                                vocabulary);
        }


        /**
         * Runs the golden-set evaluation against the real retrieval pipeline.
         * Expected IDs must be independently curated using chunkId(path, title).
         */
        public static EvaluationReport evaluate(
                        Path featureDir,
                        Path testSourceRoot,
                        List<EvaluationCase> goldenSet,
                        int topK,
                        int tokenBudget) throws IOException {

                if (goldenSet == null) {
                        throw new IllegalArgumentException("goldenSet must not be null");
                }

                List<EvaluationResult> results = new ArrayList<>();

                for (EvaluationCase testCase : goldenSet) {
                        RagContext context = buildContext(
                                        featureDir,
                                        testSourceRoot,
                                        testCase.query,
                                        topK,
                                        tokenBudget);

                        List<String> retrievedIds = new ArrayList<>();
                        for (SourceFile chunk : context.chunks) {
                                retrievedIds.add(RetrievalMetrics.chunkId(chunk));
                        }

                        RetrievalMetrics.Metrics metrics = RetrievalMetrics.calculate(
                                testCase.expectedRelevantChunkIds,
                                retrievedIds,
                                topK,
                                context.injectedTokens,
                                tokenBudget,
                                context.corpusTokens);

                        results.add(new EvaluationResult(testCase.query, retrievedIds, metrics));
                }

                return EvaluationReport.from(results);
        }

        private static List<SourceFile> selectWithinBudget(
                        List<SourceFile> rankedChunks,
                        int topK,
                        int tokenBudget) {

                if (rankedChunks == null
                                || rankedChunks.isEmpty()
                                || topK <= 0
                                || tokenBudget <= 0) {

                        return Collections.emptyList();
                }

                List<SourceFile> selected = new ArrayList<>();

                int usedTokens = 0;

                for (SourceFile chunk : rankedChunks) {

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


        public static final class EvaluationCase {
                public final String query;
                public final Set<String> expectedRelevantChunkIds;

                public EvaluationCase(String query, Set<String> expectedRelevantChunkIds) {
                        if (query == null || query.trim().isEmpty()) {
                                throw new IllegalArgumentException("query must not be blank");
                        }
                        if (expectedRelevantChunkIds == null) {
                                throw new IllegalArgumentException("expectedRelevantChunkIds must not be null");
                        }
                        this.query = query;
                        this.expectedRelevantChunkIds = Collections.unmodifiableSet(
                                        new LinkedHashSet<>(expectedRelevantChunkIds));
                }
        }

        public static final class EvaluationResult {
                public final String query;
                public final List<String> retrievedChunkIds;
                public final RetrievalMetrics.Metrics metrics;

                private EvaluationResult(String query, List<String> retrievedChunkIds,
                                RetrievalMetrics.Metrics metrics) {
                        this.query = query;
                        this.retrievedChunkIds = Collections.unmodifiableList(
                                        new ArrayList<>(retrievedChunkIds));
                        this.metrics = metrics;
                }
        }

        public static final class EvaluationReport {
                public final List<EvaluationResult> results;
                public final double meanPrecisionAtK;
                public final double meanRecallAtK;
                public final double meanReciprocalRank;
                public final double meanTokenBudgetUsagePercent;
                public final double meanCorpusInjectionPercent;
                public final int tokenBudgetViolations;

                private EvaluationReport(List<EvaluationResult> results) {
                        this.results = Collections.unmodifiableList(new ArrayList<>(results));
                        double p = 0.0, r = 0.0, mrr = 0.0, usage = 0.0, injection = 0.0;
                        int violations = 0;
                        for (EvaluationResult result : results) {
                                p += result.metrics.precisionAtK;
                                r += result.metrics.recallAtK;
                                mrr += result.metrics.reciprocalRank;
                                usage += result.metrics.tokenBudgetUsagePercent;
                                injection += result.metrics.corpusInjectionPercent;
                                if (!result.metrics.withinTokenBudget) {
                                        violations++;
                                }
                        }
                        int n = results.size();
                        this.meanPrecisionAtK = n == 0 ? 0.0 : p / n;
                        this.meanRecallAtK = n == 0 ? 0.0 : r / n;
                        this.meanReciprocalRank = n == 0 ? 0.0 : mrr / n;
                        this.meanTokenBudgetUsagePercent = n == 0 ? 0.0 : usage / n;
                        this.meanCorpusInjectionPercent = n == 0 ? 0.0 : injection / n;
                        this.tokenBudgetViolations = violations;
                }

                private static EvaluationReport from(List<EvaluationResult> results) {
                        return new EvaluationReport(results);
                }

                public String toText() {
                        StringBuilder out = new StringBuilder();
                        out.append("=== RAG EVALUATION REPORT ===\n");
                        out.append("Queries evaluated: ").append(results.size()).append('\n');
                        out.append(String.format(java.util.Locale.ROOT,
                                        "Mean Precision@K: %.4f%nMean Recall@K: %.4f%nMRR: %.4f%n",
                                        meanPrecisionAtK, meanRecallAtK, meanReciprocalRank));
                        out.append(String.format(java.util.Locale.ROOT,
                                        "Mean token-budget usage: %.2f%%%nMean corpus injection: %.2f%%%n",
                                        meanTokenBudgetUsagePercent, meanCorpusInjectionPercent));
                        out.append("Token-budget violations: ").append(tokenBudgetViolations).append('\n');
                        for (EvaluationResult result : results) {
                                out.append("\nQuery: ").append(result.query).append('\n');
                                out.append(String.format(java.util.Locale.ROOT,
                                                "  Precision@K=%.4f Recall@K=%.4f MRR=%.4f Tokens=%d/%d%n",
                                                result.metrics.precisionAtK,
                                                result.metrics.recallAtK,
                                                result.metrics.reciprocalRank,
                                                result.metrics.injectedTokens,
                                                result.metrics.tokenBudget));
                                out.append("  Retrieved chunk IDs: ")
                                                .append(result.retrievedChunkIds).append('\n');
                        }
                        return out.toString();
                }

                @Override
                public String toString() {
                        return toText();
                }
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

                        this.normalizedTexts = Collections.unmodifiableMap(
                                        new LinkedHashMap<>(
                                                        normalizedTexts));

                        this.vocabulary = Collections.unmodifiableSet(
                                        new LinkedHashSet<>(
                                                        vocabulary));
                }
        }
}