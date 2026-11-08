package com.example.jarvis.service;

import com.example.jarvis.dto.BestMonths;
import com.example.jarvis.dto.PlannerForm;
import com.example.jarvis.dto.WeatherDay;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class DayPlannerService {

    private final TripRepository trips;
    private final WeatherService weather;

    public DayPlannerService(TripRepository trips, WeatherService weather) {
        this.trips = trips;
        this.weather = weather;
    }

    // one entry per calendar day we're somewhere (not the origin)
    static List<Object[]> daysFor(Trip trip) {
        List<Object[]> days = new ArrayList<>();
        List<Stop> stops = trip.getStops();
        for (int i = 1; i < stops.size(); i++) {
            Stop s = stops.get(i);
            if (s.getArriveDate() == null || s.getLeaveDate() == null) continue;
            boolean last = i == stops.size() - 1;
            LocalDate end = last ? s.getLeaveDate() : s.getLeaveDate().minusDays(1);
            for (LocalDate d = s.getArriveDate(); !d.isAfter(end); d = d.plusDays(1)) {
                days.add(new Object[]{s, d, d.equals(s.getArriveDate())});
            }
        }
        return days;
    }

    @Transactional
    public Trip prepare(Long tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        List<Object[]> wanted = daysFor(trip);
        boolean matches = trip.getDayPlans().size() == wanted.size();
        if (matches) {
            for (int i = 0; i < wanted.size(); i++) {
                DayPlan p = trip.getDayPlans().get(i);
                if (!p.getDate().equals(wanted.get(i)[1]) || !p.getStop().getId().equals(((Stop) wanted.get(i)[0]).getId())) {
                    matches = false;
                    break;
                }
            }
        }
        if (!matches) {
            Map<LocalDate, ActivityType> previous = new HashMap<>();
            for (DayPlan p : trip.getDayPlans()) previous.put(p.getDate(), p.getActivityType());
            trip.getDayPlans().clear();
            for (Object[] w : wanted) {
                DayPlan p = new DayPlan();
                p.setTrip(trip);
                p.setStop((Stop) w[0]);
                p.setDate((LocalDate) w[1]);
                p.setTravelDay((Boolean) w[2]);
                p.setActivityType(previous.getOrDefault(p.getDate(), ActivityType.RELAXING));
                trip.getDayPlans().add(p);
            }
        }
        refreshWeather(trip);
        return trips.save(trip);
    }

    void refreshWeather(Trip trip) {
        Map<Stop, List<DayPlan>> byStop = new LinkedHashMap<>();
        for (DayPlan p : trip.getDayPlans()) byStop.computeIfAbsent(p.getStop(), k -> new ArrayList<>()).add(p);
        for (Map.Entry<Stop, List<DayPlan>> e : byStop.entrySet()) {
            Stop s = e.getKey();
            List<DayPlan> plans = e.getValue();
            // skip the network if we already have weather for every day
            boolean have = plans.stream().allMatch(p -> p.getWeatherSource() != null);
            if (!have) {
                Map<LocalDate, WeatherDay> w = weather.daily(s.getLatitude(), s.getLongitude(),
                        plans.get(0).getDate(), plans.get(plans.size() - 1).getDate());
                for (DayPlan p : plans) applyWeather(p, w.get(p.getDate()));
            }
            for (DayPlan p : plans) assess(p);
        }
    }

    static void applyWeather(DayPlan p, WeatherDay w) {
        if (w == null) {
            p.setWeatherSummary("No weather data");
            p.setWeatherSource(null);
            return;
        }
        p.setWeatherCode(w.code());
        p.setWeatherSummary(w.summary());
        p.setTempMaxC(w.tempMaxC());
        p.setTempMinC(w.tempMinC());
        p.setPrecipitationMm(w.precipitationMm());
        p.setWindKmh(w.windKmh());
        p.setWeatherSource(w.source());
    }

    // sets the warning + suggestion for the currently chosen activity type
    static void assess(DayPlan p) {
        WeatherDay w = p.getWeatherSource() == null ? null : new WeatherDay(p.getDate(), p.getWeatherCode(), p.getTempMaxC(),
                p.getTempMinC(), p.getPrecipitationMm(), p.getWindKmh(), p.getWeatherSummary(), p.getWeatherSource());
        String reason = WeatherService.severeReason(w);
        p.setSevereWeather(reason != null);
        p.setWeatherRisk(reason);
        if (reason != null && p.getActivityType().isOutdoor()) {
            ActivityType alt = WeatherService.saferAlternative(p.getActivityType(), w);
            String when = "TYPICAL".equals(p.getWeatherSource()) ? "Weather around this date is usually" : "Forecast shows";
            p.setWeatherWarning(when + " " + reason + ". " + p.getActivityType().getLabel()
                    + " plans could be unsafe - consider a " + alt.getLabel().toLowerCase() + " day instead.");
            p.setSuggestedType(alt);
        } else if (reason != null) {
            p.setWeatherWarning("Heads up: " + reason + " expected.");
            p.setSuggestedType(null);
        } else {
            p.setWeatherWarning(null);
            p.setSuggestedType(null);
        }
    }

    @Transactional
    public void save(Long tripId, PlannerForm form) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        Map<Long, ActivityType> chosen = new HashMap<>();
        for (PlannerForm.DayChoice c : form.getDays()) {
            if (c.getDayPlanId() != null && c.getActivityType() != null) chosen.put(c.getDayPlanId(), c.getActivityType());
        }
        for (DayPlan p : trip.getDayPlans()) {
            ActivityType t = chosen.get(p.getId());
            if (t != null) p.setActivityType(t);
            assess(p);
        }
        trip.setInterests(trimTo(form.getInterests(), 1000));
        trip.setCuisines(trimTo(form.getCuisines(), 500));
        trips.save(trip);
    }

    // best months for each activity type picked at each stop
    public Map<Long, List<BestMonths>> bestMonths(Trip trip) {
        Map<Long, List<BestMonths>> out = new LinkedHashMap<>();
        Map<Long, Set<ActivityType>> typesByStop = new LinkedHashMap<>();
        for (DayPlan p : trip.getDayPlans()) {
            typesByStop.computeIfAbsent(p.getStop().getId(), k -> new LinkedHashSet<>()).add(p.getActivityType());
        }
        for (Stop s : trip.getStops()) {
            Set<ActivityType> types = typesByStop.get(s.getId());
            if (types == null) continue;
            List<BestMonths> list = new ArrayList<>();
            for (ActivityType t : types) list.add(weather.bestMonths(s.getLatitude(), s.getLongitude(), t));
            out.put(s.getId(), list);
        }
        return out;
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
