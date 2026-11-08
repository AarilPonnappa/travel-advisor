package com.example.jarvis.model;

import java.util.List;

public enum ActivityType {
    ADVENTURE("Adventure", true,
            List.of("climbing", "rafting", "kayaking", "zipline", "mountain biking", "paragliding", "adventure park"),
            "Rock climbing, rafting, ziplines and other adventure sports"),
    SCENIC("Scenic", true,
            List.of("hiking trail", "viewpoint", "lake", "national park", "scenic overlook", "botanical garden"),
            "Hikes, viewpoints, lakes and trails"),
    HISTORICAL("Historical", false,
            List.of("museum", "historic site", "cathedral", "castle", "heritage walk", "art gallery"),
            "Museums, historic sites and cultural landmarks"),
    RELAXING("Relaxing & casual", false,
            List.of("old town walk", "spa massage", "market", "park", "cafe district", "cultural center"),
            "Easy walks, city exploring, massages and cultural visits"),
    PARTY("Party", false,
            List.of("nightclub", "live music venue", "cocktail bar", "concert hall", "rooftop bar"),
            "Nightclubs, concerts and bars");

    private final String label;
    // outdoor types get weather warnings
    private final boolean outdoor;
    private final List<String> searchTerms;
    private final String description;

    ActivityType(String label, boolean outdoor, List<String> searchTerms, String description) {
        this.label = label;
        this.outdoor = outdoor;
        this.searchTerms = searchTerms;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOutdoor() {
        return outdoor;
    }

    public List<String> getSearchTerms() {
        return searchTerms;
    }

    public String getDescription() {
        return description;
    }
}
