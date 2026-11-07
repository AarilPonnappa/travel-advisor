package com.example.jarvis.Personalization;

import java.util.Map;
import java.util.Set;

public record RouteProfile (
    int touristiness,              // 0 = hidden gem, 100 = packed with famous sights
    int costLevel,                 // 1-5, typical trip cost
    int petFriendliness,           // 0-100
    int kidFriendliness,           // 0-100
    Map<Vibe, Integer> vibeStrength, // 0-100 per vibe
    Set<Integer> closedMonths,     // 1-12, route impassable
    Set<Integer> riskyMonths) {

        // 1-12, possible closures or hazards
        public RouteProfile {
            vibeStrength = vibeStrength == null ? Map.of() : vibeStrength;
            closedMonths = closedMonths == null ? Set.of() : closedMonths;
            riskyMonths = riskyMonths == null ? Set.of() : riskyMonths;
        }
}




