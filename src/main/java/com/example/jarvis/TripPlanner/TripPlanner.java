package com.example.jarvis.TripPlanner;
import com.example.jarvis.Discussion.RouteQuery;
import com.example.jarvis.Lodging.*;
import com.example.jarvis.Lodging.LodgingPreferences;
import com.example.jarvis.Lodging.LodgingService;
import com.example.jarvis.Lodging.RankedLodging;
import com.example.jarvis.Lodging.StretchPick;
import com.example.jarvis.Personalization.PersonalizedCandidate;
import com.example.jarvis.Personalization.PersonalizedRoute;
import com.example.jarvis.Personalization.UserProfile;
import com.example.jarvis.RatingSystem.RouteCandidate;
import com.example.jarvis.RatingSystem.SubScores;
import com.example.jarvis.Services.DiscussionService;
import com.example.jarvis.Services.PersonalizedRankingService;
import com.example.jarvis.Services.ProfileBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
         UserProfile user = profileBuilder.build(request.preferences(), request.departure());
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
         List<PersonalizedRoute> ranked = ranking.rank(candidates, user);
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
                 discussions.findDiscussions(new RouteQuery(top.name(), orEmpty(request.origin()), orEmpty(request.destination()))));

         return new TripPlan(ranked, chosen);
     }

     /**
      * Translate the profile into lodging terms here so the lodging package never imports personalization.
      */
     private LodgingPreferences toLodgingPreferences(UserProfile user) {
         // Hidden-gem seekers value independents more; famous-sights seekers value them less
         double bonus = BASE_INDEPENDENT_BONUS + (50 - user.targetTouristiness()) / 50.0 * 4;
         return new LodgingPreferences(user.travellingWithPets(), bonus);
     }

     private LodgingResult lookUpLodging(PlannerRouteInput route, LodgingPreferences prefs,
                                         int kmPerDay, int radius, int perStretch) {
         try {
             double totalKm = new RouteGeometry(route.geometry()).totalMeters() / 1000.0;
             int totalStretches = Math.max(1, (int) Math.ceil(totalKm / kmPerDay));
             List<StretchPick> stretches = lodging.byStretch(route.geometry(), radius, prefs, kmPerDay, perStretch);

             double bestSum = 0;
             int covered = 0;
             for (StretchPick s : stretches) {
                 if (s.stretch() > totalStretches) continue;       // stretch numbers are 1-based
                 double best = s.picks().stream().mapToDouble(RankedLodging::score).max().orElse(0);
                 bestSum += best;
                 if (best >= DECENT_LODGING_SCORE) covered++;
             }
             // Stretches with no lodging at all count as 0 in the average and as uncovered
             return new LodgingResult(true, stretches,
                     covered / (double) totalStretches, bestSum / totalStretches);
         } catch (RuntimeException e) {
             log.warn("Lodging lookup failed for route '{}', using seeded scores: {}", route.name(), e.getMessage());
             return new LodgingResult(false, List.of(), 0, 0);
         }
     }

     private SubScores withLodging(SubScores seeded, LodgingResult r) {
         double food = (1 - LODGING_WEIGHT) * seeded.foodLodging() + LODGING_WEIGHT * r.avgBestScore();
         double practical = (1 - COVERAGE_WEIGHT) * seeded.practicality() + COVERAGE_WEIGHT * r.coverage() * 100;
         return new SubScores(seeded.scenic(), seeded.character(), seeded.social(),
                 food, seeded.efficiency(), practical);
     }

     private static String orEmpty(String s) {
         return s != null ? s : "";
     }

     private static double round2(double v) {
         return Math.round(v * 100) / 100.0;
     }

     private record LodgingResult(boolean available, List<StretchPick> stretches,
                                  double coverage, double avgBestScore) {
     }
 }


