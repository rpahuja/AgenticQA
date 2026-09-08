package com.agenticqa.core.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal OpenAI-compatible chat client, bound to ONE provider.
 * The provider may expose several models - the orchestrator decides which
 * model handles a task and passes it to complete().
 */
public class LlmClient {

    private final String providerId;
    private final String apiKey;
    private final String baseUrl;

    public int lastPromptTokens = 0;
    public int lastCompletionTokens = 0;

    public LlmClient(String providerId, String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No API key for provider '" + providerId
                + "'. Fill agenticqa.llm." + providerId + ".apiKey in agenticqa.properties "
                + "or set the environment variable " + providerId.toUpperCase(Locale.ROOT) + "_API_KEY.");
        }
        this.providerId = providerId;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** One chat-completion call for the given model. Returns the assistant content. */
    public String complete(String prompt, String model) throws Exception {
        String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":"
            + json(prompt) + "}],\"temperature\":0}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("Provider '" + providerId + "' returned " + response.statusCode()
                + ": " + response.body());
        }

        String jsonText = response.body();
        String content = extractString(jsonText, "content");
        if (content == null) {
            throw new IOException("No \"content\" in API response: " + jsonText);
        }

        lastPromptTokens = extractInt(jsonText, "prompt_tokens", -1);
        lastCompletionTokens = extractInt(jsonText, "completion_tokens", -1);
        if (lastPromptTokens < 0) lastPromptTokens = estimateTokens(body);
        if (lastCompletionTokens < 0) lastCompletionTokens = estimateTokens(content);
        return content;
    }

    private static String json(String s) {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    /**
     * Find the first "key":"string" in the JSON and return the unescaped value.
     * Linear character scan instead of a regex - the regex engine recurses per
     * character on long model replies and overflows the stack (seen live).
     */
    private static String extractString(String jsonText, String key) {
        String needle = "\"" + key + "\"";
        int i = jsonText.indexOf(needle);
        if (i < 0) return null;
        i = skipToColon(jsonText, i + needle.length());
        if (i < 0 || i >= jsonText.length() || jsonText.charAt(i) != '"') return null;
        i++;

        StringBuilder out = new StringBuilder();
        boolean closed = false;
        while (i < jsonText.length()) {
            char c = jsonText.charAt(i);
            if (c == '\\' && i + 1 < jsonText.length()) {
                char n = jsonText.charAt(i + 1);
                if (n == 'n')      { out.append('\n'); i += 2; continue; }
                if (n == 't')      { out.append('\t'); i += 2; continue; }
                if (n == 'r')      { out.append('\r'); i += 2; continue; }
                if (n == 'u' && i + 5 < jsonText.length()) {
                    try {
                        out.append((char) Integer.parseInt(jsonText.substring(i + 2, i + 6), 16));
                        i += 6;
                        continue;
                    } catch (RuntimeException ignored) {
                        /* fall through and append the backslash literally */
                    }
                }
                out.append(n);
                i += 2;
                continue;
            }
            if (c == '"') {
                closed = true;
                break;
            }
            out.append(c);
            i++;
        }
        return closed ? out.toString() : null;
    }

    /** Find the first "key":123 integer, or the fallback. */
    private static int extractInt(String jsonText, String key, int fallback) {
        String needle = "\"" + key + "\"";
        int i = jsonText.indexOf(needle);
        if (i < 0) return fallback;
        i = skipToColon(jsonText, i + needle.length());
        int start = i;
        while (i < jsonText.length() && Character.isDigit(jsonText.charAt(i))) i++;
        if (i == start) return fallback;
        try {
            return Integer.parseInt(jsonText.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Advance past whitespace, the ':' and more whitespace; returns the value index or -1. */
    private static int skipToColon(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= text.length() || text.charAt(i) != ':') return -1;
        i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i < text.length() ? i : -1;
    }

    private static int estimateTokens(String text) {
        return text.isEmpty() ? 0 : Math.max(1, text.length() / 4);
    }
}
