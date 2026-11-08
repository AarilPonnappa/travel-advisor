package com.example.jarvis.Personalization;

import java.util.EnumMap;
import java.util.Map;

/**
 * What we know about the traveller's taste. Replaces the old questionnaire: every field is optional,
 * because nothing is asked up front any more. Values come from (in order of priority) whatever the
 * client sends explicitly, what Gemini infers from the user's free-text prompt, and finally the
 * neutral defaults in {@link #resolved()}.
 */
public record TravelPreferences(
        Map<Vibe, Integer> vibes,      // each 1-5, missing vibes count as neutral (3)
        Integer tourismPreference,     // 1 = hidden gems ... 5 = famous attractions
        Integer budget,                // 1 = shoestring ... 5 = luxury
        Boolean travellingWithPets,
        Boolean travellingWithKids,
        Integer drivingKmPerDay) {

    public static final int DEFAULT_RATING = 3;
    public static final int DEFAULT_KM_PER_DAY = 400;

    public static TravelPreferences empty() {
        return new TravelPreferences(null, null, null, null, null, null);
    }

    /** A copy with every gap filled by a neutral default and every value clamped to its range. */
    public TravelPreferences resolved() {
        Map<Vibe, Integer> v = new EnumMap<>(Vibe.class);
        if (vibes != null) {
            vibes.forEach((vibe, rating) -> {
                if (vibe != null && rating != null) v.put(vibe, clamp(rating, 1, 5));
            });
        }
        return new TravelPreferences(
                Map.copyOf(v),
                tourismPreference != null ? clamp(tourismPreference, 1, 5) : DEFAULT_RATING,
                budget != null ? clamp(budget, 1, 5) : DEFAULT_RATING,
                Boolean.TRUE.equals(travellingWithPets),
                Boolean.TRUE.equals(travellingWithKids),
                drivingKmPerDay != null ? clamp(drivingKmPerDay, 50, 1500) : DEFAULT_KM_PER_DAY);
    }

    /** Fields set on {@code override} win; gaps fall back to this instance. Vibes merge per vibe. */
    public TravelPreferences overriddenBy(TravelPreferences override) {
        if (override == null) return this;
        Map<Vibe, Integer> merged = new EnumMap<>(Vibe.class);
        if (vibes != null) merged.putAll(vibes);
        if (override.vibes() != null) merged.putAll(override.vibes());
        return new TravelPreferences(
                merged.isEmpty() ? null : merged,
                pick(override.tourismPreference(), tourismPreference),
                pick(override.budget(), budget),
                pick(override.travellingWithPets(), travellingWithPets),
                pick(override.travellingWithKids(), travellingWithKids),
                pick(override.drivingKmPerDay(), drivingKmPerDay));
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
