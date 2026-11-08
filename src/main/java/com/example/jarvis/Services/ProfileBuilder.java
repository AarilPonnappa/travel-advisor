package com.example.jarvis.Services;

import com.example.jarvis.Personalization.QuestionnaireAnswers;
import com.example.jarvis.Personalization.UserProfile;
import com.example.jarvis.Personalization.Vibe;
import com.example.jarvis.RatingSystem.ScoreWeights;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ProfileBuilder {

    private final ScoreWeights base;

    public ProfileBuilder(ScoreWeights base) {
        this.base = base;
    }

    public UserProfile build(QuestionnaireAnswers a) {
        Map<Vibe, Integer> v = a.vibes();

        double scenic = base.scenic() * factor(rating(v, Vibe.SCENIC));
        double foodLodging = base.foodLodging() * factor(rating(v, Vibe.FOODIE));
        double character = base.character() * factor(rating(v, Vibe.ADVENTURE));
        double social = base.social() * factor(rating(v, Vibe.CULTURAL));
        // Relaxed travellers care less about speed, so the rating is inverted
        double efficiency = base.efficiency() * factor(6 - rating(v, Vibe.RELAXED));
        double practicality = base.practicality();

        // Famous-sights lovers lean on community picks; hidden-gem seekers lean on local character
        social *= factor(a.tourismPreference());
        character *= factor(6 - a.tourismPreference());

        // Pets raise the importance of practical concerns (rest stops, access, safety)
        if (a.travellingWithPets()) practicality *= 1.3;

        // Kids mean more stops and shorter stretches: practicality matters more, and long drives hurt more
        if (a.travellingWithKids()) {
            practicality *= 1.3;
            efficiency *= 1.15;
        }

        // Short daily drives make every detour hurt more
        if (a.drivingKmPerDay() <= 200) efficiency *= 1.15;

        ScoreWeights personalized = new ScoreWeights(
                scenic, character, social, foodLodging, efficiency, practicality).normalized();

        return new UserProfile(
                personalized,
                v,
                (a.tourismPreference() - 1) * 25,
                a.budget(),
                a.travellingWithPets(),
                a.travellingWithKids(),
                a.drivingKmPerDay(),
                a.departure() != null ? a.departure().getMonthValue() : 0);
    }

    /**
     * 1 -> 0.70, 3 -> 1.00, 5 -> 1.30
     */
    private static double factor(int rating) {
        return 1 + 0.15 * (rating - 3);
    }

    /**
     * Unanswered vibes count as neutral (3).
     */
    private static int rating(Map<Vibe, Integer> vibes, Vibe vibe) {
        return vibes.getOrDefault(vibe, 3);
    }
}