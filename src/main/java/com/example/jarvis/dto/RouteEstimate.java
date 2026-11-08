package com.example.jarvis.dto;

import java.math.BigDecimal;
import java.util.List;

public record RouteEstimate(int durationMinutes, double distanceKm, BigDecimal farePerPerson, List<String> vehicleTypes, String summary, String departTime, String arriveTime, int transfers, String source) {
}
