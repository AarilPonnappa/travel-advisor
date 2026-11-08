package com.example.jarvis.service;

import com.example.jarvis.dto.GeoPoint;
import com.example.jarvis.dto.RouteEstimate;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

// openrouteservice (HeiGIT): driving routes, travel-time matrix and city lookups. free key, no card
@Service
public class OpenRouteService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouteService.class);

    // ors error codes meaning "there really is no road between these points"
    private static final Set<Integer> NO_ROUTE_CODES = Set.of(2009, 2010);

    public enum Outcome { FOUND, NO_ROUTE, UNAVAILABLE }

    public record DriveResult(Outcome outcome, RouteEstimate route) {
    }

    private final RestClient http;
    private final String key;
    private final String baseUrl;
    private final String geocodeUrl;

    public OpenRouteService(RestClient.Builder builder,
                            @Value("${jarvis.ors.key:}") String key,
                            @Value("${jarvis.url.ors}") String baseUrl,
                            @Value("${jarvis.url.ors-geocode}") String geocodeUrl) {
        this.http = builder.build();
        this.key = key;
        this.baseUrl = baseUrl;
        this.geocodeUrl = geocodeUrl;
    }

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    public DriveResult drivingRoute(double fromLat, double fromLng, double toLat, double toLng) {
        if (!isConfigured()) return new DriveResult(Outcome.UNAVAILABLE, null);
        Map<String, Object> body = new LinkedHashMap<>();
        // ors wants [longitude, latitude]
        body.put("coordinates", List.of(List.of(fromLng, fromLat), List.of(toLng, toLat)));
        body.put("instructions", false);
        try {
            JsonNode root = JsonSupport.parse(http.post().uri(baseUrl + "/v2/directions/driving-car")
                    .header("Authorization", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(body))
                    .retrieve().body(String.class));
            JsonNode summary = root == null ? null : root.path("routes").path(0).path("summary");
            if (summary == null || summary.isMissingNode() || !summary.has("duration")) {
                return new DriveResult(Outcome.NO_ROUTE, null);
            }
            int minutes = (int) Math.round(summary.path("duration").asDouble() / 60.0);
            double km = summary.path("distance").asDouble() / 1000.0;
            return new DriveResult(Outcome.FOUND,
                    new RouteEstimate(minutes, km, null, List.of("CAR"), "Driving route", null, null, 0, "LIVE"));
        } catch (RestClientResponseException e) {
            int code = errorCode(e.getResponseBodyAsString());
            if (NO_ROUTE_CODES.contains(code)) return new DriveResult(Outcome.NO_ROUTE, null);
            log.warn("ors directions failed ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            return new DriveResult(Outcome.UNAVAILABLE, null);
        } catch (Exception e) {
            log.warn("ors directions failed: {}", e.getMessage());
            return new DriveResult(Outcome.UNAVAILABLE, null);
        }
    }

    static int errorCode(String body) {
        try {
            JsonNode n = JsonSupport.MAPPER.readTree(body);
            return n.path("error").path("code").asInt(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    // minutes between every pair of points inside a city: walk when it's short, otherwise transit-ish
    public int[][] travelMatrix(List<double[]> points) {
        int n = points.size();
        int[][] minutes = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                minutes[i][j] = i == j ? 0 : estimateCityMinutes(points.get(i), points.get(j));
            }
        }
        if (!isConfigured() || n < 2) return minutes;
        double[][] walk = matrix("foot-walking", points);
        double[][] drive = matrix("driving-car", points);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Integer best = combine(cell(walk, i, j), cell(drive, i, j));
                if (best != null) minutes[i][j] = best;
            }
        }
        return minutes;
    }

    // walking up to 20 min, beyond that public transport is usually ~1.6x the drive plus waiting
    static Integer combine(Double walkSeconds, Double driveSeconds) {
        Integer walk = walkSeconds == null ? null : (int) Math.round(walkSeconds / 60.0);
        Integer transit = driveSeconds == null ? null : (int) Math.round(driveSeconds / 60.0 * 1.6) + 8;
        if (walk != null && walk <= 20) return Math.max(1, walk);
        if (transit != null && walk != null) return Math.max(1, Math.min(walk, transit));
        if (transit != null) return Math.max(1, transit);
        return walk == null ? null : Math.max(1, walk);
    }

    private double[][] matrix(String profile, List<double[]> points) {
        List<List<Double>> locations = new ArrayList<>();
        for (double[] p : points) locations.add(List.of(p[1], p[0]));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locations", locations);
        body.put("metrics", List.of("duration"));
        try {
            JsonNode root = JsonSupport.parse(http.post().uri(baseUrl + "/v2/matrix/" + profile)
                    .header("Authorization", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJson(body))
                    .retrieve().body(String.class));
            JsonNode durations = root == null ? null : root.path("durations");
            if (durations == null || !durations.isArray() || durations.size() != points.size()) return null;
            double[][] out = new double[points.size()][points.size()];
            for (int i = 0; i < points.size(); i++) {
                for (int j = 0; j < points.size(); j++) {
                    JsonNode v = durations.path(i).path(j);
                    // null means ors couldn't connect the two points
                    out[i][j] = v.isNumber() ? v.asDouble() : Double.NaN;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("ors matrix ({}) failed, using estimates: {}", profile, e.getMessage());
            return null;
        }
    }

    private static Double cell(double[][] m, int i, int j) {
        if (m == null || Double.isNaN(m[i][j])) return null;
        return m[i][j];
    }

    public static int estimateCityMinutes(double[] a, double[] b) {
        double km = GeoService.haversineKm(a[0], a[1], b[0], b[1]) * 1.3;
        if (km < 1.5) return (int) Math.round(km / 4.5 * 60) + 1;
        return (int) Math.round(km / 20.0 * 60) + 10;
    }

    // pelias geocoder hosted by heigit
    public Optional<GeoPoint> geocode(String query) {
        if (!isConfigured()) return Optional.empty();
        URI uri = UriComponentsBuilder.fromUriString(geocodeUrl + "/search")
                .queryParam("text", query)
                .queryParam("size", 1)
                .queryParam("api_key", key)
                .build().encode().toUri();
        JsonNode root = JsonSupport.parse(http.get().uri(uri).header("Authorization", key).retrieve().body(String.class));
        JsonNode f = root == null ? null : root.path("features").path(0);
        if (f == null || f.isMissingNode()) return Optional.empty();
        JsonNode coords = f.path("geometry").path("coordinates");
        if (!coords.isArray() || coords.size() < 2) return Optional.empty();
        JsonNode p = f.path("properties");
        String cc = p.path("country_code").asText(null);
        if (cc == null || cc.length() != 2) cc = alpha3ToAlpha2(p.path("country_a").asText(null));
        return Optional.of(new GeoPoint(query, p.path("label").asText(query), coords.get(1).asDouble(),
                coords.get(0).asDouble(), cc == null ? null : cc.toUpperCase(Locale.ROOT), "OPENROUTESERVICE"));
    }

    static String alpha3ToAlpha2(String alpha3) {
        if (alpha3 == null || alpha3.length() != 3) return null;
        for (String a2 : Locale.getISOCountries()) {
            try {
                if (new Locale("", a2).getISO3Country().equalsIgnoreCase(alpha3)) return a2;
            } catch (MissingResourceException ignored) {
                // some legacy codes have no iso3 mapping
            }
        }
        return null;
    }

    private static String toJson(Object body) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("couldn't build request body", e);
        }
    }
}
