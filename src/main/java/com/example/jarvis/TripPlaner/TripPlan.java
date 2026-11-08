package com.example.jarvis.TripPlaner;
import com.sidekick.roadtrip.personalization.PersonalizedRoute;
import java.util.List;

public class TripPlan {
    /** ranked: every route that passed the filters, best first. chosen: the winner in full (null if none passed). */
    public record TripPlan(List<PersonalizedRoute> ranked, PlannedRoute chosen) {}
}
