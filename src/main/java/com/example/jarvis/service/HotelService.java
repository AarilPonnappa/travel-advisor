package com.example.jarvis.service;

import com.example.jarvis.dto.HotelOffer;
import com.example.jarvis.model.StayType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// google hotels (hotels + vacation rentals) through SerpApi, SearchApi.io as the backup
@Service
public class HotelService {

    private static final Logger log = LoggerFactory.getLogger(HotelService.class);

    // google hotels amenity ids for "pet-friendly"
    static final String PET_HOTEL_AMENITY = "19";
    static final String PET_RENTAL_AMENITY = "24";

    private final RestClient http;
    private final String serpKey;
    private final String serpUrl;
    private final String searchKey;
    private final String searchUrl;
    private final String currency;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    record Cached(List<HotelOffer> offers, Instant at) {
    }

    public HotelService(RestClient.Builder builder,
                        @Value("${jarvis.serpapi.key:}") String serpKey,
                        @Value("${jarvis.url.serpapi}") String serpUrl,
                        @Value("${jarvis.searchapi.key:}") String searchKey,
                        @Value("${jarvis.url.searchapi}") String searchUrl,
                        @Value("${jarvis.currency:USD}") String currency) {
        this.http = builder.build();
        this.serpKey = serpKey;
        this.serpUrl = serpUrl;
        this.searchKey = searchKey;
        this.searchUrl = searchUrl;
        this.currency = currency;
    }

    public boolean isConfigured() {
        return !serpKey.isBlank() || !searchKey.isBlank();
    }

    public List<HotelOffer> search(String city, LocalDate checkIn, LocalDate checkOut,
                                   int adults, int children, boolean pets, StayType type) {
        if (!isConfigured()) return List.of();
        List<HotelOffer> all = new ArrayList<>();
        if (type == StayType.ANY || type == StayType.HOTEL) {
            all.addAll(searchOne(city, checkIn, checkOut, adults, children, pets, StayType.HOTEL));
        }
        if (type == StayType.ANY || type == StayType.HOME) {
            all.addAll(searchOne(city, checkIn, checkOut, adults, children, pets, StayType.HOME));
        }
        Map<String, HotelOffer> unique = new LinkedHashMap<>();
        for (HotelOffer o : all) unique.putIfAbsent(o.externalId(), o);
        return new ArrayList<>(unique.values());
    }

    private List<HotelOffer> searchOne(String city, LocalDate in, LocalDate out, int adults, int children,
                                       boolean pets, StayType type) {
        String cityName = city.split(",")[0].trim();
        String cacheKey = cityName.toLowerCase(Locale.ROOT) + "@" + in + ">" + out + "/" + adults + "/" + children + "/" + pets + "/" + type;
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(1)))) return hit.offers();

        long nights = Math.max(1, ChronoUnit.DAYS.between(in, out));
        List<HotelOffer> offers = null;
        if (!serpKey.isBlank()) {
            try {
                offers = parse(serpApi(cityName, in, out, adults, children, pets, type), type, pets, nights,
                        cityName, in, out, adults, children);
            } catch (Exception e) {
                log.warn("serpapi hotels failed, trying searchapi: {}", e.getMessage());
            }
        }
        if (offers == null && !searchKey.isBlank()) {
            try {
                offers = parse(searchApi(cityName, in, out, adults, children, pets, type), type, pets, nights,
                        cityName, in, out, adults, children);
            } catch (Exception e) {
                log.warn("searchapi hotels failed too: {}", e.getMessage());
            }
        }
        if (offers == null) return List.of();
        cache.put(cacheKey, new Cached(offers, Instant.now()));
        return offers;
    }

    private JsonNode serpApi(String city, LocalDate in, LocalDate out, int adults, int children, boolean pets, StayType type) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(serpUrl)
                .queryParam("engine", "google_hotels")
                .queryParam("q", type == StayType.HOME ? city : city + " hotels")
                .queryParam("check_in_date", in.toString())
                .queryParam("check_out_date", out.toString())
                .queryParam("adults", adults)
                .queryParam("currency", currency)
                .queryParam("gl", "us")
                .queryParam("hl", "en")
                .queryParam("api_key", serpKey);
        if (children > 0) {
            b.queryParam("children", children);
            b.queryParam("children_ages", childAges(children));
        }
        if (type == StayType.HOME) b.queryParam("vacation_rentals", true);
        if (pets) b.queryParam("amenities", type == StayType.HOME ? PET_RENTAL_AMENITY : PET_HOTEL_AMENITY);
        return FlightService.checked(JsonSupport.parse(http.get().uri(b.build().encode().toUri()).retrieve().body(String.class)), "serpapi");
    }

    private JsonNode searchApi(String city, LocalDate in, LocalDate out, int adults, int children, boolean pets, StayType type) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(searchUrl)
                .queryParam("engine", "google_hotels")
                .queryParam("q", type == StayType.HOME ? city : city + " hotels")
                .queryParam("check_in_date", in.toString())
                .queryParam("check_out_date", out.toString())
                // searchapi caps hotel searches at 6 adults and rentals at 10
                .queryParam("adults", Math.min(type == StayType.HOME ? 10 : 6, adults))
                .queryParam("currency", currency)
                .queryParam("gl", "us")
                .queryParam("hl", "en")
                .queryParam("property_type", type == StayType.HOME ? "vacation_rental" : "hotel");
        if (children > 0 && type == StayType.HOTEL) b.queryParam("children_ages", childAges(children));
        if (pets) b.queryParam("amenities", type == StayType.HOME ? PET_RENTAL_AMENITY : PET_HOTEL_AMENITY);
        URI uri = b.build().encode().toUri();
        return FlightService.checked(JsonSupport.parse(http.get().uri(uri).header("Authorization", "Bearer " + searchKey)
                .retrieve().body(String.class)), "searchapi");
    }

    // handles both shapes: serpapi total_rate/rate_per_night.extracted_lowest, searchapi total_price/price_per_night.extracted_price
    static List<HotelOffer> parse(JsonNode root, StayType wanted, boolean pets, long nights, String city,
                                  LocalDate in, LocalDate out, int adults, int children) {
        List<HotelOffer> offers = new ArrayList<>();
        int i = 0;
        for (JsonNode p : root.path("properties")) {
            i++;
            String name = p.path("name").asText("");
            JsonNode gps = p.path("gps_coordinates");
            if (name.isBlank() || !gps.has("latitude") || !gps.has("longitude")) continue;
            double total = firstPositive(p.path("total_rate").path("extracted_lowest"),
                    p.path("total_price").path("extracted_price"),
                    p.path("total_rate").path("extracted_before_taxes_fees"));
            if (total <= 0) {
                double nightly = firstPositive(p.path("rate_per_night").path("extracted_lowest"),
                        p.path("price_per_night").path("extracted_price"));
                total = nightly * nights;
            }
            if (total <= 0) continue;
            String rawType = p.path("type").asText("").toLowerCase(Locale.ROOT);
            StayType type = rawType.contains("vacation") ? StayType.HOME
                    : rawType.contains("hotel") ? StayType.HOTEL : (wanted == StayType.ANY ? StayType.HOTEL : wanted);
            boolean petFriendly = pets;
            for (JsonNode a : p.path("amenities")) {
                if (a.asText("").toLowerCase(Locale.ROOT).contains("pet")) petFriendly = true;
            }
            // boxed on purpose: a mixed double/null ternary would unbox null and blow up
            Double rating = null;
            if (p.hasNonNull("overall_rating")) rating = p.path("overall_rating").asDouble();
            else if (p.hasNonNull("rating")) rating = p.path("rating").asDouble();
            Integer stars = p.hasNonNull("extracted_hotel_class") ? p.path("extracted_hotel_class").asInt() : null;
            String id = p.path("property_token").asText(type + "-" + name + "-" + i);
            String link = p.path("link").asText(null);
            if (link == null || link.isBlank()) link = googleHotelsLink(name, city, in, out);
            String photo = p.path("images").path(0).path("thumbnail").asText(null);
            offers.add(new HotelOffer(id, name, type, gps.path("latitude").asDouble(), gps.path("longitude").asDouble(),
                    BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP), rating, stars,
                    p.path("address").asText(city), photo, link, petFriendly, "LIVE"));
        }
        return offers;
    }

    private static double firstPositive(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isNumber() && n.asDouble() > 0) return n.asDouble();
        }
        return 0;
    }

    // we only collect a child count, so assume school-age kids for pricing
    static String childAges(int children) {
        StringJoiner j = new StringJoiner(",");
        for (int i = 0; i < children; i++) j.add("8");
        return j.toString();
    }

    public static String googleHotelsLink(String name, String city, LocalDate in, LocalDate out) {
        String q = name + " " + city + " " + in + " to " + out;
        return "https://www.google.com/travel/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    // fake listings around the centre so the flow works without a key, always tagged (demo)
    public List<HotelOffer> demoOffers(String city, double lat, double lng, LocalDate checkIn, LocalDate checkOut,
                                       int adults, int children, boolean pets, StayType type) {
        long nights = Math.max(1, ChronoUnit.DAYS.between(checkIn, checkOut));
        Random rnd = new Random(Objects.hash(city.toLowerCase(Locale.ROOT), type));
        String[] hotelNames = {"Central Plaza Hotel", "Harbour View Inn", "Old Town Boutique", "Skyline Suites",
                "Parkside Lodge", "Riverside Hotel", "Station Budget Inn", "Grand Avenue Hotel"};
        String[] homeNames = {"Sunny loft near downtown", "Family apartment with kitchen", "Cozy studio by the park",
                "Townhouse with backyard", "Modern 2BR condo", "Quiet garden flat"};
        List<HotelOffer> out = new ArrayList<>();
        int people = adults + children;
        int rooms = Math.max(1, (int) Math.ceil(adults / 2.0));
        String cityName = city.split(",")[0].trim();
        if (type == StayType.HOTEL || type == StayType.ANY) {
            for (int i = 0; i < hotelNames.length; i++) {
                boolean petOk = i % 2 == 0;
                if (pets && !petOk) continue;
                double nightly = (70 + rnd.nextInt(230)) * rooms;
                out.add(demo("demo-h-" + i, hotelNames[i], StayType.HOTEL, lat, lng, rnd, nightly * nights,
                        3 + rnd.nextInt(3), petOk, cityName, checkIn, checkOut));
            }
        }
        if (type == StayType.HOME || type == StayType.ANY) {
            for (int i = 0; i < homeNames.length; i++) {
                boolean petOk = i % 3 != 1;
                if (pets && !petOk) continue;
                double nightly = 60 + rnd.nextInt(160) + people * 12;
                out.add(demo("demo-a-" + i, homeNames[i], StayType.HOME, lat, lng, rnd, nightly * nights,
                        null, petOk, cityName, checkIn, checkOut));
            }
        }
        return out;
    }

    private HotelOffer demo(String id, String name, StayType type, double lat, double lng, Random rnd, double total,
                            Integer stars, boolean petOk, String city, LocalDate in, LocalDate out) {
        double dLat = (rnd.nextDouble() - 0.5) * 0.06;
        double dLng = (rnd.nextDouble() - 0.5) * 0.08;
        double rating = Math.round((3.5 + rnd.nextDouble() * 1.4) * 10) / 10.0;
        return new HotelOffer(id, name + " (demo)", type, lat + dLat, lng + dLng,
                BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP), rating, stars,
                city, null, googleHotelsLink(city, city, in, out), petOk, "DEMO");
    }
}
