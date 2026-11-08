package com.example.jarvis.Flights;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One flight itinerary as the combined planner sees it. The airline side maps its own model onto this.
 *
 * @param totalPrice   price for all travellers, in {@code currency}
 * @param totalMinutes door-to-door flying time including layovers, used to compare against driving
 * @param summary      human-readable description (can be Gemini's write-up from the airline side)
 */
public record FlightOption(String carrier, List<String> flightNumbers,
                           LocalDateTime departure, LocalDateTime arrival,
                           int stops, int totalMinutes,
                           double totalPrice, String currency,
                           String summary, String bookingUrl) {}
