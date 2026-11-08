package com.example.jarvis.Flights;

import java.time.LocalDate;

/**
 * What the combined planner hands to the airline side.
 *
 * @param prompt     the user's original free-text request, so the airline side can pass it to Gemini too
 * @param maxBudget  1-5 budget level inferred for the trip (same scale as TravelPreferences.budget), may be null
 * @param returnDate null for one-way
 */
public record FlightSearchRequest(String origin, String destination,
                                  LocalDate departureDate, LocalDate returnDate,
                                  int travellers, Integer maxBudget, String prompt) {}
