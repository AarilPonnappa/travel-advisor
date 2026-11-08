package com.example.jarvis.Travel;

import com.example.jarvis.Flights.FlightOption;
import com.example.jarvis.Personalization.TravelPreferences;
import com.example.jarvis.TripPlanner.TripPlan;
import java.util.List;

/**
 * Everything the front end needs to show both ways of getting there.
 *
 * @param preferences the preferences actually used (explicit + inferred + defaults)
 * @param roadTrip    null if road trips were not requested or no route could be planned
 * @param flights     empty if flights were not requested or no airline provider is connected
 * @param notes       human-readable notes about what was inferred or what was unavailable
 */
public record TravelOptions(TravelPreferences preferences, TripPlan roadTrip,
                            List<FlightOption> flights, List<String> notes) {}
