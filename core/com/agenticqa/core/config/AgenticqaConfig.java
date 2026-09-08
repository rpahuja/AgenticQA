package com.agenticqa.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Central configuration for the Java components.
 *
 * Supports MULTIPLE AI providers - each with its own endpoint, key and
 * models - plus routing rules the orchestrator uses to decide which
 * provider/model handles a task (see ModelRouter).
 *
 * Per-provider API key precedence:
 *   environment variable <ID>_API_KEY  >  agenticqa.llm.<id>.apiKey
 */
public class AgenticqaConfig {

    public List<Provider> providers = new ArrayList<>();
    public List<RoutingRule> routingRules = new ArrayList<>();
    public String defaultProvider = "deepseek";
    public String defaultModel = "deepseek-chat";
    public boolean ragEnabled = true;
    public int topK = 6;
    public int contextTokenBudget = 6000;
    public String repoPath = "";

    /** One AI provider with its endpoint, key and available models. */
    public static final class Provider {
        public String id = "";
        public String baseUrl = "";
        public String apiKey = "";
        public List<String> models = new ArrayList<>();
    }

    /**
     * One routing rule: when ANY keyword appears in the task, the task is
     * routed to provider:model. The first matching rule wins.
     */
    public static final class RoutingRule {
        public String id = "";
        public List<String> keywords = new ArrayList<>();
        public String providerId = "";
        public String model = "";

        public boolean matches(String prompt) {
            String t = prompt.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (!k.isEmpty() && t.contains(k)) return true;
            }
            return false;
        }
    }

    /** Find a provider by id, or null. */
    public Provider findProvider(String id) {
        for (Provider p : providers) {
            if (p.id.equalsIgnoreCase(id)) return p;
        }
        return null;
    }

    /** Load from the given file; missing keys keep the defaults. */
    public static AgenticqaConfig load(Path file) {
        AgenticqaConfig cfg = new AgenticqaConfig();
        if (file == null || !Files.isRegularFile(file)) {
            // No config file: keep a single default provider so routing and
            // dry runs work out of the box (real calls still need a key).
            Provider p = new Provider();
            p.id = "deepseek";
            p.baseUrl = "https://api.deepseek.com";
            cfg.providers.add(p);
            return cfg;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
            return cfg;
        }

        // Providers: agenticqa.llm.providers=deepseek,openai + one block each.
        for (String rawId : props.getProperty("agenticqa.llm.providers", "deepseek").split(",")) {
            String id = rawId.trim();
            if (id.isEmpty()) continue;
            Provider p = new Provider();
            p.id = id;
            p.baseUrl = props.getProperty("agenticqa.llm." + id + ".baseUrl", "");
            p.apiKey = firstNonBlank(
                System.getenv(id.toUpperCase(Locale.ROOT) + "_API_KEY"),
                props.getProperty("agenticqa.llm." + id + ".apiKey", ""));
            for (String m : props.getProperty("agenticqa.llm." + id + ".models", "").split(",")) {
                String model = m.trim();
                if (!model.isEmpty()) p.models.add(model);
            }
            cfg.providers.add(p);
        }

        // Routing: default plus keyword rules ("kw1|kw2 -> provider:model").
        String[] def = props.getProperty("agenticqa.routing.default", ":").split(":", 2);
        cfg.defaultProvider = def[0].trim();
        cfg.defaultModel = def.length > 1 ? def[1].trim() : "";
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("agenticqa.routing.rule.")) continue;
            String[] halves = props.getProperty(key, "").split("->", 2);
            if (halves.length < 2) continue;
            String[] target = halves[1].split(":", 2);
            if (target.length < 2) continue;
            RoutingRule rule = new RoutingRule();
            rule.id = key.substring("agenticqa.routing.rule.".length());
            for (String kw : halves[0].split("\\|")) {
                String k = kw.trim().toLowerCase(Locale.ROOT);
                if (!k.isEmpty()) rule.keywords.add(k);
            }
            rule.providerId = target[0].trim();
            rule.model = target[1].trim();
            cfg.routingRules.add(rule);
        }

        // RAG behaviour. The on/off switch lives in agenticqa.properties
        // (agenticqa.rag.enabled); the --rag CLI flag overrides it for
        // standalone jar runs.
        cfg.ragEnabled = Boolean.parseBoolean(
            props.getProperty("agenticqa.rag.enabled", String.valueOf(cfg.ragEnabled)));
        cfg.topK = Integer.parseInt(
            props.getProperty("agenticqa.rag.topK", String.valueOf(cfg.topK)));
        cfg.contextTokenBudget = Integer.parseInt(
            props.getProperty("agenticqa.rag.contextTokenBudget", String.valueOf(cfg.contextTokenBudget)));
        cfg.repoPath = props.getProperty("agenticqa.orchestrator.repoPath", cfg.repoPath).trim();
        return cfg;
    }

    /** Where the config file lives: a "config" folder next to the running jar. */
    public static Path defaultConfigFile() {
        try {
            Path jar = Path.of(AgenticqaConfig.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            return jar.getParent().resolve("config").resolve("agenticqa.properties");
        } catch (Exception e) {
            return Path.of("config", "agenticqa.properties");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }
}
