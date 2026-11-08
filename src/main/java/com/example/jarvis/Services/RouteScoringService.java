package com.example.jarvis.Services;

import com.example.jarvis.RatingSystem.RouteCandidate;
import com.example.jarvis.RatingSystem.ScoreWeights;
import com.example.jarvis.RatingSystem.ScoredRoute;
import com.example.jarvis.RatingSystem.SubScores;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteScoringService {
    private final ScoreWeights defaults;

    public RouteScoringService(ScoreWeights defaults) {
        this.defaults = defaults;
    }

    public List<ScoredRoute> rank(List<RouteCandidate> candidates, ScoreWeights override) {
        ScoreWeights w = (override != null ? override : defaults).normalized();
        return candidates.stream()
                .map(c -> score(c, w))
                .sorted(Comparator.comparingDouble(ScoredRoute::total).reversed())
                .toList();
    }

    private ScoredRoute score(RouteCandidate c, ScoreWeights w) {
        SubScores s = c.subScores();
        Map<String, Double> contributions = createContributions(w, s);

        double total = contributions.values().stream().mapToDouble(Double::doubleValue).sum();

        List<String> why = contributions.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(2)
                .map(e -> "Strong on " + e.getKey())
                .toList();

        return new ScoredRoute(c.name(), Math.round(total * 10) / 10.0, s, why);
    }

    private static @NonNull Map<String, Double> createContributions(ScoreWeights w, SubScores s) {
        Map<String, Double> contributions = new LinkedHashMap<>();
        contributions.put("scenic beauty", w.scenic() * s.scenic());
        contributions.put("local character", w.character() * s.character());
        contributions.put("community recommendations", w.social() * s.social());
        contributions.put("food and lodging", w.foodLodging() * s.foodLodging());
        contributions.put("travel time", w.efficiency() * s.efficiency());
        contributions.put("practicality", w.practicality() * s.practicality());
        return contributions;
    }
}
