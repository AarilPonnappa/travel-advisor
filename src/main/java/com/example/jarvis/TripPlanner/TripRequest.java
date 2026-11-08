package com.example.jarvis.TripPlanner;
import com.example.jarvis.Personalization.QuestionnaireAnswers;
import java.util.List;

public record TripRequest(QuestionnaireAnswers answers, List<PlannerRouteInput> routes,
                              Integer lodgingRadiusMeters, Integer picksPerStretch) {}
