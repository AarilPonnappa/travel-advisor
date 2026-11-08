package com.example.jarvis.TripPlaner;
import com.sidekick.roadtrip.discussion.DiscussionLink;
import com.sidekick.roadtrip.lodging.StretchPick;
import java.util.List;
public class PlannedRoute {
    /** The winning route with everything attached. */
    public record PlannedRoute(String name, double total, double baseScore, double fitScore,
                               List<String> whyPicked,
                               boolean lodgingAvailable, double lodgingCoverage,
                               List<StretchPick> lodgingByStretch,
                               List<DiscussionLink> discussions) {}
}
