package com.example.jarvis.TripPlanner;
import com.example.jarvis.Personalization.PersonalizedRoute;
import java.util.List;

    /** ranked: every route that passed the filters, best first. chosen: the winner in full (null if none passed). */
    public record TripPlan(List<PersonalizedRoute> ranked, PlannedRoute chosen) {}
