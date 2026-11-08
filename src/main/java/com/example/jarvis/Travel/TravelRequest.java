package com.example.jarvis.Travel;

import com.example.jarvis.Personalization.TravelPreferences;
import com.example.jarvis.TripPlanner.PlannerRouteInput;
import java.time.LocalDate;
import java.util.List;

/**
 * Single entry point for a trip. Only origin and destination are required.
 *
 * @param prompt      the user's free-text request ("relaxed week with the dog, love good food");
 *                    Gemini uses it to infer preferences and to suggest routes
 * @param preferences optional explicit preferences; anything set here overrides what Gemini infers
 * @param routes      optional hand-picked road-trip routes; when absent, routes are suggested by Gemini
 * @param includeRoadTrip defaults to true
 * @param includeFlights  defaults to true
 */
public record TravelRequest(String origin, String destination,
                            LocalDate departureDate, LocalDate returnDate,
                            Integer travellers, String prompt,
                            TravelPreferences preferences, List<PlannerRouteInput> routes,
                            Boolean includeRoadTrip, Boolean includeFlights) {

    public int travellersOrDefault() {
        return travellers != null && travellers > 0 ? travellers : 1;
    }

    public boolean wantsRoadTrip() {
        return !Boolean.FALSE.equals(includeRoadTrip);
    }

    public boolean wantsFlights() {
        return !Boolean.FALSE.equals(includeFlights);
    }
}
