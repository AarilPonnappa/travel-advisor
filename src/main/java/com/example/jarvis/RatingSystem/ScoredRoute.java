package com.example.jarvis.RatingSystem;

import java.util.List;

public record ScoredRoute (String name, double total,
                           SubScores subScores, List<String> whyPicked){}
