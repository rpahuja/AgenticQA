package com.agenticqa.orchestrator;

import com.agenticqa.core.config.AgenticqaConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point of the AgenticQA orchestrator.
 *
 * Usage:
 *   java -jar agenticqa-orchestrator.jar --repo <repoPath> [options]
 *
 * Options:
 *   --prompt "..."     feature description (default: read from stdin)
 *   --provider ID      force one AI provider (bypasses the routing rules)
 *   --model NAME       force one model of that provider
 *   --rag on|off       inject retrieved context (default from config)
 *   --top-k N          existing test files to inject (default from config)
 *   --config FILE      config file (default: config/agenticqa.properties next to the jar)
 *   --dry-run          skip the AI call; show scan + retrieval + route only
 *
 * Output protocol (parsed by the chat participant):
 *   [agenticqa] ...              progress lines
 *   AGENTICQA_WROTE <path>       one line per generated file
 *   AGENTICQA_TOKENS prompt=N completion=N total=N
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        String prompt = opts.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (prompt.isBlank()) {
            System.err.println("Missing --prompt. Pass it as an argument or through stdin.");
            System.exit(1);
            return;
        }

        // Token-free intent gate: only test-case generation tasks reach the AI.
        // A question like "which agent?" must not burn tokens in a token-saving POC.
        String refuse = IntentGate.reasonToRefuse(prompt);
        if (refuse != null) {
            System.out.println("[agenticqa] SKIPPED: " + refuse);
            System.out.println("[agenticqa] AgenticQA only generates Cucumber test cases. "
                + "Try: \"Write test cases for <feature>\".");
            System.out.println("[agenticqa] No AI tokens were consumed.");
            return;
        }

        // Config: file < environment < CLI args.
        Path configFile = opts.containsKey("config")
            ? Path.of(opts.get("config"))
            : AgenticqaConfig.defaultConfigFile();
        AgenticqaConfig config = AgenticqaConfig.load(configFile);
        if (opts.containsKey("rag")) config.ragEnabled = !"off".equalsIgnoreCase(opts.get("rag"));
        if (opts.containsKey("top-k")) config.topK = Integer.parseInt(opts.get("top-k"));

        // Project: --repo argument, or agenticqa.orchestrator.repoPath in the config file.
        String repo = opts.get("repo");
        if (repo == null || repo.isBlank()) repo = config.repoPath;
        if (repo == null || repo.isBlank()) {
            System.err.println("ERROR: no project configured. Pass --repo <path> "
                + "or set agenticqa.orchestrator.repoPath in agenticqa.properties.");
            System.exit(1);
            return;
        }

        // --provider/--model force one provider+model; otherwise the routing
        // rules inside the orchestrator decide based on the task.
        Orchestrator.Result result;
        try {
            result = new Orchestrator(config)
                .run(Path.of(repo), prompt, opts.get("provider"), opts.get("model"),
                     opts.containsKey("dry-run"));
        } catch (Exception e) {
            // Keep failures human-readable in the chat reply - no stack trace.
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Hint: point @agenticQA at a Maven + Cucumber test repository "
                + "with --repo <path> in the prompt.");
            System.exit(1);
            return;
        }

        for (Path p : result.writtenFiles) {
            System.out.println("AGENTICQA_WROTE " + p.toAbsolutePath());
        }
        System.out.println("AGENTICQA_TOKENS prompt=" + result.promptTokens
            + " completion=" + result.completionTokens
            + " total=" + (result.promptTokens + result.completionTokens));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "");
            }
        }
        return opts;
    }
}
