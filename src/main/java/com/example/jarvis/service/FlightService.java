package com.example.jarvis.service;

import com.example.jarvis.dto.FlightQuote;
import com.example.jarvis.model.CabinClass;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// google flights through SerpApi, then SearchApi.io, then Duffel when the ones before it fail
@Service
public class FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightService.class);

    private final RestClient http;
    private final AirportService airports;
    private final String serpKey;
    private final String serpUrl;
    private final String searchKey;
    private final String searchUrl;
    private final String duffelToken;
    private final String duffelUrl;
    private final String currency;
    // free plans are small, so a repeat search within the hour is served from memory
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    record Cached(List<FlightQuote> quotes, Instant at) {
    }

    public FlightService(RestClient.Builder builder, AirportService airports,
                         @Value("${jarvis.serpapi.key:}") String serpKey,
                         @Value("${jarvis.url.serpapi}") String serpUrl,
                         @Value("${jarvis.searchapi.key:}") String searchKey,
                         @Value("${jarvis.url.searchapi}") String searchUrl,
                         @Value("${jarvis.duffel.token:}") String duffelToken,
                         @Value("${jarvis.url.duffel}") String duffelUrl,
                         @Value("${jarvis.currency:USD}") String currency) {
        this.http = builder.build();
        this.airports = airports;
        this.serpKey = serpKey;
        this.serpUrl = serpUrl;
        this.searchKey = searchKey;
        this.searchUrl = searchUrl;
        this.duffelToken = duffelToken;
        this.duffelUrl = duffelUrl;
        this.currency = currency;
    }

    public boolean isConfigured() {
        return !serpKey.isBlank() || !searchKey.isBlank() || !duffelToken.isBlank();
    }

    // duffel test tokens only ever return made-up fares on "Duffel Airways"
    public boolean duffelTestMode() {
        return duffelToken.startsWith("duffel_test_");
    }

    public List<FlightQuote> search(double fromLat, double fromLng, double toLat, double toLng, LocalDate date,
                                    int adults, int children, CabinClass cabin) {
        if (!isConfigured()) return List.of();
        List<AirportService.Airport> from = airports.nearest(fromLat, fromLng, 3);
        List<AirportService.Airport> to = airports.nearest(toLat, toLng, 3);
        if (from.isEmpty() || to.isEmpty()) return List.of();
        // same airport on both ends (e.g. two towns sharing one) means flying makes no sense
        if (from.get(0).iata().equals(to.get(0).iata())) return List.of();

        String fromIds = from.stream().map(AirportService.Airport::iata).collect(Collectors.joining(","));
        String toIds = to.stream().map(AirportService.Airport::iata).collect(Collectors.joining(","));
        String cacheKey = fromIds + ">" + toIds + "@" + date + "/" + adults + "/" + children + "/" + cabin;
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(1)))) return hit.quotes();

        String link = googleFlightsLink(from.get(0).iata(), to.get(0).iata(), date);
        List<FlightQuote> quotes = null;
        if (!serpKey.isBlank()) {
            try {
                quotes = parse(serpApi(fromIds, toIds, date, adults, children, cabin), link, "LIVE");
            } catch (Exception e) {
                log.warn("serpapi flights failed, trying searchapi: {}", e.getMessage());
            }
        }
        if (quotes == null && !searchKey.isBlank()) {
            try {
                // searchapi only gets the main airport on each side
                quotes = parse(searchApi(from.get(0).iata(), to.get(0).iata(), date, adults, children, cabin), link, "LIVE");
            } catch (Exception e) {
                log.warn("searchapi flights failed, trying duffel: {}", e.getMessage());
            }
        }
        if (quotes == null && !duffelToken.isBlank()) {
            try {
                quotes = parseDuffel(duffel(from.get(0).iata(), to.get(0).iata(), date, adults, children, cabin),
                        link, duffelTestMode() ? "DUFFEL_TEST" : "LIVE", currency);
            } catch (Exception e) {
                log.warn("duffel flights failed too: {}", e.getMessage());
            }
        }
        if (quotes == null) return List.of();
        cache.put(cacheKey, new Cached(quotes, Instant.now()));
        return quotes;
    }

    private JsonNode serpApi(String fromIds, String toIds, LocalDate date, int adults, int children, CabinClass cabin) {
        URI uri = UriComponentsBuilder.fromUriString(serpUrl)
                .queryParam("engine", "google_flights")
                .queryParam("type", 2)
                .queryParam("departure_id", fromIds)
                .queryParam("arrival_id", toIds)
                .queryParam("outbound_date", date.toString())
                .queryParam("travel_class", serpClass(cabin))
                .queryParam("adults", adults)
                .queryParam("children", children)
                .queryParam("currency", currency)
                .queryParam("hl", "en")
                .queryParam("gl", "us")
                .queryParam("api_key", serpKey)
                .build().encode().toUri();
        return checked(JsonSupport.parse(http.get().uri(uri).retrieve().body(String.class)), "serpapi");
    }

    private JsonNode searchApi(String fromId, String toId, LocalDate date, int adults, int children, CabinClass cabin) {
        URI uri = UriComponentsBuilder.fromUriString(searchUrl)
                .queryParam("engine", "google_flights")
                .queryParam("flight_type", "one_way")
                .queryParam("departure_id", fromId)
                .queryParam("arrival_id", toId)
                .queryParam("outbound_date", date.toString())
                .queryParam("travel_class", searchApiClass(cabin))
                .queryParam("adults", Math.min(9, adults))
                .queryParam("children", children)
                .queryParam("currency", currency)
                .queryParam("hl", "en")
                .queryParam("gl", "us")
                .build().encode().toUri();
        return checked(JsonSupport.parse(http.get().uri(uri).header("Authorization", "Bearer " + searchKey).retrieve().body(String.class)),
                "searchapi");
    }

    private JsonNode duffel(String fromId, String toId, LocalDate date, int adults, int children, CabinClass cabin) {
        List<Map<String, Object>> passengers = new ArrayList<>();
        for (int i = 0; i < adults; i++) passengers.add(Map.of("type", "adult"));
        // duffel types children by age; we assume school-age kids like everywhere else
        for (int i = 0; i < children; i++) passengers.add(Map.of("age", 8));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slices", List.of(Map.of("origin", fromId, "destination", toId, "departure_date", date.toString())));
        data.put("passengers", passengers);
        data.put("cabin_class", duffelClass(cabin));
        data.put("max_connections", 1);
        URI uri = UriComponentsBuilder.fromUriString(duffelUrl + "/air/offer_requests")
                .queryParam("return_offers", true)
                .queryParam("supplier_timeout", 15000)
                .build().encode().toUri();
        String body;
        try {
            body = JsonSupport.MAPPER.writeValueAsString(Map.of("data", data));
        } catch (Exception e) {
            throw new IllegalStateException("couldn't build duffel request", e);
        }
        JsonNode root = JsonSupport.parse(http.post().uri(uri)
                .header("Authorization", "Bearer " + duffelToken)
                .header("Duffel-Version", "v2")
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class));
        if (root.has("errors")) throw new IllegalStateException("duffel: " + root.path("errors").path(0).path("message").asText("error"));
        return root;
    }

    static String duffelClass(CabinClass c) {
        return switch (c == null ? CabinClass.ECONOMY : c) {
            case ECONOMY -> "economy";
            case PREMIUM_ECONOMY -> "premium_economy";
            case BUSINESS -> "business";
            case FIRST -> "first";
        };
    }

    // rough rates to usd, only used when duffel prices in another currency
    static final Map<String, Double> USD_PER_UNIT = Map.ofEntries(
            Map.entry("USD", 1.0), Map.entry("EUR", 1.08), Map.entry("GBP", 1.27), Map.entry("CAD", 0.73),
            Map.entry("AUD", 0.66), Map.entry("NZD", 0.60), Map.entry("JPY", 0.0067), Map.entry("INR", 0.012),
            Map.entry("CHF", 1.13), Map.entry("MXN", 0.055), Map.entry("SGD", 0.75), Map.entry("HKD", 0.128),
            Map.entry("SEK", 0.095), Map.entry("NOK", 0.093), Map.entry("DKK", 0.145), Map.entry("AED", 0.272));

    static BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from == null || from.equalsIgnoreCase(to)) return amount;
        Double f = USD_PER_UNIT.get(from.toUpperCase(Locale.ROOT));
        Double t = USD_PER_UNIT.get(to.toUpperCase(Locale.ROOT));
        if (f == null || t == null) return null;
        return amount.multiply(BigDecimal.valueOf(f / t)).setScale(2, RoundingMode.HALF_UP);
    }

    static List<FlightQuote> parseDuffel(JsonNode root, String link, String source, String currency) {
        List<FlightQuote> out = new ArrayList<>();
        for (JsonNode offer : root.path("data").path("offers")) {
            JsonNode slice = offer.path("slices").path(0);
            JsonNode segs = slice.path("segments");
            if (!segs.isArray() || segs.isEmpty()) continue;
            BigDecimal total;
            try {
                total = new BigDecimal(offer.path("total_amount").asText("0"));
            } catch (NumberFormatException e) {
                continue;
            }
            total = convert(total, offer.path("total_currency").asText(currency), currency);
            if (total == null || total.signum() <= 0) continue;
            JsonNode first = segs.get(0);
            JsonNode last = segs.get(segs.size() - 1);
            int minutes = isoMinutes(slice.path("duration").asText(null));
            if (minutes <= 0) {
                for (JsonNode s : segs) minutes += isoMinutes(s.path("duration").asText(null));
            }
            Set<String> airlines = new LinkedHashSet<>();
            for (JsonNode s : segs) {
                String a = s.path("marketing_carrier").path("name").asText(s.path("operating_carrier").path("name").asText(""));
                if (!a.isBlank()) airlines.add(a);
            }
            if (airlines.isEmpty()) airlines.add(offer.path("owner").path("name").asText("Airline"));
            String route = first.path("origin").path("iata_code").asText("") + "\u2192"
                    + last.path("destination").path("iata_code").asText("");
            out.add(new FlightQuote(String.join(" / ", airlines) + " (" + route + ")", minutes, segs.size() - 1,
                    total.setScale(2, RoundingMode.HALF_UP), trimTime(first.path("departing_at").asText(null)),
                    trimTime(last.path("arriving_at").asText(null)), link, source));
        }
        out.sort(Comparator.comparing(FlightQuote::totalPrice));
        return out.size() > 15 ? new ArrayList<>(out.subList(0, 15)) : out;
    }

    // "PT5H30M" / "P1DT2H" -> minutes
    static int isoMinutes(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return (int) java.time.Duration.parse(iso).toMinutes();
        } catch (Exception e) {
            return 0;
        }
    }

    // "2026-12-01T07:05:00" -> "2026-12-01T07:05"
    static String trimTime(String t) {
        if (t == null || t.length() < 16 || t.charAt(10) != 'T') return null;
        return t.substring(0, 16);
    }

    // both apis answer 200 with an "error" field; "no results" is a real answer, anything else is a failure
    static JsonNode checked(JsonNode root, String who) {
        if (root == null) throw new IllegalStateException(who + " returned an empty body");
        String error = root.path("error").asText("");
        if (!error.isBlank()) {
            String e = error.toLowerCase(Locale.ROOT);
            if (e.contains("hasn't returned any results") || e.contains("no results")) return root;
            throw new IllegalStateException(who + ": " + error);
        }
        return root;
    }

    static int serpClass(CabinClass c) {
        return switch (c == null ? CabinClass.ECONOMY : c) {
            case ECONOMY -> 1;
            case PREMIUM_ECONOMY -> 2;
            case BUSINESS -> 3;
            case FIRST -> 4;
        };
    }

    static String searchApiClass(CabinClass c) {
        return switch (c == null ? CabinClass.ECONOMY : c) {
            case ECONOMY -> "economy";
            case PREMIUM_ECONOMY -> "premium_economy";
            case BUSINESS -> "business";
            case FIRST -> "first_class";
        };
    }

    // same json shape from both: best_flights + other_flights, price is for the whole party
    static List<FlightQuote> parse(JsonNode root, String link, String source) {
        List<FlightQuote> out = new ArrayList<>();
        for (String section : List.of("best_flights", "other_flights")) {
            for (JsonNode item : root.path(section)) {
                JsonNode segs = item.path("flights");
                if (!segs.isArray() || segs.isEmpty()) continue;
                double price = item.path("price").asDouble(0);
                if (price <= 0) continue;
                JsonNode first = segs.get(0);
                JsonNode last = segs.get(segs.size() - 1);
                int minutes = item.path("total_duration").asInt(0);
                if (minutes <= 0) {
                    for (JsonNode s : segs) minutes += s.path("duration").asInt(0);
                }
                Set<String> airlines = new LinkedHashSet<>();
                for (JsonNode s : segs) {
                    String a = s.path("airline").asText("");
                    if (!a.isBlank()) airlines.add(a);
                }
                String carrier = airlines.isEmpty() ? "Airline" : String.join(" / ", airlines);
                String route = first.path("departure_airport").path("id").asText("") + "→"
                        + last.path("arrival_airport").path("id").asText("");
                out.add(new FlightQuote(carrier + " (" + route + ")", minutes, segs.size() - 1,
                        BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP),
                        isoTime(first.path("departure_airport")), isoTime(last.path("arrival_airport")), link, source));
            }
        }
        return out;
    }

    // serpapi: "2026-03-05 8:05"; searchapi: date "2026-03-05" + time "08:05"
    static String isoTime(JsonNode airport) {
        String time = airport.path("time").asText("").trim();
        String date = airport.path("date").asText("").trim();
        if (time.isEmpty()) return null;
        if (time.contains(" ")) {
            String[] parts = time.split("\\s+");
            date = parts[0];
            time = parts[parts.length - 1];
        }
        String[] hm = time.split(":");
        if (hm.length < 2) return null;
        try {
            String hh = String.format(Locale.ROOT, "%02d", Integer.parseInt(hm[0]));
            String mm = hm[1].length() >= 2 ? hm[1].substring(0, 2) : "0" + hm[1];
            return (date.isEmpty() ? "0000-00-00" : date) + "T" + hh + ":" + mm;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String googleFlightsLink(String from, String to, LocalDate date) {
        String q = "Flights from " + from + " to " + to + " on " + date + " one way";
        return "https://www.google.com/travel/flights?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }
}
