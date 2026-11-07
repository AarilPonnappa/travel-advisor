package com.example.jarvis.Component;

import com.example.jarvis.Personalization.RouteProfile;
import com.example.jarvis.Personalization.UserProfile;
import com.example.jarvis.Personalization.Vibe;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PersonalizationScorer {
    public record Fit(double total, Map<String, Double> components) {}

    public Fit fit(UserProfile user, RouteProfile route) {
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();

        values.put("vibe", vibeFit(user, route));
        weights.put("vibe", 0.40);

        values.put("tourism", clamp(100 - Math.abs(user.targetTouristiness() - route.touristiness())));
        weights.put("tourism", 0.20);

        // Cheaper than budget is fine; each level over costs 30 points
        int overBudget = Math.max(0, route.costLevel() - user.budget());
        values.put("budget", clamp(100 - 30.0 * overBudget));
        weights.put("budget", 0.25);

        if (user.travellingWithPets()) {
            values.put("pets", clamp(route.petFriendliness()));
            weights.put("pets", 0.15);
        }

        if (user.travellingWithKids()) {
            values.put("kids", clamp(route.kidFriendliness()));
            weights.put("kids", 0.15);
        }

        double weightSum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double total = values.entrySet().stream()
                .mapToDouble(e -> e.getValue() * weights.get(e.getKey()))
                .sum() / weightSum;

        return new Fit(total, values);

    }

    private double vibeFit(UserProfile user, RouteProfile route) {
        double weighted = 0, ratingSum = 0;

        for (Map.Entry<Vibe, Integer> e : user.vibes().entrySet()) {
            weighted += e.getValue() * route.vibeStrength().getOrDefault(e.getKey() , 0);
            ratingSum += e.getValue();
        }
        return ratingSum == 0 ? 50 : weighted / ratingSum;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
