package com.example.jarvis.TripPlaner;
import com.sidekick.roadtrip.discussion.DiscussionService;
import com.sidekick.roadtrip.discussion.RouteQuery;
import com.sidekick.roadtrip.lodging.LodgingPreferences;
import com.sidekick.roadtrip.lodging.LodgingService;
import com.sidekick.roadtrip.lodging.RankedLodging;
import com.sidekick.roadtrip.lodging.RouteGeometry;
import com.sidekick.roadtrip.lodging.StretchPick;
import com.sidekick.roadtrip.personalization.PersonalizedCandidate;
import com.sidekick.roadtrip.personalization.PersonalizedRankingService;
import com.sidekick.roadtrip.personalization.PersonalizedRoute;
import com.sidekick.roadtrip.personalization.ProfileBuilder;
import com.sidekick.roadtrip.personalization.QuestionnaireAnswers;
import com.sidekick.roadtrip.personalization.UserProfile;
import com.sidekick.roadtrip.scoring.RouteCandidate;
import com.sidekick.roadtrip.scoring.SubScores;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

public class TripPlanner {

    /**
     * Runs the whole pipeline in one direction:
     * answers -> user profile -> lodging per route -> route sub-scores -> ranking -> winner + extras.
     */
    @Service
    public class TripPlanner {

        private static final Logger log = LoggerFactory.getLogger(TripPlanner.class);

        private static final double DECENT_LODGING_SCORE = 50;  // minimum lodging score that counts as a usable stay
        private static final double LODGING_WEIGHT = 0.6;       // share of foodLodging taken from real lodging data
        private static final double COVERAGE_WEIGHT = 0.5;      // share of practicality taken from lodging coverage
        private static final double BASE_INDEPENDENT_BONUS = 8;

        private final ProfileBuilder profileBuilder;
        private final PersonalizedRankingService ranking;
        private final LodgingService lodging;
        private final DiscussionService discussions;

        public TripPlanner(ProfileBuilder profileBuilder, PersonalizedRankingService ranking,
                           LodgingService lodging, DiscussionService discussions) {
            this.profileBuilder = profileBuilder;
            this.ranking = ranking;
            this.lodging = lodging;
            this.discussions = discussions;
        }

        public TripPlan plan(TripRequest request) {
            QuestionnaireAnswers answers = request.answers();
            UserProfile user = profileBuilder.build(answers);
            LodgingPreferences prefs = toLodgingPreferences(user);
            int radius = request.lodgingRadiusMeters() != null ? request.lodgingRadiusMeters() : 3000;
            int perStretch = request.picksPerStretch() != null ? request.picksPerStretch() : 3;

            // 1. Lodging per route, using this user's preferences, then fold it into the route's sub-scores
            Map<String, LodgingResult> lodgingByRoute = new HashMap<>();
            List<PersonalizedCandidate> candidates = new ArrayList<>();
            for (PlannerRouteInput route : request.routes()) {
                LodgingResult result = lookUpLodging(route, prefs, user.drivingKmPerDay(), radius, perStretch);
                lodgingByRoute.put(route.name(), result);
                SubScores scores = result.available() ? withLodging(route.seededScores(), result) : route.seededScores();
                candidates.add(new PersonalizedCandidate(new RouteCandidate(route.name(), scores), route.profile()));
            }

            // 2. Rank with the existing personalized pipeline (filters, weights, fit, season penalty)
            List<PersonalizedRoute> ranked = ranking.rank(candidates, answers);
            if (ranked.isEmpty()) return new TripPlan(ranked, null);

            // 3. Attach lodging stops and discussion links to the winner
            PersonalizedRoute top = ranked.get(0);
            LodgingResult lodgingResult = lodgingByRoute.get(top.name());

            List<String> why = new ArrayList<>(top.whyPicked());
            if (!lodgingResult.available()) {
                why.add("Lodging data unavailable, so lodging scores are estimates");
            } else if (lodgingResult.coverage() >= 0.8) {
                why.add("Good places to stay along the whole route");
            } else if (lodgingResult.coverage() < 0.5) {
                why.add("Heads up: few places to stay in some stretches");
            }

            PlannedRoute chosen = new PlannedRoute(
                    top.name(), top.total(), top.baseScore(), top.fitScore(), why,
                    lodgingResult.available(), round2(lodgingResult.coverage()),
                    lodgingResult.stretches(),
                    discussions.findDiscussions(new RouteQuery(top.name(), "", answers.destination())));

            return new TripPlan(ranked, chosen);
        }

        /** Translate the profile into lodging terms here so the lodging package never imports personalization. */
        private LodgingPreferences toLodgingPreferences(UserProfile user) {
            // Hidden-gem seekers value independents more; famous-sights seekers value them less
            double bonus = BASE_INDEPENDENT_BONUS + (50 - user.targetTouristiness()) / 50.0 * 4;
            return new LodgingPreferences(user.travellingWithPets(), bonus);
... (41 lines left)
}
