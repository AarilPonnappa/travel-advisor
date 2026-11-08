package com.example.jarvis.Personalization;

import com.example.jarvis.RatingSystem.ScoreWeights;

import java.util.Map;

public record UserProfile (
        ScoreWeights weights,
        Map<Vibe, Integer> vibes,
        int targetTouristiness,
        int budget,
        boolean travellingWithPets,
        boolean travellingWithKids,
        int travelMonth,
        int drivingKmPerDay
) {}
