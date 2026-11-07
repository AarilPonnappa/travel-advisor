package com.example.jarvis.Component;

import org.springframework.stereotype.Component;

@Component
public class EfficiencyScorer {
    private static final double FREE_OVERHEAD = 0.30;

    public double score(double routeMinutes, double fastestMinutes) {
        double overhead = (routeMinutes - fastestMinutes) / fastestMinutes;
        if (overhead <= 0) return 100;
        if (overhead <= FREE_OVERHEAD) {
            return 100 - 40 * (overhead / FREE_OVERHEAD);
        }
        return Math.max(0 , 60 - 200 * (overhead - FREE_OVERHEAD));
    }
}
