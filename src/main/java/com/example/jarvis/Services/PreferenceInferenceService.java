package com.example.jarvis.Services;

import com.example.jarvis.Gemini.GeminiClient;
import com.example.jarvis.Personalization.TravelPreferences;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.example.jarvis.Personalization.Vibe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Works out the traveller's preferences without a questionnaire.
 *
 * Priority: explicit values from the client > values Gemini infers from the free-text prompt > defaults.
 * The result is not yet resolved, so callers can still tell which fields are unknown; ProfileBuilder
 * fills the remaining gaps.
 */
@Service
public class PreferenceInferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceInferenceService.class);

    private final GeminiClient gemini;
    private final ObjectMapper mapper;

    public PreferenceInferenceService(GeminiClient gemini, ObjectMapper mapper) {
        this.gemini = gemini;
        this.mapper = mapper;
    }

    public TravelPreferences infer(String prompt, TravelPreferences explicit) {
        TravelPreferences inferred = TravelPreferences.empty();
        if (prompt != null && !prompt.isBlank()) {
            inferred = gemini.generateJson(buildPrompt(prompt))
                    .map(this::parse)
                    .orElse(inferred);
        }
        return inferred.overriddenBy(explicit);
    }

    private TravelPreferences parse(JsonNode node) {
        try {
            return mapper.treeToValue(node, TravelPreferences.class);
        } catch (Exception e) {
            log.warn("Could not read preferences from Gemini reply: {}", e.getMessage());
            return TravelPreferences.empty();
        }
    }

    static String buildPrompt(String userRequest) {
        String vibes = Arrays.stream(Vibe.values()).map(Enum::name).collect(Collectors.joining(", "));
        return """
                You help a travel planner understand a traveller from a single free-text request.
                Infer their preferences and reply with ONLY a JSON object of this shape:
                {
                  "vibes": { "<VIBE>": <1-5> },
                  "tourismPreference": <1-5>,
                  "budget": <1-5>,
                  "travellingWithPets": <true|false>,
                  "travellingWithKids": <true|false>,
                  "drivingKmPerDay": <integer>
                }
                Allowed vibe keys: %s.
                tourismPreference: 1 = hidden gems, 5 = famous attractions.
                budget: 1 = shoestring, 5 = luxury.
                Use null for anything the request does not give you a reasonable signal for,
                and only include vibes the request actually hints at.

                Traveller request:
                \"\"\"%s\"\"\"
                """.formatted(vibes, userRequest);
    }
}
