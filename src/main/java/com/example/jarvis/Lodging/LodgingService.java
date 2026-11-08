package com.example.jarvis.Lodging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class LodgingService {

    private static final double SIMPLIFY_TOLERANCE_M = 500; // shrink the route before querying
    private static final int CHUNK_POINTS = 40;              // keeps each Overpass query small
    private static final long PAUSE_BETWEEN_QUERIES_MS = 1000;

    private final OverPassClient overpass;
    private final LodgingScorer scorer;

    public LodgingService(OverPassClient overpass, LodgingScorer scorer) {
        this.overpass = overpass;
        this.scorer = scorer;
    }

    /** Top lodging anywhere along the route. */
    public List<RankedLodging> best(List<LatLon> route, int radiusMeters, LodgingPreferences prefs, int limit) {
        return rankAll(route, radiusMeters, prefs).stream().limit(limit).toList();
    }

    /** Top lodging for each stretch of the trip (e.g. each day's drive), which is usually what a trip needs. */
    public List<StretchPick> byStretch(List<LatLon> route, int radiusMeters, LodgingPreferences prefs,
                                       double kmPerStretch, int perStretch) {
        if (kmPerStretch <= 0) throw new IllegalArgumentException("kmPerStretch must be > 0");
        Map<Integer, List<RankedLodging>> groups = new TreeMap<>();
        for (RankedLodging r : rankAll(route, radiusMeters, prefs)) { // already sorted best-first
            groups.computeIfAbsent((int) (r.alongRouteKm() / kmPerStretch), k -> new ArrayList<>()).add(r);
        }
        return groups.entrySet().stream()
                .map(e -> new StretchPick(
                        e.getKey() + 1,
                        e.getKey() * kmPerStretch,
                        (e.getKey() + 1) * kmPerStretch,
                        e.getValue().stream().limit(perStretch).toList()))
                .toList();
    }

    private List<RankedLodging> rankAll(List<LatLon> route, int radiusMeters, LodgingPreferences prefs) {
        RouteGeometry geometry = new RouteGeometry(route);
        List<LatLon> line = geometry.simplified(SIMPLIFY_TOLERANCE_M);

        // Query the route in overlapping chunks, merging and de-duplicating by OSM id
        Map<String, Lodging> unique = new LinkedHashMap<>();
        for (int start = 0; start < line.size() - 1; start += CHUNK_POINTS - 1) {
            int end = Math.min(line.size(), start + CHUNK_POINTS);
            overpass.findLodging(line.subList(start, end), radiusMeters)
                    .forEach(l -> unique.putIfAbsent(l.id(), l));
            if (end < line.size()) pause();
        }

        List<RankedLodging> ranked = new ArrayList<>();
        for (Lodging l : unique.values()) {
            if (prefs.petsRequired() && "no".equalsIgnoreCase(l.tags().get("dog"))) continue; // explicitly no dogs
            RouteGeometry.Projection p = geometry.project(l.location());
            LodgingScorer.Result r = scorer.score(l, p.offRouteMeters(), prefs);
            ranked.add(new RankedLodging(l, round1(r.score()),
                    round1(p.offRouteMeters() / 1000), round1(p.alongRouteMeters() / 1000), r.reasons()));
        }
        ranked.sort(Comparator.comparingDouble(RankedLodging::score).reversed());
        return ranked;
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE_BETWEEN_QUERIES_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}