package com.example.jarvis.TripPlaner;
import com.sidekick.roadtrip.lodging.LatLon;
import com.sidekick.roadtrip.personalization.RouteProfile;
import com.sidekick.roadtrip.scoring.SubScores;
import java.util.List;

public class PlannerRouteInput {
    /** One candidate route: its geometry, its seeded scores, and its attributes for personalization. */
    public record PlannerRouteInput(String name, List<LatLon> geometry,
                                    SubScores seededScores, RouteProfile profile) {}
}
