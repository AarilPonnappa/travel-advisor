package com.example.jarvis.Personalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelPreferencesTest {

    @Test
    void emptyResolvesToNeutralDefaults() {
        TravelPreferences p = TravelPreferences.empty().resolved();
        assertEquals(3, p.tourismPreference());
        assertEquals(3, p.budget());
        assertFalse(p.travellingWithPets());
        assertFalse(p.travellingWithKids());
        assertEquals(TravelPreferences.DEFAULT_KM_PER_DAY, p.drivingKmPerDay());
        assertTrue(p.vibes().isEmpty());
    }

    @Test
    void explicitValuesOverrideInferredOnesAndVibesMerge() {
        TravelPreferences inferred = new TravelPreferences(
                Map.of(Vibe.SCENIC, 5, Vibe.FOODIE, 2), 2, 4, true, null, null);
        TravelPreferences explicit = new TravelPreferences(
                Map.of(Vibe.FOODIE, 5), null, 1, null, true, 250);

        TravelPreferences merged = inferred.overriddenBy(explicit).resolved();

        assertEquals(Map.of(Vibe.SCENIC, 5, Vibe.FOODIE, 5), merged.vibes());
        assertEquals(2, merged.tourismPreference());
        assertEquals(1, merged.budget());
        assertTrue(merged.travellingWithPets());
        assertTrue(merged.travellingWithKids());
        assertEquals(250, merged.drivingKmPerDay());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        TravelPreferences p = new TravelPreferences(Map.of(Vibe.ADVENTURE, 9), 0, 7, null, null, 10).resolved();
        assertEquals(5, p.vibes().get(Vibe.ADVENTURE));
        assertEquals(1, p.tourismPreference());
        assertEquals(5, p.budget());
        assertEquals(50, p.drivingKmPerDay());
    }
}
