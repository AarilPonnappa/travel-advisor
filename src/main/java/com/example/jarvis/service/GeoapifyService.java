package com.example.jarvis.service;

import com.example.jarvis.dto.PlaceInfo;
import com.example.jarvis.dto.RestaurantInfo;
import com.example.jarvis.model.ActivityType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

// geoapify places (openstreetmap data): activity spots per day type and restaurants by cuisine
@Service
public class GeoapifyService {

    private static final Logger log = LoggerFactory.getLogger(GeoapifyService.class);
    private static final int RADIUS_M = 25000;

    // each inner list is one request, so one bad category can't sink the others
    static final Map<ActivityType, List<String>> ACTIVITY_CATEGORIES = new EnumMap<>(ActivityType.class);

    static {
        ACTIVITY_CATEGORIES.put(ActivityType.ADVENTURE, List.of(
                "entertainment.activity_park.climbing,entertainment.flying_fox",
                "commercial.outdoor_and_sport.water_sports,entertainment.water_park,entertainment.theme_park",
                "natural.protected_area,leisure.park.nature_reserve"));
        ACTIVITY_CATEGORIES.put(ActivityType.SCENIC, List.of(
                "tourism.attraction.viewpoint",
                "natural.protected_area,leisure.park.nature_reserve,natural.water,beach",
                "leisure.park"));
        ACTIVITY_CATEGORIES.put(ActivityType.HISTORICAL, List.of(
                "entertainment.museum",
                "tourism.sights.castle,tourism.sights.fort,tourism.sights.archaeological_site,tourism.sights.memorial.monument,heritage",
                "tourism.sights.place_of_worship.cathedral,tourism.sights.place_of_worship.church,tourism.sights.place_of_worship.temple"));
        ACTIVITY_CATEGORIES.put(ActivityType.RELAXING, List.of(
                "leisure.spa,service.beauty.massage",
                "leisure.park.garden,leisure.park",
                "entertainment.culture.gallery,entertainment.culture.arts_centre,catering.cafe"));
        ACTIVITY_CATEGORIES.put(ActivityType.PARTY, List.of(
                "adult.nightclub",
                "catering.bar,catering.pub,catering.taproom",
                "entertainment.culture.theatre"));
    }

    // cuisine keys geoapify actually knows about
    static final Set<String> RESTAURANT_CUISINES = Set.of("afghan", "african", "american", "arab", "argentinian", "asian",
            "austrian", "balkan", "barbecue", "bavarian", "beef_bowl", "belgian", "bolivian", "brazilian", "burger",
            "caribbean", "chicken", "chili", "chinese", "croatian", "cuban", "curry", "czech", "danish", "dumpling",
            "ethiopian", "european", "filipino", "fish", "fish_and_chips", "french", "friture", "georgian", "german",
            "greek", "hawaiian", "hungarian", "indian", "indonesian", "international", "irish", "italian", "jamaican",
            "japanese", "kebab", "korean", "latin_american", "lebanese", "malay", "malaysian", "mediterranean", "mexican",
            "moroccan", "nepalese", "noodle", "oriental", "pakistani", "persian", "peruvian", "pita", "pizza",
            "portuguese", "ramen", "regional", "russian", "sandwich", "seafood", "soup", "spanish", "steak_house",
            "sushi", "swedish", "syrian", "tacos", "taiwanese", "tapas", "tex-mex", "thai", "turkish", "ukrainian",
            "uzbek", "vietnamese", "western", "wings");
    static final Map<String, String> CUISINE_ALIASES = Map.of(
            "bbq", "barbecue", "steak", "steak_house", "steakhouse", "steak_house", "tex mex", "tex-mex",
            "middle eastern", "lebanese", "burgers", "burger", "noodles", "noodle", "dumplings", "dumpling",
            "fish and chips", "fish_and_chips", "latin", "latin_american");

    private final RestClient http;
    private final String key;
    private final String baseUrl;

    public GeoapifyService(RestClient.Builder builder,
                           @Value("${jarvis.geoapify.key:}") String key,
                           @Value("${jarvis.url.geoapify}") String baseUrl) {
        this.http = builder.build();
        this.key = key;
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }

    public List<PlaceInfo> activityPlaces(ActivityType type, double lat, double lng, int limit) {
        if (!isConfigured()) return List.of();
        List<PlaceInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int perGroup = Math.max(2, (int) Math.ceil(limit / 2.0));
        for (String categories : ACTIVITY_CATEGORIES.getOrDefault(type, List.of())) {
            for (JsonNode f : search(categories, lat, lng, perGroup, null)) {
                PlaceInfo p = toPlace(f);
                if (p != null && seen.add(p.name().toLowerCase(Locale.ROOT))) out.add(p);
            }
            if (out.size() >= limit) break;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public List<RestaurantInfo> restaurants(String city, String cuisine, double lat, double lng, int limit) {
        if (!isConfigured()) return List.of();
        String slug = cuisineSlug(cuisine);
        List<RestaurantInfo> out = new ArrayList<>();
        if (slug != null) {
            for (JsonNode f : search("catering.restaurant." + slug, lat, lng, limit, null)) {
                RestaurantInfo r = toRestaurant(f, city, cuisine);
                if (r != null) out.add(r);
            }
            // cheap eats are often filed under fast food instead
            if (out.isEmpty() && Set.of("pizza", "burger", "kebab", "noodle", "ramen", "sandwich", "tacos", "wings",
                    "soup", "pita", "tapas", "fish_and_chips").contains(slug)) {
                for (JsonNode f : search("catering.fast_food." + slug, lat, lng, limit, null)) {
                    RestaurantInfo r = toRestaurant(f, city, cuisine);
                    if (r != null) out.add(r);
                }
            }
        }
        if (out.isEmpty()) {
            // unknown cuisine: look for it by name, then fall back to any restaurant
            String byName = cuisine == null || cuisine.isBlank() ? null : cuisine.trim();
            for (JsonNode f : search("catering.restaurant", lat, lng, limit, byName)) {
                RestaurantInfo r = toRestaurant(f, city, cuisine);
                if (r != null) out.add(r);
            }
            if (out.isEmpty() && byName != null) {
                for (JsonNode f : search("catering.restaurant", lat, lng, limit, null)) {
                    RestaurantInfo r = toRestaurant(f, city, null);
                    if (r != null) out.add(r);
                }
            }
        }
        return out;
    }

    static String cuisineSlug(String cuisine) {
        if (cuisine == null || cuisine.isBlank()) return null;
        String c = cuisine.trim().toLowerCase(Locale.ROOT);
        if (CUISINE_ALIASES.containsKey(c)) return CUISINE_ALIASES.get(c);
        String slug = c.replace(' ', '_');
        if (RESTAURANT_CUISINES.contains(slug)) return slug;
        // "deep dish pizza", "spicy thai food" -> pizza, thai
        for (String word : c.split("[^a-z-]+")) {
            if (CUISINE_ALIASES.containsKey(word)) return CUISINE_ALIASES.get(word);
            if (RESTAURANT_CUISINES.contains(word)) return word;
        }
        for (String known : RESTAURANT_CUISINES) {
            if (known.contains("_") && c.contains(known.replace('_', ' '))) return known;
        }
        return null;
    }

    private List<JsonNode> search(String categories, double lat, double lng, int limit, String name) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/v2/places")
                .queryParam("categories", categories)
                .queryParam("filter", "circle:" + lng + "," + lat + "," + RADIUS_M)
                .queryParam("bias", "proximity:" + lng + "," + lat)
                .queryParam("limit", limit)
                .queryParam("lang", "en")
                .queryParam("apiKey", key);
        if (name != null) b.queryParam("name", name);
        URI uri = b.build().encode().toUri();
        try {
            JsonNode root = JsonSupport.parse(http.get().uri(uri).retrieve().body(String.class));
            List<JsonNode> out = new ArrayList<>();
            if (root != null) root.path("features").forEach(out::add);
            return out;
        } catch (Exception e) {
            log.warn("geoapify places ({}) failed: {}", categories, e.getMessage());
            return List.of();
        }
    }

    static PlaceInfo toPlace(JsonNode feature) {
        JsonNode p = feature.path("properties");
        String name = p.path("name").asText("");
        // unnamed nodes (benches, random parks) aren't worth putting in a plan
        if (name.isBlank() || !p.has("lat") || !p.has("lon")) return null;
        List<String> types = new ArrayList<>();
        p.path("categories").forEach(c -> types.add(c.asText()));
        return new PlaceInfo(name, p.path("formatted").asText(null), p.path("lat").asDouble(), p.path("lon").asDouble(),
                null, null, types);
    }

    static RestaurantInfo toRestaurant(JsonNode feature, String city, String wanted) {
        JsonNode p = feature.path("properties");
        String name = p.path("name").asText("");
        if (name.isBlank()) return null;
        String cuisine = p.path("catering").path("cuisine").asText(null);
        if (cuisine == null) cuisine = p.path("datasource").path("raw").path("cuisine").asText(null);
        if (cuisine != null) cuisine = cuisine.replace(';', ',').replace('_', ' ');
        if (cuisine == null && wanted != null && !wanted.isBlank()) cuisine = wanted.trim();
        String website = p.path("website").asText(null);
        if (website == null) website = p.path("datasource").path("raw").path("website").asText(null);
        String link = website != null ? website : "https://www.openstreetmap.org/search?query="
                + URLEncoder.encode(name + " " + city.split(",")[0].trim(), StandardCharsets.UTF_8);
        return new RestaurantInfo(name, p.path("formatted").asText(null), cuisine, null, null, link, "LIVE");
    }
}
