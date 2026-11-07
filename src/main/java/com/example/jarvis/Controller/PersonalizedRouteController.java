package com.example.jarvis.Controller;

import com.example.jarvis.Personalization.PersonalizedCandidate;
import com.example.jarvis.Personalization.PersonalizedRoute;
import com.example.jarvis.Personalization.QuestionnaireAnswers;
import com.example.jarvis.Services.PersonalizedRankingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/personalized")
public class PersonalizedRouteController {
    public record Request(QuestionnaireAnswers answers, List<PersonalizedCandidate> routes) {}

    private final PersonalizedRankingService ranking;

    public PersonalizedRouteController (PersonalizedRankingService ranking) {
        this.ranking = ranking;
    }

    @PostMapping("/rank")
    public List<PersonalizedRoute> rank(@RequestBody Request request) {
        return ranking.rank(request.routes(), request.answers());
    }
}
