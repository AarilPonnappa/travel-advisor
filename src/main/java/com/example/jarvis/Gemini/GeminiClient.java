package com.example.jarvis.Gemini;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin wrapper around the Gemini generateContent REST endpoint, used by the road-trip side.
 *
 * If the airline side already has its own Gemini client, this class is the one place to swap it in:
 * everything on the road-trip side only calls {@link #generateJson(String)}.
 *
 * Configure with {@code gemini.api-key} (or the GEMINI_API_KEY environment variable). Without a key the
 * client reports itself unavailable and callers fall back to defaults, so the app still runs.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final ObjectMapper mapper;
    private final RestTemplate rest;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiClient(ObjectMapper mapper,
                        @Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model:gemini-2.5-flash}") String model,
                        @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.rest = new RestTemplate(factory);
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Sends the prompt asking for a JSON-only answer and returns the parsed JSON.
     * Returns empty if Gemini is not configured, the call fails, or the reply is not valid JSON;
     * callers are expected to fall back to something sensible.
     */
    public Optional<JsonNode> generateJson(String prompt) {
        if (!isAvailable()) return Optional.empty();
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.4));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);

            String response = rest.postForObject(
                    baseUrl + "/models/" + model + ":generateContent",
                    new HttpEntity<>(mapper.writeValueAsString(body), headers),
                    String.class);

            JsonNode text = mapper.readTree(response)
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isString()) {
                log.warn("Gemini returned no text content");
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(stripCodeFence(text.stringValue())));
        } catch (Exception e) {
            log.warn("Gemini call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Models sometimes wrap JSON in ```json fences even when asked not to. */
    static String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return t.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return t;
    }
}
