package com.agenticqa.orchestrator;

import java.util.Locale;

/**
 * Token-free intent gate: decides whether the chat request is a test-case
 * generation task BEFORE any AI call happens.
 *
 * Rationale: AgenticQA exists to prove that RAG saves tokens on test-case
 * generation. A question like "which agent?" must never be forwarded to the
 * AI - that would burn tokens without producing tests.
 */
public final class IntentGate {

    /** Strong signals: any of these means "this is about generating tests". */
    private static final String[] STRONG_SIGNALS = {
        "test case", "testcases", "test-case",
        "scenario", "scenarios",
        "gherkin", "cucumber", "bdd",
        ".feature", "feature file",
        "step definition", "stepdefinition", "step def", "stepdef",
        "acceptance criteria", "acceptance test",
        "test suite", "test plan",
        "unit test", "integration test", "e2e test", "end-to-end test",
        "tests for", "test for", "test the", "test coverage"
    };

    /** Action words that turn a bare "test(s)" mention into a request. */
    private static final String[] ACTION_WORDS = {
        "write", "generate", "create", "add", "produce", "build", "make",
        "cover", "extend", "update", "implement", "define", "design", "automate"
    };

    private IntentGate() {
    }

    /**
     * @return {@code null} when the prompt looks like a test-generation task,
     *         otherwise a human-readable reason to refuse it.
     */
    public static String reasonToRefuse(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).trim();
        if (p.isBlank()) {
            return "the prompt is empty.";
        }

        for (String signal : STRONG_SIGNALS) {
            if (p.contains(signal)) {
                return null;
            }
        }

        boolean mentionsTests = p.contains("test") || p.contains("tests") || p.contains("testing");
        boolean hasAction = false;
        for (String action : ACTION_WORDS) {
            if (p.contains(action)) {
                hasAction = true;
                break;
            }
        }
        if (mentionsTests && hasAction) {
            return null;
        }

        return "this does not look like a test-case generation request "
            + "(no test / scenario / feature signals found).";
    }
}
