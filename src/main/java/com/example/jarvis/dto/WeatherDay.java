package com.example.jarvis.dto;

import java.time.LocalDate;

public record WeatherDay(LocalDate date, Integer code, Double tempMaxC, Double tempMinC, Double precipitationMm, Double windKmh, String summary, String source) {
}
