package com.example.jarvis.Services;

import com.example.jarvis.Component.PersonalizationScorer;
import com.example.jarvis.Personalization.*;
import com.example.jarvis.RatingSystem.ScoredRoute;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;



@Service
public class PersonalizedRankingService {
    private static final double FIT_BLEND = 0.30; //personal fit vs. base score
    private static final double RISKY_SEASON_PENALTY = 10;
    private static final int MIN_PET_FRIENDLINESS = 20;
    private static final int MIN_KID_FRIENDLINESS = 20;

    private static final Map<String, String> REASONS = Map.of (
            "vibe", "Matches your vibe",
            "tourism", "Fits your sightseeing style",
            "budget", "Fits your budget",
            "pets", "Pet-friendly",
            "kids", "Kid-friendly"
    );

    private final ProfileBuilder profileBuilder;
    private final PersonalizationScorer fitScorer;
    private final RouteScoringService scoring;

    public PersonalizedRankingService (
            ProfileBuilder profileBuilder,
            PersonalizationScorer fitScorer,
            RouteScoringService scoring
    ) {
        this.profileBuilder = profileBuilder;
        this.fitScorer = fitScorer;
        this.scoring = scoring;
    }

    public List<PersonalizedRoute> rank(List<PersonalizedCandidate> candidates, QuestionnaireAnswers answers) {
        UserProfile user = profileBuilder.build(answers);

        return candidates.stream()
                .filter(c -> passedHardFilters(user, c.profile()))
                .map(c -> score(user, c))
                .sorted(Comparator.comparingDouble(PersonalizedRoute::total).reversed())
                .toList();
    }

    public boolean passedHardFilters(UserProfile user, RouteProfile route) {
        if (route.closedMonths().contains(user.travelMonth())) {
            return false;
        }
        if (user.travellingWithPets() && route.petFriendliness () < MIN_PET_FRIENDLINESS) {
            return false;
        }
        return  !(user.travellingWithKids() && route.kidFriendliness() < MIN_KID_FRIENDLINESS);
    }

    private PersonalizedRoute score(UserProfile user, PersonalizedCandidate c) {
        ScoredRoute base = scoring.rank(List.of(c.route()), user.weights()).get(0);
        PersonalizationScorer.Fit fit = fitScorer.fit(user, c.profile());

        double penalty = c.profile().riskyMonths().contains(user.travelMonth())
                ? RISKY_SEASON_PENALTY : 0;
        double total = (1 - FIT_BLEND) * base.total() + FIT_BLEND * fit.total() - penalty;
        total = Math.max(0, Math.min(100, total));

        List<String> why = new ArrayList<>(base.whyPicked());
        fit.components().forEach((key, value) -> {
            if (value >= 80) why.add(REASONS.get(key));
        });
        if (penalty > 0) why.add("Heads up: this season can be risky on this route");

        return new PersonalizedRoute(c.route().name(), round1(total),
                base.total(), round1(fit.total()), why);

    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }


}
