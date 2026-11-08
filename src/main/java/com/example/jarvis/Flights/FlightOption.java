package com.example.jarvis.Flights;

import java.math.BigDecimal;

/**
 * One flight itinerary as the combined planner returns it. Mirrors the airline side's FlightQuote,
 * plus the currency so the client doesn't have to know the app's configured currency.
 *
 * @param totalPrice      price for all travellers
 * @param durationMinutes flying time including layovers, used to compare against driving
 * @param source          where the fare came from, e.g. LIVE or DUFFEL_TEST
 */
public record FlightOption(String carrier, int durationMinutes, int stops,
                           BigDecimal totalPrice, String currency,
                           String departTime, String arriveTime,
                           String bookingUrl, String source) {}
