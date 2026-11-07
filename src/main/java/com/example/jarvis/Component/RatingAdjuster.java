package com.example.jarvis.Component;

import org.springframework.stereotype.Component;

@Component
public class RatingAdjuster {
    private static final double CONFIDENCE = 50;

    public double bayesian(double rating, int reviewCount, double regionalAvg) {
        return (reviewCount * rating + CONFIDENCE * regionalAvg) / (reviewCount + CONFIDENCE);
    }

    public double toScore(double adjustedRating, double regionalAvg) {
        double delta = adjustedRating - regionalAvg;
        return Math.max(0, Math.min(100, 50 + delta * 50));
    }
}

