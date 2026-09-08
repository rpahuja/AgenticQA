package com.agenticqa.core.llm;

import com.agenticqa.core.config.AgenticqaConfig;

/**
 * Decides which AI provider and which model handle a task.
 *
 * The first routing rule whose keywords appear in the prompt wins;
 * when nothing matches, the configured default provider:model is used.
 * Rules live in agenticqa.properties, so the decision is data, not code.
 */
public final class ModelRouter {

    private ModelRouter() {}

    public static Route route(AgenticqaConfig config, String prompt) {
        for (AgenticqaConfig.RoutingRule rule : config.routingRules) {
            if (rule.matches(prompt)) {
                return new Route(rule.providerId, rule.model, rule.id);
            }
        }
        return new Route(config.defaultProvider, config.defaultModel, "default");
    }

    /** The provider/model chosen for one task. */
    public static final class Route {
        public final String providerId;
        public final String model;
        public final String source;   // matching rule id, or "default" - for logging

        public Route(String providerId, String model, String source) {
            this.providerId = providerId;
            this.model = model;
            this.source = source;
        }
    }
}
