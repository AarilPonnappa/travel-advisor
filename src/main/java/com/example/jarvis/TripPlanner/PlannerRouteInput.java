package com.example.jarvis.TripPlanner;
import com.example.jarvis.Lodging.LatLon;
import com.example.jarvis.Personalization.RouteProfile;
import com.example.jarvis.RatingSystem.SubScores;

import java.util.List;

    /** One candidate route: its geometry, its seeded scores, and its attributes for personalization. */
    public record PlannerRouteInput(String name, List<LatLon> geometry,
                                    SubScores seededScores, RouteProfile profile) {}