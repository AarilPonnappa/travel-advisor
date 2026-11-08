package com.example.jarvis.Travel;

import com.example.jarvis.Flights.FlightOption;
import com.example.jarvis.Flights.FlightProvider;
import com.example.jarvis.Flights.FlightSearchRequest;
import com.example.jarvis.Gemini.GeminiClient;
import com.example.jarvis.Personalization.TravelPreferences;
import com.example.jarvis.Services.PreferenceInferenceService;
import com.example.jarvis.TripPlanner.PlannerRouteInput;
import com.example.jarvis.TripPlanner.RouteSuggestionService;
import com.example.jarvis.TripPlanner.TripPlan;
import com.example.jarvis.TripPlanner.TripPlanner;
import com.example.jarvis.TripPlanner.TripRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a whole trip: infers preferences (no questionnaire), plans the road trip with the
 * existing ranking/lodging/discussion pipeline, and asks the airline side for flights, in parallel.
 */
@Service
public class TravelService {

    private static final Logger log = LoggerFactory.getLogger(TravelService.class);

    private final PreferenceInferenceService preferenceInference;
    private final RouteSuggestionService routeSuggestions;
    private final TripPlanner tripPlanner;
    private final List<FlightProvider> flightProviders;
    private final GeminiClient gemini;

    public TravelService(PreferenceInferenceService preferenceInference,
                         RouteSuggestionService routeSuggestions,
                         TripPlanner tripPlanner,
                         List<FlightProvider> flightProviders,
                         GeminiClient gemini) {
        this.preferenceInference = preferenceInference;
        this.routeSuggestions = routeSuggestions;
        this.tripPlanner = tripPlanner;
        this.flightProviders = flightProviders;
        this.gemini = gemini;
    }

    public TravelOptions plan(TravelRequest request) {
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        if (!gemini.isAvailable()) {
            notes.add("Gemini is not configured, so preferences use defaults and routes must be supplied");
        }

        TravelPreferences preferences = preferenceInference.infer(request.prompt(), request.preferences());

        CompletableFuture<TripPlan> roadTrip = request.wantsRoadTrip()
                ? CompletableFuture.supplyAsync(() -> planRoadTrip(request, preferences, notes))
                : CompletableFuture.completedFuture(null);
        CompletableFuture<List<FlightOption>> flights = request.wantsFlights()
                ? CompletableFuture.supplyAsync(() -> searchFlights(request, preferences, notes))
                : CompletableFuture.completedFuture(List.of());

        return new TravelOptions(preferences.resolved(), roadTrip.join(), flights.join(), List.copyOf(notes));
    }

    private TripPlan planRoadTrip(TravelRequest request, TravelPreferences preferences, List<String> notes) {
        try {
            List<PlannerRouteInput> routes = request.routes();
            if (routes == null || routes.isEmpty()) {
                routes = routeSuggestions.suggest(request.origin(), request.destination(),
                        request.departureDate(), request.prompt());
            }
            if (routes.isEmpty()) {
                notes.add("No road-trip routes available to rank");
                return null;
            }
            return tripPlanner.plan(new TripRequest(request.origin(), request.destination(),
                    request.departureDate(), preferences, routes, null, null));
        } catch (RuntimeException e) {
            log.warn("Road-trip planning failed: {}", e.getMessage(), e);
            notes.add("Road-trip planning failed: " + e.getMessage());
            return null;
        }
    }

    private List<FlightOption> searchFlights(TravelRequest request, TravelPreferences preferences, List<String> notes) {
        if (flightProviders.isEmpty()) {
            notes.add("No airline provider is connected yet, so no flights were searched");
            return List.of();
        }
        FlightSearchRequest search = new FlightSearchRequest(request.origin(), request.destination(),
                request.departureDate(), request.returnDate(), request.travellersOrDefault(),
                preferences.budget(), request.prompt());

        List<FlightOption> all = new ArrayList<>();
        for (FlightProvider provider : flightProviders) {
            try {
                all.addAll(provider.search(search));
            } catch (RuntimeException e) {
                log.warn("Flight provider '{}' failed: {}", provider.name(), e.getMessage(), e);
                notes.add("Flight search via " + provider.name() + " failed: " + e.getMessage());
            }
        }
        all.sort(Comparator.comparingDouble(FlightOption::totalPrice));
        return all;
    }
}
