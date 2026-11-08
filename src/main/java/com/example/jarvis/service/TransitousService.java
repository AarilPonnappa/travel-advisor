package com.example.jarvis.service;

import com.example.jarvis.dto.RouteEstimate;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// transitous: community run public transport router (MOTIS). no key, but it wants a descriptive user agent
@Service
public class TransitousService {

    private static final Logger log = LoggerFactory.getLogger(TransitousService.class);

    static final Set<String> RAIL = Set.of("RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_RAIL",
            "REGIONAL_FAST_RAIL", "SUBURBAN", "SUBWAY", "TRAM", "METRO", "FUNICULAR");
    static final Set<String> BUS = Set.of("BUS", "COACH");

    // three searches per leg: anything but planes, rail only, buses only
    static final List<String> MODE_SETS = List.of(
            "RAIL,COACH,BUS,FERRY,TRAM,SUBWAY,SUBURBAN",
            "RAIL",
            "COACH,BUS");

    private final RestClient http;
    private final String baseUrl;
    private final String userAgent;
    // the service is volunteer run, so never ask the same question twice in a while
    private final Map<String, CachedRoutes> cache = new ConcurrentHashMap<>();

    record CachedRoutes(List<RouteEstimate> routes, Instant at) {
    }

    public TransitousService(RestClient.Builder builder,
                             @Value("${jarvis.url.transitous}") String baseUrl,
                             @Value("${jarvis.transitous.user-agent}") String userAgent) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
    }

    // null means the service couldn't be reached, an empty list means it answered with nothing
    public List<RouteEstimate> routes(double fromLat, double fromLng, double toLat, double toLng, LocalDate date) {
        String cacheKey = String.format(Locale.ROOT, "%.3f,%.3f>%.3f,%.3f@%s", fromLat, fromLng, toLat, toLng, date);
        CachedRoutes hit = cache.get(cacheKey);
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(6)))) return hit.routes();

        List<RouteEstimate> found = new ArrayList<>();
        boolean anyAnswer = false;
        String time = departureTime(date, fromLng);
        for (String modes : MODE_SETS) {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/api/v6/plan")
                    .queryParam("fromPlace", fromLat + "," + fromLng)
                    .queryParam("toPlace", toLat + "," + toLng)
                    .queryParam("transitModes", modes)
                    .queryParam("maxItineraries", 3)
                    .queryParam("detailedLegs", false)
                    .queryParam("detailedTransfers", false);
            if (time != null) b.queryParam("time", time);
            URI uri = b.build().encode().toUri();
            try {
                JsonNode root = JsonSupport.parse(http.get().uri(uri).header("User-Agent", userAgent).retrieve().body(String.class));
                anyAnswer = true;
                if (root == null) continue;
                for (JsonNode it : root.path("itineraries")) {
                    RouteEstimate r = parseItinerary(it);
                    if (r != null) found.add(r);
                }
            } catch (Exception e) {
                log.warn("transitous plan ({}) failed: {}", modes, e.getMessage());
            }
        }
        if (!anyAnswer) return null;
        cache.put(cacheKey, new CachedRoutes(found, Instant.now()));
        return found;
    }

    static RouteEstimate parseItinerary(JsonNode it) {
        JsonNode legs = it.path("legs");
        if (!legs.isArray() || legs.isEmpty()) return null;
        Map<String, Double> kmByMode = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        String depart = null;
        String arrive = null;
        double totalKm = 0;
        int transitLegs = 0;
        for (JsonNode leg : legs) {
            String mode = leg.path("mode").asText("");
            double km = legKm(leg);
            totalKm += km;
            String group = RAIL.contains(mode) ? "RAIL" : BUS.contains(mode) ? "BUS" : "FERRY".equals(mode) ? "FERRY"
                    : "AIRPLANE".equals(mode) ? "AIRPLANE" : null;
            if (group == null) continue;
            transitLegs++;
            kmByMode.merge(group, km, Double::sum);
            String agency = leg.path("agencyName").asText("");
            String line = leg.path("displayName").asText(leg.path("routeShortName").asText(""));
            String label = (agency + " " + line).trim();
            if (!label.isBlank() && !lines.contains(label)) lines.add(label);
            if (depart == null) depart = localTime(leg.path("startTime").asText(null), leg.path("from").path("lon").asDouble(0));
            arrive = localTime(leg.path("endTime").asText(null), leg.path("to").path("lon").asDouble(0));
        }
        // nothing but walking, or someone slipped a flight in
        if (transitLegs == 0 || kmByMode.containsKey("AIRPLANE")) return null;
        List<String> modes = new ArrayList<>(kmByMode.keySet());
        modes.sort((a, b) -> Double.compare(kmByMode.get(b), kmByMode.get(a)));
        int minutes = (int) Math.round(it.path("duration").asDouble() / 60.0);
        int transfers = it.has("transfers") ? it.path("transfers").asInt() : Math.max(0, transitLegs - 1);
        return new RouteEstimate(minutes, Math.round(totalKm * 10) / 10.0, null, modes, String.join(" + ", lines),
                depart, arrive, transfers, "LIVE");
    }

    // transit legs carry no distance, so measure stop to stop
    static double legKm(JsonNode leg) {
        if (leg.hasNonNull("distance")) return leg.path("distance").asDouble() / 1000.0;
        JsonNode f = leg.path("from");
        JsonNode t = leg.path("to");
        if (!f.has("lat") || !t.has("lat")) return 0;
        return GeoService.haversineKm(f.path("lat").asDouble(), f.path("lon").asDouble(),
                t.path("lat").asDouble(), t.path("lon").asDouble());
    }

    // rough local clock from longitude, close enough for "leaves around 08:40"
    static ZoneOffset approxOffset(double lng) {
        int hours = (int) Math.round(lng / 15.0);
        return ZoneOffset.ofHours(Math.max(-12, Math.min(14, hours)));
    }

    static String localTime(String iso, double lng) {
        if (iso == null || iso.isBlank()) return null;
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso).withOffsetSameInstant(approxOffset(lng));
            return t.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        } catch (Exception e) {
            return null;
        }
    }

    // ask for departures from 08:00 local on the travel day
    static String departureTime(LocalDate date, double lng) {
        if (date == null) return null;
        OffsetDateTime at = date.atTime(8, 0).atOffset(approxOffset(lng));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (at.isBefore(now)) at = now.plusMinutes(5);
        return at.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
