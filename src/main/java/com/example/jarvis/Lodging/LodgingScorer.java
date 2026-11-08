package com.example.jarvis.Lodging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;


@Component
public class LodgingScorer {

    public record Result(double score, List<String> reasons) {}

    private static final Pattern LEADING_DIGIT = Pattern.compile("^(\\d)");
    private static final Set<String> DOG_OK = Set.of("yes", "leashed");
    private static final Set<String> WIFI_OK = Set.of("wlan", "yes", "wired");

    public Result score(Lodging l, double offRouteMeters, LodgingPreferences prefs) {
        Map<String, String> t = l.tags();
        List<String> reasons = new ArrayList<>();
        double score = 50;

        // Official star classification, when mapped
        Matcher m = LEADING_DIGIT.matcher(t.getOrDefault("stars", ""));
        if (m.find()) {
            int stars = Integer.parseInt(m.group(1));
            score += (stars - 3) * 5;
            reasons.add(stars + "-star rated");
        }

        // A listing with contact details is more likely to be real, open, and bookable
        boolean website = t.containsKey("website") || t.containsKey("contact:website");
        boolean phone = t.containsKey("phone") || t.containsKey("contact:phone");
        if (website) score += 4;
        if (phone) score += 4;
        if (website && phone) reasons.add("Has website and phone");

        // Independents fit the "local character" idea; chains usually carry a brand tag
        if (!t.containsKey("brand") && !t.containsKey("operator")) {
            score += prefs.independentBonus();
            reasons.add("Independent");
        }

        if (WIFI_OK.contains(t.getOrDefault("internet_access", ""))) {
            score += 4;
            reasons.add("Wi-Fi");
        }

        if (prefs.petsRequired()) {
            String dog = t.getOrDefault("dog", "");
            if (DOG_OK.contains(dog)) {
                score += 15;
                reasons.add("Allows dogs");
            } else if (dog.isEmpty()) {
                reasons.add("Pet policy unknown, call ahead");
            }
        }

        // Closer to the road is better: lose 6 points per km, capped at 30
        score -= Math.min(30, offRouteMeters / 1000.0 * 6);

        return new Result(Math.max(0, Math.min(100, score)), reasons);
    }
}
