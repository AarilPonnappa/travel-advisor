package com.example.jarvis.TripPlanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.jarvis.Component.EfficiencyScorer;
import com.example.jarvis.Gemini.GeminiClient;
import com.example.jarvis.Lodging.LatLon;
import com.example.jarvis.Personalization.Vibe;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RouteSuggestionServiceTest {

    private static final String REPLY = """
            {"routes": [
              {"name": "Sea to Sky", "summary": "Coastal",
               "waypoints": [{"name":"Vancouver","lat":49.28,"lon":-123.12},{"name":"Whistler","lat":50.12,"lon":-122.95}],
               "subScores": {"scenic":95,"character":70,"social":80,"foodLodging":60,"efficiency":50,"practicality":70},
               "profile": {"touristiness":80,"costLevel":4,"petFriendliness":60,"kidFriendliness":70,
                           "vibeStrength":{"SCENIC":95,"ADVENTURE":70},"closedMonths":[],"riskyMonths":[1,2]}},
              {"name": "Broken", "waypoints": [{"name":"Only one","lat":1,"lon":1}]}
            ]}
            """;

    @Test
    void parsesGeminiRoutesAndFallsBackToWaypointGeometry() {
        ObjectMapper mapper = JsonMapper.builder().build();
        GeminiClient gemini = new GeminiClient(mapper, "test-key", "m", "http://unused") {
            @Override
            public Optional<JsonNode> generateJson(String prompt) {
                return Optional.of(mapper.readTree(REPLY));
            }
        };
        RoutingClient noRouting = new RoutingClient(mapper, "http://unused") {
            @Override
            public Optional<DrivingRoute> route(List<LatLon> waypoints) {
                return Optional.empty();
            }
        };

        List<PlannerRouteInput> routes = new RouteSuggestionService(gemini, noRouting, new EfficiencyScorer(), mapper)
                .suggest("Vancouver", "Whistler", null, null);

        assertEquals(1, routes.size()); // the incomplete suggestion is dropped
        PlannerRouteInput r = routes.get(0);
        assertEquals("Sea to Sky", r.name());
        assertEquals(List.of(new LatLon(49.28, -123.12), new LatLon(50.12, -122.95)), r.geometry());
        assertEquals(95, r.seededScores().scenic());
        assertEquals(95, r.profile().vibeStrength().get(Vibe.SCENIC));
        assertEquals(java.util.Set.of(1, 2), r.profile().riskyMonths());
    }
}
