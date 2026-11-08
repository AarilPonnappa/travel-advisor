package com.example.jarvis.dto;

import java.math.BigDecimal;

public record FlightQuote(String carrier, int durationMinutes, int stops, BigDecimal totalPrice, String departTime, String arriveTime, String bookingUrl, String source) {
}
