package com.example.jarvis.model;

public enum StayType {
    HOTEL("Hotel"),
    HOME("Airbnb-style home / apartment"),
    ANY("Hotels and homes");

    private final String label;

    StayType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
