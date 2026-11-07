package com.example.jarvis.Controller;

import com.example.jarvis.RatingSystem.RouteCandidate;
import com.example.jarvis.RatingSystem.ScoreWeights;
import com.example.jarvis.RatingSystem.ScoredRoute;
import com.example.jarvis.Services.RouteScoringService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
public class RouteController {
    public record RankRequest(List<RouteCandidate> routes, ScoreWeights weights) {}

    private final RouteScoringService scoring;

    public RouteController(RouteScoringService scoring) {
        this.scoring = scoring;
    }

    @PostMapping("/rank")
    public List<ScoredRoute> rank(@RequestBody RankRequest request) {
        return scoring.rank(request.routes(), request.weights());
    }


}
