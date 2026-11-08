package com.example.jarvis.TripPlanner;
import com.example.jarvis.Personalization.TravelPreferences;

import java.time.LocalDate;
import java.util.List;

/**
 * Road-trip planning input. Only {@code routes} is required: preferences are optional (no questionnaire),
 * and departure is only used for seasonal closures/risks.
 */
public record TripRequest(String origin, String destination, LocalDate departure,
                          TravelPreferences preferences, List<PlannerRouteInput> routes,
                          Integer lodgingRadiusMeters, Integer picksPerStretch) {}
