package com.example.jarvis.model;

public enum CabinClass {
    ECONOMY("Economy", "economy", 1.0),
    PREMIUM_ECONOMY("Premium economy", "premium_economy", 1.6),
    BUSINESS("Business", "business", 3.2),
    FIRST("First", "first", 5.0);

    private final String label;
    private final String apiValue;
    // rough multiplier used when we have to estimate a fare
    private final double priceFactor;

    CabinClass(String label, String apiValue, double priceFactor) {
        this.label = label;
        this.apiValue = apiValue;
        this.priceFactor = priceFactor;
    }

    public String getLabel() {
        return label;
    }

    public String getApiValue() {
        return apiValue;
    }

    public double getPriceFactor() {
        return priceFactor;
    }
}
