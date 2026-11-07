package com.example.jarvis.Personalization;

import java.util.List;

public record PersonalizedRoute(String name, double total, double baseScore,
                                double fitScore, List<String> whyPicked) {}
