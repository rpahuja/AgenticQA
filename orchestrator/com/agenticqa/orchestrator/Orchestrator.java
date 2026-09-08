package com.agenticqa.orchestrator;

import com.agenticqa.core.config.AgenticqaConfig;
import com.agenticqa.core.llm.LlmClient;
import com.agenticqa.core.llm.ModelRouter;
import com.agenticqa.core.model.SourceFile;
import com.agenticqa.rag.RagEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The orchestration flow behind @agenticQA:
 *
 *   discover repository layout
 *     -> ask the RAG for the focused context (single entry point, zero tokens)
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
            // Single RAG entry point: the orchestrator asks for the context
            // and never touches the RAG internals. The retrieval itself costs
            // zero LLM tokens - only the pieces that fit the budget reach the
            // prompt.
            RagEngine.RagContext rag = RagEngine.buildContext(
                layout.featureDir, layout.testSourceRoot, prompt,
                config.topK, config.contextTokenBudget);
            context = rag.chunks;

            log("RAG: knowledge base = " + rag.corpusFiles + " files -> " + rag.corpusChunks
                + " chunks (~" + rag.corpusTokens + " tokens).");
            log("RAG: retrieved " + rag.chunks.size() + " chunks (~" + rag.injectedTokens
                + " tokens) = " + pct(rag.injectedTokens, rag.corpusTokens)
                + "% of the knowledge base - "
                + pct(rag.corpusTokens - rag.injectedTokens, rag.corpusTokens)
                + "% NOT injected into the prompt.");
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

        if (dryRun) {
            int n = context == null ? 0 : context.size();
            int t = context == null ? 0 : totalTokens(context);
            log("Dry run: would call " + provider.id + "/" + model + " with " + n
                + " context chunks (~" + t + " tokens).");
            log("Dry run: no AI tokens consumed, no files written.");
            return new Result(List.of(), 0, 0);
        }

        LlmClient llm = new LlmClient(provider.id, provider.apiKey, provider.baseUrl);
        String reply = llm.complete(Generator.buildPrompt(prompt, layout, context), model);
        List<Path> written = Generator.writeGenerated(reply, prompt, layout);

        return new Result(written, llm.lastPromptTokens, llm.lastCompletionTokens);
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
