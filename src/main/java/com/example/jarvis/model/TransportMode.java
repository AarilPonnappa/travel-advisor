package com.example.jarvis.model;

public enum TransportMode {
    FLIGHT("Flight"),
    TRAIN("Train"),
    BUS("Bus"),
    FERRY("Ferry"),
    DRIVE("Drive");

    private final String label;

    TransportMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
