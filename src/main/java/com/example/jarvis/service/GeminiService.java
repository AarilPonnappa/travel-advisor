package com.example.jarvis.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

// talks to gemini. the planner and the chatbot use two different api keys on purpose
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final RestClient http;
    private final String baseUrl;
    private final String plannerKey;
    private final String plannerModel;
    private final String chatKey;
    private final String chatModel;

    public GeminiService(RestClient.Builder builder,
                         @Value("${jarvis.url.gemini}") String baseUrl,
                         @Value("${jarvis.gemini.planner-key:}") String plannerKey,
                         @Value("${jarvis.gemini.planner-model}") String plannerModel,
                         @Value("${jarvis.gemini.chat-key:}") String chatKey,
                         @Value("${jarvis.gemini.chat-model}") String chatModel) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.plannerKey = plannerKey;
        this.plannerModel = plannerModel;
        this.chatKey = chatKey;
        this.chatModel = chatModel;
    }

    public boolean plannerConfigured() {
        return plannerKey != null && !plannerKey.isBlank();
    }

    public boolean chatConfigured() {
        return chatKey != null && !chatKey.isBlank();
    }

    public String plannerModel() {
        return plannerModel;
    }

    // planner key, json output
    public String generatePlan(String systemPrompt, String userPrompt) {
        List<Map<String, Object>> contents = List.of(turn("user", userPrompt));
        return call(plannerKey, plannerModel, systemPrompt, contents, true);
    }

    // chat key, plain text. history is [role, text] pairs, oldest first, ending with the user's question
    public String chat(String systemPrompt, List<String[]> history) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (String[] h : history) {
            String role = "model".equals(h[0]) ? "model" : "user";
            // gemini wants alternating turns, so glue repeated roles together
            if (!contents.isEmpty() && role.equals(contents.get(contents.size() - 1).get("role"))) {
                Map<String, Object> last = contents.remove(contents.size() - 1);
                @SuppressWarnings("unchecked")
                String prev = (String) ((List<Map<String, Object>>) last.get("parts")).get(0).get("text");
                contents.add(turn(role, prev + "\n\n" + h[1]));
            } else {
                contents.add(turn(role, h[1]));
            }
        }
        // a request may not end on a model turn
        if (contents.isEmpty() || !"user".equals(contents.get(contents.size() - 1).get("role"))) {
            throw new IllegalArgumentException("chat history must end with a user message");
        }
        return call(chatKey, chatModel, systemPrompt, contents, false);
    }

    private String call(String key, String model, String systemPrompt, List<Map<String, Object>> contents, boolean json) {
        if (key == null || key.isBlank()) throw new GeminiException("Gemini API key is not configured");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", contents);
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("maxOutputTokens", json ? 24000 : 2048);
        if (json) gen.put("responseMimeType", "application/json");
        body.put("generationConfig", gen);
        try {
            JsonNode root = JsonSupport.parse(http.post().uri(baseUrl + "/models/" + model + ":generateContent")
                    .header("x-goog-api-key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class));
            return extractText(root);
        } catch (RestClientResponseException e) {
            log.warn("gemini returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GeminiException("Gemini request failed (" + e.getStatusCode().value() + ")");
        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException("Gemini request failed: " + e.getMessage());
        }
    }

    static String extractText(JsonNode root) {
        if (root == null) throw new GeminiException("empty response from Gemini");
        JsonNode cand = root.path("candidates").path(0);
        if (cand.isMissingNode()) {
            String reason = root.path("promptFeedback").path("blockReason").asText("no candidates");
            throw new GeminiException("Gemini returned nothing (" + reason + ")");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : cand.path("content").path("parts")) {
            // skip thought summaries if the model sends them
            if (part.path("thought").asBoolean(false)) continue;
            sb.append(part.path("text").asText(""));
        }
        if (sb.isEmpty()) throw new GeminiException("Gemini response had no text (" + cand.path("finishReason").asText("") + ")");
        return sb.toString();
    }

    private static Map<String, Object> turn(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("parts", List.of(Map.of("text", text)));
        return m;
    }

    public static class GeminiException extends RuntimeException {
        public GeminiException(String message) {
            super(message);
        }
    }
}
