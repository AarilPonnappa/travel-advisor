package com.example.jarvis.service;

import com.example.jarvis.dto.PlaceInfo;
import com.example.jarvis.dto.RedditSnippet;
import com.example.jarvis.dto.RestaurantInfo;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.ItineraryRepository;
import com.example.jarvis.repository.TripRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ItineraryService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryService.class);
    private static final int MAX_CANDIDATES = 8;

    private final TripRepository trips;
    private final ItineraryRepository itineraries;
    private final WikivoyageService wikivoyage;
    private final GeoapifyService places;
    private final OpenRouteService ors;
    private final RedditService reddit;
    private final GeminiService gemini;
    private final ObjectMapper json = JsonSupport.MAPPER;

    public ItineraryService(TripRepository trips, ItineraryRepository itineraries, WikivoyageService wikivoyage,
                            GeoapifyService places, OpenRouteService ors, RedditService reddit,
                            GeminiService gemini) {
        this.trips = trips;
        this.itineraries = itineraries;
        this.wikivoyage = wikivoyage;
        this.places = places;
        this.ors = ors;
        this.reddit = reddit;
        this.gemini = gemini;
    }

    @Transactional(readOnly = true)
    public Itinerary get(Long id) {
        return itineraries.findById(id).orElseThrow(() -> new NotFoundException("Itinerary " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Itinerary> latestFor(Long tripId) {
        return itineraries.findFirstByTripIdOrderByCreatedAtDesc(tripId);
    }

    @Transactional(readOnly = true)
    public List<Itinerary> recent() {
        return itineraries.findTop20ByOrderByCreatedAtDesc();
    }

    // everything we know about one destination before asking gemini
    static class StopContext {
        Stop stop;
        List<DayPlan> days = new ArrayList<>();
        Map<String, String> guide = Map.of();
        Map<ActivityType, List<PlaceInfo>> places = new LinkedHashMap<>();
        List<RedditSnippet> redditPosts = new ArrayList<>();
        List<RestaurantInfo> restaurants = new ArrayList<>();
        // index 0 is the base (hotel or centre), the rest are candidates
        List<String> pointNames = new ArrayList<>();
        int[][] minutes = new int[0][0];
        String baseName;
        String arrivalInfo;
    }

    @Transactional
    public Itinerary generate(Long tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        if (trip.getDayPlans().isEmpty()) throw new IllegalArgumentException("Set up your days first.");

        Map<Stop, StopContext> contexts = new LinkedHashMap<>();
        for (DayPlan p : trip.getDayPlans()) {
            contexts.computeIfAbsent(p.getStop(), s -> {
                StopContext c = new StopContext();
                c.stop = s;
                return c;
            }).days.add(p);
        }

        ArrayNode allDays = json.createArrayNode();
        List<String> tips = new ArrayList<>();
        List<String> engines = new ArrayList<>();
        for (StopContext c : contexts.values()) {
            gather(trip, c);
            JsonNode planned = null;
            if (gemini.plannerConfigured()) {
                try {
                    String raw = gemini.generatePlan(systemPrompt(trip), userPrompt(trip, c));
                    planned = parseModelJson(raw);
                    validatePlan(planned, c);
                    engines.add(gemini.plannerModel());
                } catch (Exception e) {
                    log.warn("gemini planning failed for {}, using built-in planner: {}", c.stop.getName(), e.getMessage());
                    planned = null;
                    engines.add("built-in (Gemini error: " + e.getMessage() + ")");
                }
            } else {
                engines.add("built-in (no Gemini key)");
            }
            if (planned == null) planned = fallbackPlan(trip, c);
            for (JsonNode d : planned.path("days")) {
                ObjectNode day = (ObjectNode) d;
                day.put("location", c.stop.getDisplayName());
                allDays.add(day);
            }
            for (JsonNode t : planned.path("tips")) tips.add(t.asText());
        }

        Itinerary it = new Itinerary();
        it.setTrip(trip);
        it.setTitle(clip(trip.getRouteLabel(), 250));
        it.setGeneratedBy(clip(String.join("; ", new LinkedHashSet<>(engines)), 250));
        it.setSummary(summaryLine(trip));
        it.setTips(String.join("\n", tips.stream().limit(12).toList()));
        BigDecimal spend = BigDecimal.ZERO;
        int idx = 0;
        List<JsonNode> sortedDays = new ArrayList<>();
        allDays.forEach(sortedDays::add);
        sortedDays.sort(Comparator.comparing(n -> n.path("date").asText()));
        for (JsonNode d : sortedDays) {
            ItineraryDay day = toDay(d, idx++);
            day.setItinerary(it);
            it.getDays().add(day);
            if (day.getEstimatedCost() != null) spend = spend.add(day.getEstimatedCost());
        }
        it.setEstimatedActivitySpend(spend.setScale(2, RoundingMode.HALF_UP));

        ObjectNode raw = json.createObjectNode();
        raw.put("title", it.getTitle());
        raw.put("summary", it.getSummary());
        raw.put("currency", trip.getCurrency());
        raw.put("generatedBy", it.getGeneratedBy());
        raw.set("days", json.valueToTree(sortedDays));
        raw.set("tips", json.valueToTree(tips));
        it.setRawJson(raw.toString());
        return itineraries.save(it);
    }

    private void gather(Trip trip, StopContext c) {
        Stop s = c.stop;
        String city = s.getName();
        c.guide = wikivoyage.guide(city);

        Set<ActivityType> types = new LinkedHashSet<>();
        for (DayPlan p : c.days) types.add(effectiveType(p));
        for (ActivityType t : types) {
            List<PlaceInfo> found = places.activityPlaces(t, s.getLatitude(), s.getLongitude(), 8);
            c.places.put(t, dedupePlaces(found));
        }
        int redditCalls = 0;
        for (ActivityType t : types) {
            if (redditCalls++ >= 2) break;
            c.redditPosts.addAll(reddit.search(city + " " + t.getLabel().toLowerCase() + " things to do recommendations", 4));
        }
        List<String> cuisines = splitList(trip.getCuisines());
        if (cuisines.isEmpty()) cuisines = List.of("");
        for (String cuisine : cuisines.subList(0, Math.min(3, cuisines.size()))) {
            c.restaurants.addAll(places.restaurants(city, cuisine, s.getLatitude(), s.getLongitude(), 4));
        }

        // base point for travel times: first booked stay, otherwise city centre
        StayBooking firstStay = s.getBookings().isEmpty() ? null : s.getBookings().get(0);
        double[] base = firstStay != null && firstStay.getLatitude() != null
                ? new double[]{firstStay.getLatitude(), firstStay.getLongitude()}
                : new double[]{s.getLatitude(), s.getLongitude()};
        c.baseName = firstStay != null ? firstStay.getPropertyName() : city + " centre";
        List<double[]> points = new ArrayList<>();
        points.add(base);
        c.pointNames.add(c.baseName);
        outer:
        for (List<PlaceInfo> list : c.places.values()) {
            for (PlaceInfo p : list) {
                if (points.size() > MAX_CANDIDATES) break outer;
                if (p.latitude() == null || p.longitude() == null) continue;
                points.add(new double[]{p.latitude(), p.longitude()});
                c.pointNames.add(p.name());
            }
        }
        c.minutes = ors.travelMatrix(points);

        // how and when they get here, so day one isn't overbooked
        for (Leg leg : trip.getLegs()) {
            if (leg.getToStop().getId().equals(s.getId()) && leg.getSelectedOption() != null) {
                TransportOption o = leg.getSelectedOption();
                c.arrivalInfo = o.getMode().getLabel() + " from " + leg.getFromStop().getName()
                        + (o.getArriveTime() != null ? ", arriving around " + o.getArriveTime()
                        : ", about " + o.getDurationLabel() + " of travel");
            }
        }
    }

    static ActivityType effectiveType(DayPlan p) {
        // a dangerous weather day quietly swaps to the safer suggestion
        return p.getSuggestedType() != null && p.isSevereWeather() ? p.getSuggestedType() : p.getActivityType();
    }

    private static List<PlaceInfo> dedupePlaces(List<PlaceInfo> in) {
        Map<String, PlaceInfo> m = new LinkedHashMap<>();
        for (PlaceInfo p : in) m.putIfAbsent(p.name().toLowerCase(Locale.ROOT), p);
        return new ArrayList<>(m.values()).subList(0, Math.min(8, m.size()));
    }

    String systemPrompt(Trip trip) {
        return """
                You are Jarvis, a practical travel planner. Build realistic day-by-day itineraries.
                Rules:
                - Use the supplied research (Wikivoyage, OpenStreetMap places with travel times, Reddit, restaurants by cuisine) first; only add your own picks when the data is thin.
                - Plan each full day roughly 08:00-22:00 (party days may run past midnight). Include breakfast, lunch and dinner.
                - Every item needs start/end times, realistic travel minutes from the previous item and a travel mode.
                - Respect the group (children, pets), the chosen activity type of each day, and the weather. If a day's weather is severe for outdoor plans, switch to indoor/safer options and explain in weatherNote.
                - Arrival days are lighter: start after the arrival time and check-in.
                - estimatedCost is for the WHOLE group in %s. Do not include accommodation or the intercity transport, they're already paid.
                - Respond with JSON only, no markdown.
                """.formatted(trip.getCurrency());
    }

    String userPrompt(Trip trip, StopContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("GROUP: ").append(trip.getAdults()).append(" adult(s), ").append(trip.getChildren()).append(" child(ren), ")
                .append(trip.getPets()).append(" pet(s)\n");
        if (trip.getInterests() != null) sb.append("INTERESTS: ").append(trip.getInterests()).append('\n');
        if (trip.getCuisines() != null) sb.append("CUISINES TO TRY: ").append(trip.getCuisines()).append('\n');
        sb.append("DESTINATION: ").append(c.stop.getDisplayName()).append('\n');
        sb.append("BASE / STAY: ").append(c.baseName).append('\n');
        if (c.arrivalInfo != null) sb.append("ARRIVAL: ").append(c.arrivalInfo).append('\n');

        sb.append("\nDAYS:\n");
        for (DayPlan p : c.days) {
            sb.append("- ").append(p.getDate()).append(" | type: ").append(effectiveType(p).getLabel());
            if (effectiveType(p) != p.getActivityType()) sb.append(" (user wanted ").append(p.getActivityType().getLabel()).append(", swapped for weather)");
            if (p.isTravelDay()) sb.append(" | ARRIVAL DAY");
            sb.append(" | stay: ").append(stayFor(c.stop, p.getDate()));
            sb.append(" | weather: ").append(weatherText(p)).append('\n');
        }

        if (!c.guide.isEmpty()) {
            sb.append("\nWIKIVOYAGE:\n").append(wikivoyage.asPromptText(c.guide));
        }
        if (!c.places.isEmpty()) {
            sb.append("\nPLACES FROM OPENSTREETMAP (travel minutes from base in brackets):\n");
            for (Map.Entry<ActivityType, List<PlaceInfo>> e : c.places.entrySet()) {
                sb.append(e.getKey().getLabel()).append(": ");
                StringJoiner j = new StringJoiner("; ");
                for (PlaceInfo p : e.getValue()) {
                    int idx = c.pointNames.indexOf(p.name());
                    String mins = idx > 0 ? " [" + c.minutes[0][idx] + " min]" : "";
                    j.add(p.name() + (p.rating() != null ? " (" + p.rating() + "★)" : "") + mins);
                }
                sb.append(j).append('\n');
            }
        }
        if (c.pointNames.size() > 2) {
            sb.append("\nTRAVEL MINUTES BETWEEN PLACES:\n");
            for (int i = 1; i < c.pointNames.size(); i++) {
                for (int k = i + 1; k < c.pointNames.size(); k++) {
                    sb.append(c.pointNames.get(i)).append(" <-> ").append(c.pointNames.get(k)).append(": ")
                            .append(c.minutes[i][k]).append('\n');
                }
            }
        }
        if (!c.redditPosts.isEmpty()) {
            sb.append("\nREDDIT:\n");
            for (RedditSnippet r : c.redditPosts) {
                sb.append("- r/").append(r.subreddit()).append(" \"").append(r.title()).append("\" (").append(r.score())
                        .append(" pts): ").append(r.text()).append('\n');
            }
        }
        if (!c.restaurants.isEmpty()) {
            sb.append("\nRESTAURANTS (OpenStreetMap via Geoapify):\n");
            for (RestaurantInfo r : c.restaurants) {
                sb.append("- ").append(r.name());
                if (r.cuisine() != null && !r.cuisine().isBlank()) sb.append(" | ").append(r.cuisine());
                if (r.rating() != null) sb.append(" | ").append(r.rating()).append("/5");
                if (r.priceLevel() != null) sb.append(" | ").append(r.priceLevel());
                sb.append('\n');
            }
        }
        sb.append("""

                Return JSON shaped exactly like:
                {"days":[{"date":"YYYY-MM-DD","theme":"short title","weatherNote":"","estimatedDayCost":0,
                  "items":[{"start":"HH:MM","end":"HH:MM","title":"","description":"","category":"ACTIVITY|MEAL|TRAVEL|REST|CHECKIN|NIGHTLIFE",
                  "place":"","travelMinutes":0,"travelMode":"walk|transit|drive|taxi","estimatedCost":0}]}],
                 "tips":["short practical tip"]}
                Include one entry in "days" for every date listed above, in order.
                """);
        return sb.toString();
    }

    private static String stayFor(Stop s, LocalDate d) {
        StayBooking b = s.bookingFor(d);
        if (b == null) b = s.bookingFor(d.minusDays(1));
        if (b != null) return b.getPropertyName();
        if (s.isNightSkipped(d)) return "own arrangement (friends/family)";
        return "not booked";
    }

    static String weatherText(DayPlan p) {
        if (p.getWeatherSource() == null) return "unknown";
        StringBuilder sb = new StringBuilder(p.getWeatherSummary());
        if (p.getTempMinC() != null && p.getTempMaxC() != null) {
            sb.append(", ").append(Math.round(p.getTempMinC())).append("-").append(Math.round(p.getTempMaxC())).append("°C");
        }
        if (p.getPrecipitationMm() != null && p.getPrecipitationMm() > 0) sb.append(", ").append(Math.round(p.getPrecipitationMm())).append("mm rain");
        if ("TYPICAL".equals(p.getWeatherSource())) sb.append(" (typical for the date)");
        if (p.isSevereWeather()) sb.append(" SEVERE");
        return sb.toString();
    }

    JsonNode parseModelJson(String raw) throws Exception {
        String s = raw.trim();
        // models sometimes wrap json in ```json fences anyway
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(json)?", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("no json object in model output");
        return json.readTree(s.substring(start, end + 1));
    }

    static void validatePlan(JsonNode plan, StopContext c) {
        if (!(plan instanceof ObjectNode)) throw new IllegalStateException("model output is not a json object");
        JsonNode days = plan.path("days");
        if (!days.isArray() || days.isEmpty()) throw new IllegalStateException("model returned no days");
        Set<String> wanted = new HashSet<>();
        for (DayPlan p : c.days) wanted.add(p.getDate().toString());
        Set<String> got = new HashSet<>();
        for (JsonNode d : days) {
            if (!(d instanceof ObjectNode)) throw new IllegalStateException("day is not an object");
            got.add(d.path("date").asText());
            if (!d.path("items").isArray() || d.path("items").isEmpty()) throw new IllegalStateException("a day has no items");
        }
        if (!got.containsAll(wanted)) throw new IllegalStateException("model skipped some dates");
        // drop any day the model invented outside this stop, and duplicates
        ArrayNode kept = JsonNodeFactoryHolder.array();
        Set<String> seen = new HashSet<>();
        for (JsonNode d : days) {
            String date = d.path("date").asText();
            if (wanted.contains(date) && seen.add(date)) kept.add(d);
        }
        ((ObjectNode) plan).set("days", kept);
    }

    // rule-based planner used without a gemini key or when gemini fails
    JsonNode fallbackPlan(Trip trip, StopContext c) {
        ObjectNode root = json.createObjectNode();
        ArrayNode days = root.putArray("days");
        double people = trip.getAdults() + trip.getChildren() * 0.6;
        Map<ActivityType, Integer> cursor = new EnumMap<>(ActivityType.class);
        String city = c.stop.getName();

        for (DayPlan p : c.days) {
            ActivityType type = effectiveType(p);
            ObjectNode day = days.addObject();
            day.put("date", p.getDate().toString());
            day.put("theme", type.getLabel() + " day in " + city);
            day.put("weatherNote", p.getWeatherWarning() != null ? p.getWeatherWarning() : weatherText(p));
            ArrayNode items = day.putArray("items");
            double cost = 0;
            LocalTime t;
            String lastPlace = c.baseName;
            int lastIdx = 0;

            if (p.isTravelDay()) {
                t = LocalTime.of(15, 0);
                cost += item(items, t, 45, "Check in", "Drop the bags and freshen up at " + c.baseName + ".", "CHECKIN",
                        c.baseName, 0, null, 0);
                t = t.plusMinutes(60);
            } else {
                t = LocalTime.of(8, 0);
                cost += item(items, t, 60, "Breakfast", "Breakfast near " + c.baseName + ".", "MEAL", c.baseName, 5, "walk",
                        12 * people);
                t = t.plusMinutes(60);
            }

            int blocks = p.isTravelDay() ? 1 : 2;
            for (int b = 0; b < blocks; b++) {
                Object[] pick = nextPlace(c, type, cursor);
                String place = (String) pick[0];
                int idx = (Integer) pick[1];
                int travel = idx > 0 && lastIdx >= 0 ? c.minutes[lastIdx][idx]
                        : (lastIdx == 0 ? 20 : 15);
                t = t.plusMinutes(travel);
                int dur = p.isTravelDay() ? 90 : 180;
                cost += item(items, t, dur, place, type.getDescription() + " - " + place + ".", "ACTIVITY", place,
                        travel, travel <= 15 ? "walk" : "transit", activityCost(type) * people);
                t = t.plusMinutes(dur);
                lastPlace = place;
                lastIdx = idx;
                if (b == 0 && blocks > 1) {
                    RestaurantInfo lunch = pickRestaurant(c, p.getDate().getDayOfYear());
                    String lunchName = lunch != null ? lunch.name() : "Local lunch spot near " + lastPlace;
                    t = t.plusMinutes(15);
                    cost += item(items, t, 60, "Lunch", lunchName + (lunch != null && lunch.cuisine() != null ? " (" + lunch.cuisine() + ")" : ""),
                            "MEAL", lunchName, 15, "walk", 20 * people);
                    t = t.plusMinutes(60);
                }
            }

            int backToBase = lastIdx > 0 ? c.minutes[lastIdx][0] : 20;
            t = t.plusMinutes(backToBase);
            if (t.isBefore(LocalTime.of(18, 0))) {
                cost += item(items, t, 60, "Free time", "Rest at the stay or wander nearby.", "REST", c.baseName,
                        backToBase, "transit", 0);
                t = t.plusMinutes(60);
            }
            if (t.isBefore(LocalTime.of(19, 0))) t = LocalTime.of(19, 0);
            RestaurantInfo dinner = pickRestaurant(c, p.getDate().getDayOfYear() + 7);
            String dinnerName = dinner != null ? dinner.name() : "Dinner in " + city;
            cost += item(items, t, 90, "Dinner", dinnerName + (dinner != null && dinner.cuisine() != null ? " (" + dinner.cuisine() + ")" : ""),
                    "MEAL", dinnerName, 15, "walk", 35 * people);
            t = t.plusMinutes(90);
            if (type == ActivityType.PARTY && trip.getChildren() == 0) {
                Object[] club = nextPlace(c, ActivityType.PARTY, cursor);
                cost += item(items, t.plusMinutes(30), 180, "Night out", "Bars and live music at " + club[0] + ".", "NIGHTLIFE",
                        (String) club[0], 20, "taxi", 45 * people);
            }
            day.put("estimatedDayCost", Math.round(cost));
        }
        ArrayNode tips = root.putArray("tips");
        String stayTip = c.guide.get("Stay safe");
        if (stayTip != null) tips.add(firstSentence(stayTip));
        String around = c.guide.get("Get around");
        if (around != null) tips.add(firstSentence(around));
        if (trip.hasPets()) tips.add("Check pet rules before visiting museums and indoor venues in " + city + ".");
        return root;
    }

    private Object[] nextPlace(StopContext c, ActivityType type, Map<ActivityType, Integer> cursor) {
        List<PlaceInfo> list = c.places.getOrDefault(type, List.of());
        int i = cursor.merge(type, 1, Integer::sum) - 1;
        if (!list.isEmpty()) {
            PlaceInfo p = list.get(i % list.size());
            return new Object[]{p.name(), Math.max(-1, c.pointNames.indexOf(p.name()))};
        }
        // no places api: name the kind of thing to look for
        List<String> terms = type.getSearchTerms();
        String term = terms.get(i % terms.size());
        return new Object[]{capitalize(term) + " in " + c.stop.getName(), -1};
    }

    private static RestaurantInfo pickRestaurant(StopContext c, int seed) {
        if (c.restaurants.isEmpty()) return null;
        return c.restaurants.get(Math.floorMod(seed, c.restaurants.size()));
    }

    private static double item(ArrayNode items, LocalTime start, int minutes, String title, String desc, String category,
                               String place, int travelMinutes, String travelMode, double cost) {
        ObjectNode n = items.addObject();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("HH:mm");
        n.put("start", start.format(f));
        n.put("end", start.plusMinutes(minutes).format(f));
        n.put("title", title);
        n.put("description", desc);
        n.put("category", category);
        n.put("place", place);
        n.put("travelMinutes", travelMinutes);
        if (travelMode != null) n.put("travelMode", travelMode);
        n.put("estimatedCost", Math.round(cost));
        return Math.round(cost);
    }

    static double activityCost(ActivityType t) {
        return switch (t) {
            case ADVENTURE -> 80;
            case SCENIC -> 10;
            case HISTORICAL -> 25;
            case RELAXING -> 35;
            case PARTY -> 20;
        };
    }

    ItineraryDay toDay(JsonNode d, int idx) {
        ItineraryDay day = new ItineraryDay();
        day.setDayIndex(idx);
        day.setDate(LocalDate.parse(d.path("date").asText()));
        day.setLocation(clip(d.path("location").asText(null), 250));
        day.setTheme(clip(d.path("theme").asText(null), 250));
        day.setWeatherNote(d.path("weatherNote").asText(null));
        BigDecimal itemTotal = BigDecimal.ZERO;
        int pos = 0;
        for (JsonNode i : d.path("items")) {
            ItineraryItem item = new ItineraryItem();
            item.setDay(day);
            item.setPosition(pos++);
            item.setStartTime(i.path("start").asText(null));
            item.setEndTime(i.path("end").asText(null));
            item.setTitle(clip(i.path("title").asText("Activity"), 250));
            item.setDescription(i.path("description").asText(null));
            item.setCategory(clip(i.path("category").asText("ACTIVITY").toUpperCase(Locale.ROOT), 30));
            item.setPlace(clip(i.path("place").asText(null), 250));
            item.setTravelMinutes(i.hasNonNull("travelMinutes") ? i.path("travelMinutes").asInt() : null);
            item.setTravelMode(clip(i.path("travelMode").asText(null), 30));
            if (i.hasNonNull("estimatedCost")) {
                BigDecimal c = BigDecimal.valueOf(i.path("estimatedCost").asDouble()).setScale(2, RoundingMode.HALF_UP);
                item.setEstimatedCost(c);
                itemTotal = itemTotal.add(c);
            }
            day.getItems().add(item);
        }
        BigDecimal dayCost = d.hasNonNull("estimatedDayCost") && d.path("estimatedDayCost").asDouble() > 0
                ? BigDecimal.valueOf(d.path("estimatedDayCost").asDouble()) : itemTotal;
        day.setEstimatedCost(dayCost.setScale(2, RoundingMode.HALF_UP));
        return day;
    }

    private static String summaryLine(Trip trip) {
        LocalDate start = trip.getStops().get(0).getLeaveDate();
        LocalDate end = trip.getStops().get(trip.getStops().size() - 1).getLeaveDate();
        return trip.getRouteLabel() + " | " + start + " to " + end + " | " + trip.getAdults() + " adult(s), "
                + trip.getChildren() + " child(ren), " + trip.getPets() + " pet(s)";
    }

    static List<String> splitList(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,;\\n]")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        String t = dot > 0 ? s.substring(0, dot + 1) : s;
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final class JsonNodeFactoryHolder {
        static ArrayNode array() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
