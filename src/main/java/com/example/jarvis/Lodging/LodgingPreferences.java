package com.example.jarvis.Lodging;

/** What the lodging search should favour. Kept in this package so lodging never depends on personalization. */
public record LodgingPreferences(boolean petsRequired, double independentBonus) {

    public static final double DEFAULT_INDEPENDENT_BONUS = 8;

    public static LodgingPreferences defaults(boolean petsRequired) {
        return new LodgingPreferences(petsRequired, DEFAULT_INDEPENDENT_BONUS);
    }
}

