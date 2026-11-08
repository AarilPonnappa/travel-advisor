package com.example.jarvis.TripPlaner;
import com.sidekick.roadtrip.personalization.QuestionnaireAnswers;
import java.util.List;
public class TripRequest {

    public record TripRequest(QuestionnaireAnswers answers, List<PlannerRouteInput> routes,
                              Integer lodgingRadiusMeters, Integer picksPerStretch) {}
}
