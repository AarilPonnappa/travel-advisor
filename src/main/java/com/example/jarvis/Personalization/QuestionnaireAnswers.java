package com.example.jarvis.Personalization;

import java.time.LocalDateTime;
import java.util.Map;

public record QuestionnaireAnswers (
        String destination,
        LocalDateTime departure,
        Map<Vibe, Integer> vibes,      // each 1-5
        int tourismPreference,         // 1 = hidden gems ... 5 = famous attractions
        int budget,                    // 1 = shoestring ... 5 = luxury
        boolean travellingWithPets,
        boolean travellingWithKids,
        int drivingKmPerDay) {

}
