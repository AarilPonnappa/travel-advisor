package com.example.jarvis.RatingSystem;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "scoring.weights")
public record ScoreWeights (
        @DefaultValue("0.35") double scenic,
        @DefaultValue("0.20") double character,
        @DefaultValue("0.15") double social,
        @DefaultValue("0.15") double foodLodging,
        @DefaultValue("0.10") double efficiency,
        @DefaultValue("0.05") double practicality)
{
    public ScoreWeights normalized () {
        double sum = scenic + character + social + foodLodging + efficiency + practicality;
        if (sum <= 0) throw new IllegalArgumentException("Weights must sum to > 0");
        return new ScoreWeights(scenic / sum, character / sum, social / sum,
                foodLodging / sum, efficiency / sum, practicality / sum);
    }
}
