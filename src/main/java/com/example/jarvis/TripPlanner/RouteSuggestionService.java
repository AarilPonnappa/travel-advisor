package com.example.jarvis.TripPlanner;

import com.example.jarvis.Component.EfficiencyScorer;
import com.example.jarvis.Gemini.GeminiClient;
import com.example.jarvis.Lodging.LatLon;
import com.example.jarvis.Personalization.RouteProfile;
import com.example.jarvis.Personalization.Vibe;
import com.example.jarvis.RatingSystem.SubScores;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Produces candidate road-trip routes for an origin/destination pair, so the client no longer has to
 * supply routes itself. Gemini proposes the routes (waypoints, seeded sub-scores, route profile);
 * the routing server supplies real road geometry and drive time, which also drives the efficiency score.
 */
@Service
public class RouteSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(RouteSuggestionService.class);
    private static final int DEFAULT_ROUTE_COUNT = 3;

    /** Shape Gemini is asked to return for each route. */
    record SuggestedRoute(String name, String summary, List<Waypoint> waypoints,
                          SubScores subScores, RouteProfile profile) {}

    record Waypoint(String name, double lat, double lon) {}

    private final GeminiClient gemini;
    private final RoutingClient routing;
    private final EfficiencyScorer efficiency;
    private final ObjectMapper mapper;

    public RouteSuggestionService(GeminiClient gemini, RoutingClient routing,
                                  EfficiencyScorer efficiency, ObjectMapper mapper) {
        this.gemini = gemini;
        this.routing = routing;
        this.efficiency = efficiency;
        this.mapper = mapper;
    }

    /** Empty when Gemini is unavailable or returns nothing usable. */
    public List<PlannerRouteInput> suggest(String origin, String destination, LocalDate departure, String userPrompt) {
        List<SuggestedRoute> suggested = gemini.generateJson(buildPrompt(origin, destination, departure, userPrompt))
                .map(this::parse)
                .orElse(List.of());
        if (suggested.isEmpty()) return List.of();

        // Route each suggestion on real roads where possible
        List<Optional<RoutingClient.DrivingRoute>> driven = suggested.stream()
                .map(s -> routing.route(s.waypoints().stream().map(w -> new LatLon(w.lat(), w.lon())).toList()))
                .toList();
        double fastest = driven.stream().flatMap(Optional::stream)
                .mapToDouble(RoutingClient.DrivingRoute::minutes).filter(m -> m > 0).min().orElse(0);

        List<PlannerRouteInput> out = new ArrayList<>();
        for (int i = 0; i < suggested.size(); i++) {
            SuggestedRoute s = suggested.get(i);
            Optional<RoutingClient.DrivingRoute> d = driven.get(i);
            List<LatLon> geometry = d.map(RoutingClient.DrivingRoute::geometry)
                    .orElseGet(() -> s.waypoints().stream().map(w -> new LatLon(w.lat(), w.lon())).toList());
            SubScores scores = s.subScores();
            if (d.isPresent() && fastest > 0) {
                // Replace Gemini's guess with a measured efficiency score relative to the fastest option
                scores = new SubScores(scores.scenic(), scores.character(), scores.social(), scores.foodLodging(),
                        efficiency.score(d.get().minutes(), fastest), scores.practicality());
            }
            out.add(new PlannerRouteInput(s.name(), geometry, scores, s.profile()));
        }
        return out;
    }

    private List<SuggestedRoute> parse(JsonNode root) {
        JsonNode routes = root.has("routes") ? root.get("routes") : root;
        List<SuggestedRoute> out = new ArrayList<>();
        for (JsonNode node : routes) {
            try {
                SuggestedRoute r = mapper.treeToValue(node, SuggestedRoute.class);
                if (r.name() == null || r.waypoints() == null || r.waypoints().size() < 2
                        || r.subScores() == null || r.profile() == null) {
                    log.warn("Skipping incomplete route suggestion: {}", r.name());
                    continue;
                }
                out.add(r);
            } catch (Exception e) {
                log.warn("Skipping unreadable route suggestion: {}", e.getMessage());
            }
        }
        return out;
    }

    static String buildPrompt(String origin, String destination, LocalDate departure, String userPrompt) {
        String vibes = Arrays.stream(Vibe.values()).map(Enum::name).collect(Collectors.joining(", "));
        return """
                Suggest %d distinct road-trip routes by car from "%s" to "%s"%s.
                Make them meaningfully different (e.g. fastest highway, scenic detour, small-town route).
                Reply with ONLY a JSON object of this shape:
                {
                  "routes": [
                    {
                      "name": "<short memorable route name>",
                      "summary": "<one sentence>",
                      "waypoints": [ { "name": "<place>", "lat": <number>, "lon": <number> } ],
                      "subScores": {
                        "scenic": <0-100>, "character": <0-100>, "social": <0-100>,
                        "foodLodging": <0-100>, "efficiency": <0-100>, "practicality": <0-100>
                      },
                      "profile": {
                        "touristiness": <0-100>, "costLevel": <1-5>,
                        "petFriendliness": <0-100>, "kidFriendliness": <0-100>,
                        "vibeStrength": { "<VIBE>": <0-100> },
                        "closedMonths": [<1-12>], "riskyMonths": [<1-12>]
                      }
                    }
                  ]
                }
                Waypoints must start at the origin, end at the destination, and include 2-6 stops in
                between in driving order, with accurate coordinates.
                Allowed vibe keys: %s.
                closedMonths are months the route is impassable; riskyMonths have possible closures or hazards.
                %s
                """.formatted(DEFAULT_ROUTE_COUNT, origin, destination,
                departure != null ? " departing " + departure : "",
                vibes,
                userPrompt != null && !userPrompt.isBlank()
                        ? "The traveller said: \"\"\"" + userPrompt + "\"\"\"" : "");
    }
}
