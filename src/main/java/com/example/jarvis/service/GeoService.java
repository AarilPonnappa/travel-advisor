package com.example.jarvis.service;

import com.example.jarvis.dto.GeoPoint;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoService.class);

    // last resort coordinates so the app still works with no network/keys
    private static final Map<String, GeoPoint> KNOWN_CITIES = new HashMap<>();
    private static final Set<String> FERRY_PAIRS = new HashSet<>();

    static {
        city("New York", "New York, NY, USA", 40.7128, -74.0060, "US");
        city("Seattle", "Seattle, WA, USA", 47.6062, -122.3321, "US");
        city("San Francisco", "San Francisco, CA, USA", 37.7749, -122.4194, "US");
        city("Los Angeles", "Los Angeles, CA, USA", 34.0522, -118.2437, "US");
        city("Chicago", "Chicago, IL, USA", 41.8781, -87.6298, "US");
        city("Boston", "Boston, MA, USA", 42.3601, -71.0589, "US");
        city("Washington", "Washington, DC, USA", 38.9072, -77.0369, "US");
        city("Miami", "Miami, FL, USA", 25.7617, -80.1918, "US");
        city("Las Vegas", "Las Vegas, NV, USA", 36.1699, -115.1398, "US");
        city("Denver", "Denver, CO, USA", 39.7392, -104.9903, "US");
        city("Portland", "Portland, OR, USA", 45.5152, -122.6784, "US");
        city("Honolulu", "Honolulu, HI, USA", 21.3069, -157.8583, "US");
        city("Vancouver", "Vancouver, BC, Canada", 49.2827, -123.1207, "CA");
        city("Victoria", "Victoria, BC, Canada", 48.4284, -123.3656, "CA");
        city("Toronto", "Toronto, ON, Canada", 43.6532, -79.3832, "CA");
        city("Montreal", "Montreal, QC, Canada", 45.5019, -73.5674, "CA");
        city("Calgary", "Calgary, AB, Canada", 51.0447, -114.0719, "CA");
        city("Banff", "Banff, AB, Canada", 51.1784, -115.5708, "CA");
        city("Whistler", "Whistler, BC, Canada", 50.1163, -122.9574, "CA");
        city("Mexico City", "Mexico City, Mexico", 19.4326, -99.1332, "MX");
        city("Cancun", "Cancun, Mexico", 21.1619, -86.8515, "MX");
        city("London", "London, UK", 51.5074, -0.1278, "GB");
        city("Edinburgh", "Edinburgh, UK", 55.9533, -3.1883, "GB");
        city("Dover", "Dover, UK", 51.1279, 1.3134, "GB");
        city("Calais", "Calais, France", 50.9513, 1.8587, "FR");
        city("Paris", "Paris, France", 48.8566, 2.3522, "FR");
        city("Rome", "Rome, Italy", 41.9028, 12.4964, "IT");
        city("Barcelona", "Barcelona, Spain", 41.3874, 2.1686, "ES");
        city("Madrid", "Madrid, Spain", 40.4168, -3.7038, "ES");
        city("Lisbon", "Lisbon, Portugal", 38.7223, -9.1393, "PT");
        city("Amsterdam", "Amsterdam, Netherlands", 52.3676, 4.9041, "NL");
        city("Berlin", "Berlin, Germany", 52.5200, 13.4050, "DE");
        city("Munich", "Munich, Germany", 48.1351, 11.5820, "DE");
        city("Prague", "Prague, Czechia", 50.0755, 14.4378, "CZ");
        city("Vienna", "Vienna, Austria", 48.2082, 16.3738, "AT");
        city("Zurich", "Zurich, Switzerland", 47.3769, 8.5417, "CH");
        city("Dublin", "Dublin, Ireland", 53.3498, -6.2603, "IE");
        city("Athens", "Athens, Greece", 37.9838, 23.7275, "GR");
        city("Istanbul", "Istanbul, Turkey", 41.0082, 28.9784, "TR");
        city("Helsinki", "Helsinki, Finland", 60.1699, 24.9384, "FI");
        city("Tallinn", "Tallinn, Estonia", 59.4370, 24.7536, "EE");
        city("Stockholm", "Stockholm, Sweden", 59.3293, 18.0686, "SE");
        city("Copenhagen", "Copenhagen, Denmark", 55.6761, 12.5683, "DK");
        city("Oslo", "Oslo, Norway", 59.9139, 10.7522, "NO");
        city("Reykjavik", "Reykjavik, Iceland", 64.1466, -21.9426, "IS");
        city("Dubai", "Dubai, UAE", 25.2048, 55.2708, "AE");
        city("Mumbai", "Mumbai, India", 19.0760, 72.8777, "IN");
        city("Delhi", "New Delhi, India", 28.6139, 77.2090, "IN");
        city("Bangalore", "Bengaluru, India", 12.9716, 77.5946, "IN");
        city("Tokyo", "Tokyo, Japan", 35.6762, 139.6503, "JP");
        city("Kyoto", "Kyoto, Japan", 35.0116, 135.7681, "JP");
        city("Osaka", "Osaka, Japan", 34.6937, 135.5023, "JP");
        city("Seoul", "Seoul, South Korea", 37.5665, 126.9780, "KR");
        city("Singapore", "Singapore", 1.3521, 103.8198, "SG");
        city("Bangkok", "Bangkok, Thailand", 13.7563, 100.5018, "TH");
        city("Hong Kong", "Hong Kong", 22.3193, 114.1694, "HK");
        city("Sydney", "Sydney, Australia", -33.8688, 151.2093, "AU");
        city("Melbourne", "Melbourne, Australia", -37.8136, 144.9631, "AU");
        city("Auckland", "Auckland, New Zealand", -36.8485, 174.7633, "NZ");
        city("Cape Town", "Cape Town, South Africa", -33.9249, 18.4241, "ZA");
        city("Rio de Janeiro", "Rio de Janeiro, Brazil", -22.9068, -43.1729, "BR");
        city("Buenos Aires", "Buenos Aires, Argentina", -34.6037, -58.3816, "AR");

        ferry("seattle", "victoria");
        ferry("vancouver", "victoria");
        ferry("helsinki", "tallinn");
        ferry("helsinki", "stockholm");
        ferry("dover", "calais");
        ferry("copenhagen", "oslo");
    }

    private static void city(String name, String formatted, double lat, double lng, String cc) {
        KNOWN_CITIES.put(normalize(name), new GeoPoint(name, formatted, lat, lng, cc, "BUILT_IN"));
    }

    private static void ferry(String a, String b) {
        FERRY_PAIRS.add(a + "|" + b);
        FERRY_PAIRS.add(b + "|" + a);
    }

    private final RestClient http;
    private final OpenRouteService ors;
    private final String openMeteoGeocodeUrl;
    private final Map<String, Optional<GeoPoint>> cache = new ConcurrentHashMap<>();

    public GeoService(RestClient.Builder builder, OpenRouteService ors,
                      @Value("${jarvis.url.open-meteo-geocode}") String openMeteoGeocodeUrl) {
        this.http = builder.build();
        this.ors = ors;
        this.openMeteoGeocodeUrl = openMeteoGeocodeUrl;
    }

    public Optional<GeoPoint> geocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String key = query.trim().toLowerCase(Locale.ROOT);
        Optional<GeoPoint> cached = cache.get(key);
        if (cached != null) return cached;
        Optional<GeoPoint> found = lookup(query.trim());
        // only remember hits, a miss might just be a flaky network
        found.ifPresent(p -> cache.put(key, Optional.of(p)));
        return found;
    }

    private Optional<GeoPoint> lookup(String query) {
        // openrouteservice first, then open-meteo (free, no key), then our small table
        if (ors.isConfigured()) {
            try {
                Optional<GeoPoint> g = ors.geocode(query);
                if (g.isPresent()) return g;
            } catch (Exception e) {
                log.warn("ors geocode failed for {}: {}", query, e.getMessage());
            }
        }
        try {
            Optional<GeoPoint> om = fromOpenMeteo(query);
            if (om.isPresent()) return om;
        } catch (Exception e) {
            log.warn("open-meteo geocode failed for {}: {}", query, e.getMessage());
        }
        return fromTable(query);
    }

    Optional<GeoPoint> fromOpenMeteo(String query) {
        // open-meteo only understands the bare place name, so drop anything after a comma
        String name = query.split(",")[0].trim();
        String url = UriComponentsBuilder.fromUriString(openMeteoGeocodeUrl)
                .queryParam("name", name)
                .queryParam("count", 1)
                .queryParam("language", "en")
                .build().encode().toUriString();
        JsonNode root = JsonSupport.parse(http.get().uri(java.net.URI.create(url)).retrieve().body(String.class));
        JsonNode first = root == null ? null : root.path("results").path(0);
        if (first == null || first.isMissingNode()) return Optional.empty();
        String formatted = first.path("name").asText(name);
        if (first.hasNonNull("admin1")) formatted += ", " + first.path("admin1").asText();
        if (first.hasNonNull("country")) formatted += ", " + first.path("country").asText();
        return Optional.of(new GeoPoint(name, formatted, first.path("latitude").asDouble(),
                first.path("longitude").asDouble(), first.path("country_code").asText(null), "OPEN_METEO"));
    }

    Optional<GeoPoint> fromTable(String query) {
        String n = normalize(query.split(",")[0]);
        GeoPoint p = KNOWN_CITIES.get(n);
        if (p == null && n.endsWith(" city")) p = KNOWN_CITIES.get(n.substring(0, n.length() - 5));
        if (p == null && n.equals("nyc")) p = KNOWN_CITIES.get("new york");
        if (p == null && n.equals("sf")) p = KNOWN_CITIES.get("san francisco");
        if (p == null && n.equals("la")) p = KNOWN_CITIES.get("los angeles");
        return Optional.ofNullable(p);
    }

    public boolean knownFerryRoute(String a, String b) {
        return FERRY_PAIRS.contains(normalize(a.split(",")[0]) + "|" + normalize(b.split(",")[0]));
    }

    static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // great-circle distance in km
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
