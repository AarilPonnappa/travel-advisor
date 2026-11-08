package com.example.jarvis.service;

import com.example.jarvis.dto.FlightQuote;
import com.example.jarvis.dto.RouteEstimate;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class TransportService {

    public static final String FASTEST = "FASTEST";
    public static final String BUDGET = "BUDGET";
    public static final String CHEAPEST = "CHEAPEST";

    // airports, security, getting to/from the terminal
    static final int FLIGHT_OVERHEAD_MIN = 150;
    static final int STATION_OVERHEAD_MIN = 40;
    static final int FERRY_OVERHEAD_MIN = 60;
    // what an hour of a traveller's time is "worth" when ranking practicality
    static final double VALUE_OF_HOUR = 18.0;

    private static final Set<String> EU = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "CH", "NO");
    private final TripRepository trips;
    private final OpenRouteService ors;
    private final TransitousService transitous;
    private final FlightService flights;
    private final GeoService geo;

    public TransportService(TripRepository trips, OpenRouteService ors, TransitousService transitous,
                            FlightService flights, GeoService geo) {
        this.trips = trips;
        this.ors = ors;
        this.transitous = transitous;
        this.flights = flights;
        this.geo = geo;
    }

    @Transactional
    public void ensureOptions(Long tripId, boolean force) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        for (Leg leg : trip.getLegs()) {
            if (leg.getTravelDate() == null) continue;
            if (!force && !leg.getOptions().isEmpty()) continue;
            TransportMode keepMode = leg.getSelectedOption() == null ? null : leg.getSelectedOption().getMode();
            leg.getOptions().clear();
            List<TransportOption> generated = buildOptions(leg, trip);
            // attach first so ranking can see the group size
            for (TransportOption o : generated) o.setLeg(leg);
            rank(generated);
            leg.getOptions().addAll(generated);
            // re-select the same kind of transport after a refresh if it still exists
            if (keepMode != null) {
                generated.stream().filter(o -> o.getMode() == keepMode).findFirst().ifPresent(o -> o.setSelected(true));
            }
        }
        trips.save(trip);
    }

    @Transactional
    public void updateTravellers(Long tripId, int adults, int children, int pets, CabinClass cabin) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        if (adults < 1) throw new IllegalArgumentException("At least one adult has to travel.");
        if (children < 0 || pets < 0) throw new IllegalArgumentException("Children and pets can't be negative.");
        boolean peopleChanged = trip.getAdults() != adults || trip.getChildren() != children || trip.getPets() != pets;
        trip.setAdults(adults);
        trip.setChildren(children);
        trip.setPets(pets);
        trip.setCabinClass(cabin == null ? CabinClass.ECONOMY : cabin);
        if (peopleChanged) {
            // stays were priced for the old group
            for (Stop s : trip.getStops()) s.getBookings().clear();
        }
        trips.save(trip);
        ensureOptions(tripId, true);
    }

    // returns legs that still have nothing chosen
    @Transactional
    public List<String> select(Long tripId, Map<Long, Long> optionByLeg) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        List<String> missing = new ArrayList<>();
        for (Leg leg : trip.getLegs()) {
            Long chosen = optionByLeg.get(leg.getId());
            if (chosen == null) {
                if (leg.getSelectedOption() == null) {
                    missing.add(leg.getFromStop().getName() + " → " + leg.getToStop().getName());
                }
                continue;
            }
            boolean found = leg.getOptions().stream().anyMatch(o -> o.getId().equals(chosen));
            if (!found) {
                missing.add(leg.getFromStop().getName() + " → " + leg.getToStop().getName());
                continue;
            }
            for (TransportOption o : leg.getOptions()) o.setSelected(o.getId().equals(chosen));
        }
        trips.save(trip);
        return missing;
    }

    List<TransportOption> buildOptions(Leg leg, Trip trip) {
        Stop from = leg.getFromStop();
        Stop to = leg.getToStop();
        double km = GeoService.haversineKm(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
        int adults = trip.getAdults();
        int children = trip.getChildren();
        int people = adults + children;
        int pets = trip.getPets();
        CabinClass cabin = trip.getCabinClass() == null ? CabinClass.ECONOMY : trip.getCabinClass();
        boolean ferryPair = geo.knownFerryRoute(from.getName(), to.getName());
        boolean sameRegion = sameLandRegion(from.getCountryCode(), to.getCountryCode())
                && isHawaii(from) == isHawaii(to);
        List<TransportOption> out = new ArrayList<>();

        // flights
        if (km >= 200) {
            List<FlightQuote> quotes = flights.search(from.getLatitude(), from.getLongitude(), to.getLatitude(),
                    to.getLongitude(), leg.getTravelDate(), adults, children, cabin);
            if (!quotes.isEmpty()) {
                for (FlightQuote q : pickFlights(quotes)) {
                    BigDecimal total = q.totalPrice().add(petFlightFee(pets));
                    TransportOption o = option(TransportMode.FLIGHT, q.carrier(), q.durationMinutes(),
                            q.durationMinutes() + FLIGHT_OVERHEAD_MIN, km, q.stops(), total, cabin.getLabel(), q.source());
                    o.setSummary(q.stops() == 0 ? "Non-stop" : q.stops() + " stop" + (q.stops() > 1 ? "s" : ""));
                    o.setDepartTime(timeOf(q.departTime()));
                    o.setArriveTime(timeOf(q.arriveTime()));
                    o.setBookingUrl(q.bookingUrl());
                    o.setNotes(flightNotes(pets));
                    if ("DUFFEL_TEST".equals(q.source())) {
                        String warn = "Duffel test mode: sample fare on a test airline, not a real price.";
                        o.setNotes(o.getNotes() == null ? warn : warn + " " + o.getNotes());
                    }
                    out.add(o);
                }
            } else {
                out.add(estimatedFlight(from, to, leg, km, adults, children, pets, cabin));
            }
        }

        // trains, buses and ferries from transitous (null = the service couldn't be reached)
        List<RouteEstimate> transit = transitous.routes(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), leg.getTravelDate());
        if (transit == null) transit = List.of();
        Map<TransportMode, RouteEstimate> bestByMode = new EnumMap<>(TransportMode.class);
        for (RouteEstimate r : transit) {
            TransportMode m = classifyTransit(r);
            if (m == null) continue;
            RouteEstimate cur = bestByMode.get(m);
            if (cur == null || r.durationMinutes() < cur.durationMinutes()) bestByMode.put(m, r);
        }
        for (Map.Entry<TransportMode, RouteEstimate> e : bestByMode.entrySet()) {
            out.add(fromTransit(e.getKey(), e.getValue(), from, to, people, pets, km));
        }
        // no timetable data (outside coverage, too far ahead, or unreachable), so guess what usually exists
        if (bestByMode.isEmpty()) {
            if (!bestByMode.containsKey(TransportMode.TRAIN) && km >= 40 && km <= 4500 && sameRegion && !ferryPair) {
                out.add(estimatedTrain(from, to, km, people, pets));
            }
            if (!bestByMode.containsKey(TransportMode.BUS) && km >= 30 && km <= 2500 && sameRegion && !ferryPair) {
                out.add(estimatedBus(from, to, km, people, pets));
            }
        }
        if (!bestByMode.containsKey(TransportMode.FERRY) && ferryPair) {
            out.add(estimatedFerry(from, to, km, people, pets));
        }

        // driving
        if (!ferryPair && sameRegion && km <= 4500) {
            OpenRouteService.DriveResult drive = ors.drivingRoute(from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude());
            if (drive.outcome() == OpenRouteService.Outcome.FOUND) {
                out.add(driveOption(from, to, drive.route().distanceKm(), drive.route().durationMinutes(), people, pets, "LIVE"));
            } else if (drive.outcome() == OpenRouteService.Outcome.UNAVAILABLE) {
                // no key or the api is down: typical road distance and speed
                double roadKm = km * 1.25;
                out.add(driveOption(from, to, roadKm, (int) Math.round(roadKm / 85.0 * 60), people, pets, "ESTIMATE"));
            }
        }
        return out;
    }

    // best, cheapest and fastest flight, without duplicates
    static List<FlightQuote> pickFlights(List<FlightQuote> quotes) {
        LinkedHashSet<FlightQuote> picked = new LinkedHashSet<>();
        picked.add(quotes.get(0));
        picked.add(quotes.stream().min(Comparator.comparing(FlightQuote::totalPrice)).orElseThrow());
        picked.add(quotes.stream().min(Comparator.comparingInt(FlightQuote::durationMinutes)).orElseThrow());
        return new ArrayList<>(picked);
    }

    static TransportMode classifyTransit(RouteEstimate r) {
        if (r.vehicleTypes() == null || r.vehicleTypes().isEmpty()) return null;
        // whatever covers the most distance decides the label
        return switch (r.vehicleTypes().get(0)) {
            case "RAIL" -> TransportMode.TRAIN;
            case "BUS" -> TransportMode.BUS;
            case "FERRY" -> TransportMode.FERRY;
            default -> null;
        };
    }

    private TransportOption fromTransit(TransportMode mode, RouteEstimate r, Stop from, Stop to, int people, int pets, double km) {
        int overhead = mode == TransportMode.FERRY ? FERRY_OVERHEAD_MIN : STATION_OVERHEAD_MIN;
        BigDecimal total;
        String source = "LIVE";
        if (r.farePerPerson() != null && r.farePerPerson().signum() > 0) {
            total = r.farePerPerson().multiply(BigDecimal.valueOf(people)).setScale(2, RoundingMode.HALF_UP);
        } else {
            // google gave the route but no fare
            double perPerson = switch (mode) {
                case TRAIN -> 15 + 0.12 * r.distanceKm();
                case BUS -> 10 + 0.07 * r.distanceKm();
                default -> 25 + 0.30 * r.distanceKm();
            };
            total = money(perPerson * people);
            source = "LIVE_ROUTE_ESTIMATED_FARE";
        }
        TransportOption o = option(mode, r.summary() == null || r.summary().isBlank() ? mode.getLabel() : r.summary(),
                r.durationMinutes(), r.durationMinutes() + overhead, r.distanceKm() > 0 ? r.distanceKm() : km,
                r.transfers(), total, mode == TransportMode.TRAIN ? "Standard" : null, source);
        o.setSummary(r.transfers() == 0 ? "Direct" : r.transfers() + " change" + (r.transfers() > 1 ? "s" : ""));
        o.setDepartTime(timeOf(r.departTime()));
        o.setArriveTime(timeOf(r.arriveTime()));
        o.setBookingUrl(mapsLink(from, to, "transit"));
        o.setNotes(petNotes(mode, pets));
        return o;
    }

    private TransportOption estimatedFlight(Stop from, Stop to, Leg leg, double km, int adults, int children, int pets, CabinClass cabin) {
        int minutes = (int) Math.round(30 + km / 780.0 * 60);
        int stops = km > 3500 ? 1 : 0;
        if (stops > 0) minutes += 90;
        double perAdult = (50 + 0.07 * km) * cabin.getPriceFactor();
        double total = perAdult * adults + perAdult * 0.85 * children;
        TransportOption o = option(TransportMode.FLIGHT, "Typical airfare", minutes, minutes + FLIGHT_OVERHEAD_MIN, km, stops,
                money(total).add(petFlightFee(pets)), cabin.getLabel(), "ESTIMATE");
        o.setSummary(stops == 0 ? "Non-stop (typical)" : "1 stop (typical)");
        o.setBookingUrl(FlightService.googleFlightsLink(from.getName(), to.getName(), leg.getTravelDate()));
        o.setNotes(flightNotes(pets));
        return o;
    }

    private TransportOption estimatedTrain(Stop from, Stop to, double km, int people, int pets) {
        double railKm = km * 1.2;
        boolean longHaul = km > 1200;
        double speed = longHaul ? 75 : 100;
        int minutes = (int) Math.round(railKm / speed * 60);
        double perPerson = longHaul ? 50 + 0.09 * railKm : 15 + 0.12 * railKm;
        TransportOption o = option(TransportMode.TRAIN, longHaul ? "Long-distance rail" : "Intercity rail", minutes,
                minutes + STATION_OVERHEAD_MIN, railKm, longHaul ? 1 : 0, money(perPerson * people), "Standard", "ESTIMATE");
        o.setSummary(longHaul ? "Overnight / multi-day train (typical)" : "Typical rail time");
        o.setBookingUrl(mapsLink(from, to, "transit"));
        o.setNotes(petNotes(TransportMode.TRAIN, pets));
        return o;
    }

    private TransportOption estimatedBus(Stop from, Stop to, double km, int people, int pets) {
        double roadKm = km * 1.25;
        int minutes = (int) Math.round(roadKm / 65.0 * 60);
        double perPerson = 10 + 0.07 * roadKm;
        TransportOption o = option(TransportMode.BUS, "Intercity coach", minutes, minutes + STATION_OVERHEAD_MIN, roadKm,
                km > 800 ? 1 : 0, money(perPerson * people), null, "ESTIMATE");
        o.setSummary("Typical coach time");
        o.setBookingUrl(mapsLink(from, to, "transit"));
        o.setNotes(petNotes(TransportMode.BUS, pets));
        return o;
    }

    private TransportOption estimatedFerry(Stop from, Stop to, double km, int people, int pets) {
        double seaKm = km * 1.15;
        int minutes = (int) Math.round(seaKm / 35.0 * 60);
        double perPerson = 25 + 0.30 * seaKm;
        TransportOption o = option(TransportMode.FERRY, "Passenger / car ferry", minutes, minutes + FERRY_OVERHEAD_MIN,
                seaKm, 0, money(perPerson * people), null, "ESTIMATE");
        o.setSummary("Scheduled ferry (typical)");
        o.setBookingUrl(mapsLink(from, to, "transit"));
        o.setNotes(petNotes(TransportMode.FERRY, pets));
        return o;
    }

    private TransportOption driveOption(Stop from, Stop to, double roadKm, int driveMinutes, int people, int pets, String source) {
        int cars = Math.max(1, (int) Math.ceil(people / 5.0));
        // keep to ~9h behind the wheel per day
        int drivingDays = Math.max(1, (int) Math.ceil(driveMinutes / (9.0 * 60)));
        int restMinutes = (drivingDays - 1) * 14 * 60;
        double fuel = roadKm * 0.085 * cars;
        int rooms = Math.max(1, (int) Math.ceil(people / 4.0));
        double motels = (drivingDays - 1) * 110.0 * rooms;
        TransportOption o = option(TransportMode.DRIVE, cars > 1 ? cars + " cars" : "Car", driveMinutes,
                driveMinutes + restMinutes + 20, roadKm, drivingDays - 1, money(fuel + motels), null, source);
        o.setSummary(drivingDays > 1 ? drivingDays + " driving days" : "Same-day drive");
        o.setBookingUrl(mapsLink(from, to, "driving"));
        StringBuilder notes = new StringBuilder("Fuel");
        if (drivingDays > 1) notes.append(" + ").append(drivingDays - 1).append(" roadside motel night(s)");
        notes.append(" included. Add a rental (~$60/day) if you don't have a car.");
        if (pets > 0) notes.append(" Easiest option with pets.");
        o.setNotes(notes.toString());
        return o;
    }

    private static TransportOption option(TransportMode mode, String provider, int minutes, int doorToDoor, double km,
                                          int transfers, BigDecimal total, String travelClass, String source) {
        TransportOption o = new TransportOption();
        o.setMode(mode);
        o.setProvider(provider);
        o.setDurationMinutes(minutes);
        o.setDoorToDoorMinutes(doorToDoor);
        o.setDistanceKm(Math.round(km * 10) / 10.0);
        o.setTransfers(transfers);
        o.setTotalPrice(total);
        o.setTravelClass(travelClass);
        o.setSource(source);
        return o;
    }

    // ranks by a "generalised cost": money + time + comfort penalties, then tags the three highlights
    static void rank(List<TransportOption> options) {
        if (options.isEmpty()) return;
        Map<TransportOption, Double> cost = new IdentityHashMap<>();
        for (TransportOption o : options) cost.put(o, generalisedCost(o));
        options.sort(Comparator.comparingDouble(cost::get));
        double best = cost.get(options.get(0));
        for (int i = 0; i < options.size(); i++) {
            TransportOption o = options.get(i);
            o.setRanking(i + 1);
            o.setPracticalityScore(Math.round(100.0 * best / Math.max(1, cost.get(o))));
            o.setTags(null);
        }
        TransportOption fastest = options.stream().min(Comparator.comparingInt(TransportOption::getDoorToDoorMinutes)).orElseThrow();
        TransportOption cheapest = options.stream().min(Comparator.comparing(TransportOption::getTotalPrice)).orElseThrow();
        double minPrice = Math.max(1, cheapest.getTotalPrice().doubleValue());
        double minTime = Math.max(1, fastest.getDoorToDoorMinutes());
        // best balance of price and time
        TransportOption budget = options.stream().min(Comparator.comparingDouble(
                o -> o.getTotalPrice().doubleValue() / minPrice + o.getDoorToDoorMinutes() / minTime)).orElseThrow();
        fastest.addTag(FASTEST);
        budget.addTag(BUDGET);
        cheapest.addTag(CHEAPEST);
    }

    static double generalisedCost(TransportOption o) {
        double hours = o.getDoorToDoorMinutes() / 60.0;
        double travelHours = o.getDurationMinutes() / 60.0;
        double people = Math.max(1, guessPeople(o));
        double c = o.getTotalPrice().doubleValue() + hours * VALUE_OF_HOUR * people;
        String notes = o.getNotes() == null ? "" : o.getNotes();
        switch (o.getMode()) {
            case DRIVE -> {
                if (travelHours > 10) c += (travelHours - 10) * 40 * people;
                if (notes.contains("pets")) c *= 0.9;
            }
            case BUS -> {
                if (travelHours > 12) c += (travelHours - 12) * 30 * people;
                if (notes.contains("Pets aren't allowed")) c += 5000;
            }
            case TRAIN -> {
                if (travelHours > 24) c += (travelHours - 24) * 20 * people;
            }
            case FLIGHT -> c += Math.max(0, o.getTransfers() == null ? 0 : o.getTransfers()) * 40;
            default -> { }
        }
        return c;
    }

    // the option itself doesn't store head count; leg/trip does
    private static int guessPeople(TransportOption o) {
        if (o.getLeg() != null && o.getLeg().getTrip() != null) return o.getLeg().getTrip().getTravellerCount();
        return 1;
    }

    private static BigDecimal petFlightFee(int pets) {
        return BigDecimal.valueOf(125L * pets);
    }

    private static String flightNotes(int pets) {
        return pets > 0 ? "Pets fly in-cabin for ~$125 each on most airlines (small pets only); larger pets go as cargo." : null;
    }

    static String petNotes(TransportMode mode, int pets) {
        if (pets <= 0) return null;
        return switch (mode) {
            case TRAIN -> "Many rail operators take small pets in a carrier for a small fee - check the operator.";
            case BUS -> "Pets aren't allowed on most intercity buses (service animals only).";
            case FERRY -> "Ferries usually allow pets on the car deck or in a pet area.";
            default -> null;
        };
    }

    static boolean sameLandRegion(String a, String b) {
        if (a == null || b == null) return true;
        if (a.equalsIgnoreCase(b)) return true;
        Set<String> northAmerica = Set.of("US", "CA", "MX");
        if (northAmerica.contains(a.toUpperCase()) && northAmerica.contains(b.toUpperCase())) return true;
        return EU.contains(a.toUpperCase()) && EU.contains(b.toUpperCase()) && !(a.equalsIgnoreCase("IE") ^ b.equalsIgnoreCase("IE"));
    }

    // the one big "same country but no road" case worth special-casing
    static boolean isHawaii(Stop s) {
        return s.getLatitude() != null && s.getLongitude() != null
                && s.getLatitude() > 18 && s.getLatitude() < 23 && s.getLongitude() > -161 && s.getLongitude() < -154;
    }

    private static String timeOf(String iso) {
        if (iso == null || iso.length() < 16) return null;
        int t = iso.indexOf('T');
        return t > 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : null;
    }

    private static String mapsLink(Stop from, Stop to, String mode) {
        return "https://www.google.com/maps/dir/?api=1&origin=" + URLEncoder.encode(from.getDisplayName(), StandardCharsets.UTF_8)
                + "&destination=" + URLEncoder.encode(to.getDisplayName(), StandardCharsets.UTF_8) + "&travelmode=" + mode;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
