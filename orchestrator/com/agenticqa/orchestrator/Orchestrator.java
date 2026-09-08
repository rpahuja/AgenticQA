package com.agenticqa.orchestrator;

import com.agenticqa.core.config.AgenticqaConfig;
import com.agenticqa.core.llm.LlmClient;
import com.agenticqa.core.llm.ModelRouter;
import com.agenticqa.core.model.SourceFile;
import com.agenticqa.rag.Chunker;
import com.agenticqa.rag.Retriever;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The orchestration flow behind @agenticQA:
 *
 *   discover repository layout
 *     -> collect existing test artifacts
 *     -> retrieve the relevant ones (rag component, zero tokens)
 *     -> route the task to a provider + model (routing rules)
 *     -> one LLM call with the focused context
 *     -> write the generated tests into the discovered folders
 */
public class Orchestrator {

    private final AgenticqaConfig config;

    public Orchestrator(AgenticqaConfig config) {
        this.config = config;
    }

    public Result run(Path repo, String prompt) throws Exception {
        return run(repo, prompt, null, null, false);
    }

    /**
     * Same run, optionally with a provider/model forced from the CLI
     * (bypasses the routing rules).
     */
    public Result run(Path repo, String prompt, String forcedProviderId, String forcedModel,
                      boolean dryRun) throws Exception {
        log("Repository       : " + repo);

        RepoLayout layout = RepoScanner.scan(repo);
        log("Feature dir      : " + relative(layout, layout.featureDir));
        log("Test source root : " + relative(layout, layout.testSourceRoot));
        log("Steps package    : " + layout.stepsPackage);
        log("Runner package   : " + layout.runnerPackage);

        List<SourceFile> context = null;
        if (config.ragEnabled) {
            // Knowledge base: every existing test artifact, chunked into
            // scenarios / methods.
            List<SourceFile> files = collectTestArtifacts(layout);
            List<SourceFile> chunks = Chunker.chunk(files);
            int corpusTokens = totalTokens(files);

            // Retrieval runs in code - zero LLM tokens. Only the chunks that
            // fit the budget reach the prompt.
            context = Retriever.retrieve(chunks, prompt, config.topK, config.contextTokenBudget);
            int contextTokens = totalTokens(context);

            log("RAG: knowledge base = " + files.size() + " files -> " + chunks.size()
                + " chunks (~" + corpusTokens + " tokens).");
            log("RAG: retrieved " + context.size() + " chunks (~" + contextTokens + " tokens) = "
                + pct(contextTokens, corpusTokens) + "% of the knowledge base - "
                + pct(corpusTokens - contextTokens, corpusTokens) + "% NOT injected into the prompt.");
        } else {
            log("RAG disabled - the model gets no existing tests.");
        }

        return runWithModel(prompt, layout, context, forcedProviderId, forcedModel, dryRun);
    }

    /** Pick the provider/model for this task and finish the run. */
    private Result runWithModel(String prompt, RepoLayout layout, List<SourceFile> context,
                                String forcedProviderId, String forcedModel,
                                boolean dryRun) throws Exception {
        ModelRouter.Route route = forcedProviderId != null
            ? new ModelRouter.Route(forcedProviderId, forcedModel, "cli")
            : ModelRouter.route(config, prompt);

        AgenticqaConfig.Provider provider = config.findProvider(route.providerId);
        if (provider == null) {
            throw new IOException("Routed to unknown provider '" + route.providerId + "'.");
        }
        String model = route.model;
        if (model == null || model.isEmpty()) {
            model = provider.models.isEmpty() ? "" : provider.models.get(0);
        }
        log("Model: " + provider.id + "/" + model + " (routed via " + route.source + ")");

        // Deterministic repo surface: existing step definitions + TestWorld state.
        // Retrieval gives similar scenarios; the surface gives the repository's
        // interface, so the model reuses steps and fields instead of inventing them.
        SurfaceExtractor.Surface surface = SurfaceExtractor.extract(layout);
        log("RAG: repo surface = " + surface.steps + " existing steps + " + surface.fields
            + " state fields (~" + surface.tokenEstimate + " tokens).");

        if (dryRun) {
            int n = context == null ? 0 : context.size();
            int t = context == null ? 0 : totalTokens(context);
            log("Dry run: would call " + provider.id + "/" + model + " with " + n
                + " context chunks (~" + t + " tokens) + repo surface (~" + surface.tokenEstimate + " tokens).");
            log("Dry run: no AI tokens consumed, no files written.");
            return new Result(List.of(), 0, 0);
        }

        LlmClient llm = new LlmClient(provider.id, provider.apiKey, provider.baseUrl);
        String reply = llm.complete(Generator.buildPrompt(prompt, layout, context, surface.text), model);
        List<Path> written = Generator.writeGenerated(reply, prompt, layout);

        return new Result(written, llm.lastPromptTokens, llm.lastCompletionTokens);
    }

    /** Walk the discovered folders and read every existing test artifact. */
    private List<SourceFile> collectTestArtifacts(RepoLayout layout) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        if (Files.isDirectory(layout.featureDir)) {
            try (Stream<Path> s = Files.walk(layout.featureDir)) {
                s.filter(p -> p.toString().endsWith(".feature"))
                 .sorted()
                 .forEach(p -> out.add(read(p)));
            }
        }
        if (Files.isDirectory(layout.testSourceRoot)) {
            try (Stream<Path> s = Files.walk(layout.testSourceRoot)) {
                s.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(p -> out.add(read(p)));
            }
        }
        return out;
    }

    private SourceFile read(Path p) {
        try {
            return new SourceFile(p.toString(), new String(Files.readAllBytes(p)));
        } catch (IOException e) {
            return new SourceFile(p.toString(), "");
        }
    }

    /** Estimated tokens (1 token ~ 4 chars) of a set of artifacts. */
    private int totalTokens(List<SourceFile> artifacts) {
        int total = 0;
        for (SourceFile f : artifacts) {
            total += f.content.isEmpty() ? 0 : Math.max(1, f.content.length() / 4);
        }
        return total;
    }

    private int pct(int part, int whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    private void log(String msg) {
        System.out.println("[agenticqa] " + msg);
    }

    private String relative(RepoLayout layout, Path p) {
        try {
            return layout.repo.relativize(p).toString();
        } catch (Exception e) {
            return p.toString();
        }
    }

    /** What one orchestration run produced. */
    public static final class Result {
        public final List<Path> writtenFiles;
        public final int promptTokens;
        public final int completionTokens;

        public Result(List<Path> writtenFiles, int promptTokens, int completionTokens) {
            this.writtenFiles = writtenFiles;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }
}
