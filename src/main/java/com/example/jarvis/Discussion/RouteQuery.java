package com.example.jarvis.Discussion;

public record RouteQuery(String routeName, String origin, String destination) {
    public String searchTerm() {
        if (routeName != null && !routeName.isBlank()) {
            return routeName.trim() + " road trip";
        }
        return (origin + " to " + destination + " road trip").trim();
    }
}

