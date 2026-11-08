package com.example.jarvis.Gemini;

import com.example.jarvis.service.GeminiService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The road-trip side's view of Gemini. It does not talk to Gemini itself: every call goes through the
 * shared {@link GeminiService} using the planner key (GEMINI_PLANNER_API_KEY), so the whole app has one
 * Gemini connection, one set of keys and one place to change models.
 *
 * Failures are turned into {@link Optional#empty()} so road-trip planning degrades to defaults instead
 * of failing the request.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final String SYSTEM_PROMPT =
            "You are the road-trip planning assistant inside a travel planner. "
                    + "Always answer with a single valid JSON value and nothing else.";

    private final GeminiService gemini;
    private final ObjectMapper mapper;

    public GeminiClient(GeminiService gemini, ObjectMapper mapper) {
        this.gemini = gemini;
        this.mapper = mapper;
    }

    public boolean isAvailable() {
        return gemini.plannerConfigured();
    }

    /** Sends the prompt and returns the parsed JSON reply, or empty if Gemini is unavailable or the reply is unusable. */
    public Optional<JsonNode> generateJson(String prompt) {
        if (!isAvailable()) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(stripCodeFence(gemini.generatePlan(SYSTEM_PROMPT, prompt))));
        } catch (Exception e) {
            log.warn("Gemini road-trip call failed: {}", e.getMessage());
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
