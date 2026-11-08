package com.example.jarvis.TripPlanner;

import com.example.jarvis.Discussion.DiscussionLink;
import com.example.jarvis.Lodging.StretchPick;

import java.util.List;

public record PlannedRoute(String name, double total, double baseScore, double fitScore,
                               List<String> whyPicked,
                               boolean lodgingAvailable, double lodgingCoverage,
                               List<StretchPick> lodgingByStretch,
                               List<DiscussionLink> discussions) {}
