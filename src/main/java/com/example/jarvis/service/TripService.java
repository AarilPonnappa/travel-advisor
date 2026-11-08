package com.example.jarvis.service;

import com.example.jarvis.dto.DatesForm;
import com.example.jarvis.dto.GeoPoint;
import com.example.jarvis.dto.MoneySummary;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.ItineraryRepository;
import com.example.jarvis.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class TripService {

    private final TripRepository trips;
    private final ItineraryRepository itineraries;
    private final GeoService geo;

    public TripService(TripRepository trips, ItineraryRepository itineraries, GeoService geo) {
        this.trips = trips;
        this.itineraries = itineraries;
        this.geo = geo;
    }

    @Transactional(readOnly = true)
    public Trip get(Long id) {
        return trips.findById(id).orElseThrow(() -> new NotFoundException("Trip " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Trip> recent() {
        return trips.findTop20ByOrderByCreatedAtDesc();
    }

    @Transactional
    public Trip create(List<String> places, String currency) {
        List<String> cleaned = new ArrayList<>();
        if (places != null) {
            for (String p : places) {
                if (p != null && !p.isBlank()) cleaned.add(p.trim());
            }
        }
        if (cleaned.size() < 2) throw new IllegalArgumentException("Enter at least a starting point and a destination.");
        for (int i = 1; i < cleaned.size(); i++) {
            if (cleaned.get(i).equalsIgnoreCase(cleaned.get(i - 1))) {
                throw new IllegalArgumentException("Two stops in a row are the same place (" + cleaned.get(i) + ").");
            }
        }

        Trip trip = new Trip();
        trip.setCurrency(currency);
        List<String> notFound = new ArrayList<>();
        for (int i = 0; i < cleaned.size(); i++) {
            String name = cleaned.get(i);
            Optional<GeoPoint> point = geo.geocode(name);
            if (point.isEmpty()) {
                notFound.add(name);
                continue;
            }
            Stop s = new Stop();
            s.setTrip(trip);
            s.setPosition(i);
            s.setName(name);
            s.setFormattedName(point.get().formattedName());
            s.setLatitude(point.get().latitude());
            s.setLongitude(point.get().longitude());
            s.setCountryCode(point.get().countryCode());
            trip.getStops().add(s);
        }
        if (!notFound.isEmpty()) {
            throw new IllegalArgumentException("Couldn't find: " + String.join(", ", notFound)
                    + ". Try adding the country, e.g. \"Victoria, Canada\".");
        }
        // stops get their ids first, legs point at them
        trip = trips.save(trip);
        for (int i = 0; i + 1 < trip.getStops().size(); i++) {
            Stop a = trip.getStops().get(i);
            Stop b = trip.getStops().get(i + 1);
            Leg leg = new Leg();
            leg.setTrip(trip);
            leg.setSeq(i);
            leg.setFromStop(a);
            leg.setToStop(b);
            leg.setDistanceKm(GeoService.haversineKm(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude()));
            trip.getLegs().add(leg);
        }
        return trips.save(trip);
    }

    public DatesForm datesFormFor(Trip trip) {
        DatesForm form = new DatesForm();
        form.setAdults(trip.getAdults());
        form.setChildren(trip.getChildren());
        form.setPets(trip.getPets());
        form.setBudget(trip.getBudget());
        List<Stop> stops = trip.getStops();
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            DatesForm.StopDates sd = new DatesForm.StopDates();
            sd.setStopId(s.getId());
            sd.setName(s.getDisplayName());
            sd.setOrigin(i == 0);
            sd.setLast(i == stops.size() - 1);
            sd.setArriveDate(s.getArriveDate());
            sd.setLeaveDate(s.getLeaveDate());
            form.getStops().add(sd);
        }
        return form;
    }

    // returns a list of problems, empty when everything was saved
    @Transactional
    public List<String> saveDates(Long tripId, DatesForm form) {
        Trip trip = get(tripId);
        List<String> errors = validateDates(trip, form);
        if (!errors.isEmpty()) return errors;

        boolean datesChanged = false;
        Map<Long, DatesForm.StopDates> byId = new HashMap<>();
        for (DatesForm.StopDates sd : form.getStops()) byId.put(sd.getStopId(), sd);
        for (Stop s : trip.getStops()) {
            DatesForm.StopDates sd = byId.get(s.getId());
            LocalDate arrive = s.isOrigin() ? null : sd.getArriveDate();
            LocalDate leave = sd.getLeaveDate();
            if (!Objects.equals(arrive, s.getArriveDate()) || !Objects.equals(leave, s.getLeaveDate())) datesChanged = true;
            s.setArriveDate(arrive);
            s.setLeaveDate(leave);
        }
        boolean peopleChanged = trip.getAdults() != form.getAdults() || trip.getChildren() != form.getChildren()
                || trip.getPets() != form.getPets();
        trip.setAdults(form.getAdults());
        trip.setChildren(form.getChildren());
        trip.setPets(form.getPets());
        trip.setBudget(form.getBudget() != null && form.getBudget().signum() > 0 ? form.getBudget() : null);

        for (Leg leg : trip.getLegs()) {
            LocalDate travel = leg.getFromStop().getLeaveDate();
            if (!Objects.equals(travel, leg.getTravelDate())) leg.getOptions().clear();
            leg.setTravelDate(travel);
            if (peopleChanged) leg.getOptions().clear();
        }
        if (datesChanged || peopleChanged) {
            // stays priced for other dates or group sizes are no longer valid
            for (Stop s : trip.getStops()) {
                Set<LocalDate> nights = new HashSet<>(s.getNights());
                if (peopleChanged) {
                    s.getBookings().clear();
                } else {
                    // nights are contiguous, so checking both ends is enough
                    s.getBookings().removeIf(b -> !nights.contains(b.getCheckIn())
                            || !nights.contains(b.getCheckOut().minusDays(1)));
                }
                s.getSkippedNights().removeIf(n -> !nights.contains(n));
            }
        }
        if (datesChanged) trip.getDayPlans().clear();
        trips.save(trip);
        return List.of();
    }

    List<String> validateDates(Trip trip, DatesForm form) {
        List<String> errors = new ArrayList<>();
        List<Stop> stops = trip.getStops();
        if (form.getStops().size() != stops.size()) {
            errors.add("The form doesn't match this trip, please reload the page.");
            return errors;
        }
        if (form.getAdults() < 1) errors.add("At least one adult has to travel.");
        if (form.getChildren() < 0 || form.getPets() < 0) errors.add("Children and pets can't be negative.");
        LocalDate today = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate prevLeave = null;
        for (int i = 0; i < stops.size(); i++) {
            DatesForm.StopDates sd = form.getStops().get(i);
            Stop s = stops.get(i);
            if (!s.getId().equals(sd.getStopId())) {
                errors.add("The form doesn't match this trip, please reload the page.");
                return errors;
            }
            String name = s.getName();
            if (i == 0) {
                if (sd.getLeaveDate() == null) errors.add("Pick the date you leave " + name + ".");
                else if (sd.getLeaveDate().isBefore(today)) errors.add("The departure from " + name + " is in the past.");
                prevLeave = sd.getLeaveDate();
                continue;
            }
            if (sd.getArriveDate() == null) errors.add("Pick the date you arrive in " + name + ".");
            if (sd.getLeaveDate() == null) {
                errors.add(i == stops.size() - 1 ? "Pick the date your trip ends in " + name + "."
                        : "Pick the date you leave " + name + ".");
            }
            if (sd.getArriveDate() != null && prevLeave != null && sd.getArriveDate().isBefore(prevLeave)) {
                errors.add("You can't arrive in " + name + " before leaving the previous stop.");
            }
            if (sd.getArriveDate() != null && sd.getArriveDate().isAfter(prevLeave == null ? sd.getArriveDate() : prevLeave.plusDays(3))) {
                errors.add("Arrival in " + name + " is more than 3 days after leaving the previous stop. Add a stop in between if you're staying somewhere.");
            }
            if (sd.getArriveDate() != null && sd.getLeaveDate() != null && sd.getLeaveDate().isBefore(sd.getArriveDate())) {
                errors.add("You leave " + name + " before you arrive.");
            }
            prevLeave = sd.getLeaveDate();
        }
        if (errors.isEmpty()) {
            LocalDate start = form.getStops().get(0).getLeaveDate();
            LocalDate end = form.getStops().get(stops.size() - 1).getLeaveDate();
            if (start.plusDays(60).isBefore(end)) errors.add("Trips are capped at 60 days.");
        }
        return errors;
    }

    public boolean datesComplete(Trip trip) {
        for (Stop s : trip.getStops()) {
            if (s.getLeaveDate() == null) return false;
            if (!s.isOrigin() && s.getArriveDate() == null) return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public MoneySummary money(Trip trip) {
        BigDecimal transport = BigDecimal.ZERO;
        for (Leg leg : trip.getLegs()) {
            TransportOption o = leg.getSelectedOption();
            if (o != null && o.getTotalPrice() != null) transport = transport.add(o.getTotalPrice());
        }
        BigDecimal stays = BigDecimal.ZERO;
        for (Stop s : trip.getStops()) {
            for (StayBooking b : s.getBookings()) {
                if (b.getTotalPrice() != null) stays = stays.add(b.getTotalPrice());
            }
        }
        BigDecimal activities = BigDecimal.ZERO;
        if (trip.getId() != null) {
            activities = itineraries.findFirstByTripIdOrderByCreatedAtDesc(trip.getId())
                    .map(Itinerary::getEstimatedActivitySpend)
                    .orElse(BigDecimal.ZERO);
            if (activities == null) activities = BigDecimal.ZERO;
        }
        return new MoneySummary(transport, stays, activities, trip.getBudget(), trip.getCurrency());
    }
}
