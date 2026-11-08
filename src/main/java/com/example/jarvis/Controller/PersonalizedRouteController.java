package com.example.jarvis.Controller;

import com.example.jarvis.Personalization.PersonalizedCandidate;
import com.example.jarvis.Personalization.PersonalizedRoute;
import com.example.jarvis.Personalization.TravelPreferences;
import com.example.jarvis.Services.PersonalizedRankingService;
import com.example.jarvis.Services.ProfileBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/personalized")
public class PersonalizedRouteController {
    /** preferences and departure are optional; missing values fall back to neutral defaults. */
    public record Request(TravelPreferences preferences, LocalDate departure, List<PersonalizedCandidate> routes) {}

    private final PersonalizedRankingService ranking;
    private final ProfileBuilder profileBuilder;

    public PersonalizedRouteController (PersonalizedRankingService ranking, ProfileBuilder profileBuilder) {
        this.ranking = ranking;
        this.profileBuilder = profileBuilder;
    }

    @PostMapping("/rank")
    public List<PersonalizedRoute> rank(@RequestBody Request request) {
        return ranking.rank(request.routes(), profileBuilder.build(request.preferences(), request.departure()));
    }
}
