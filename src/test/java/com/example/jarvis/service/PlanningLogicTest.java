package com.example.jarvis.service;

import com.example.jarvis.dto.DatesForm;
import com.example.jarvis.dto.WeatherDay;
import com.example.jarvis.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ranking, weather rules, date validation and the offline planner
class PlanningLogicTest {

    private static TransportOption opt(TransportMode mode, int minutes, int doorToDoor, String price) {
        TransportOption o = new TransportOption();
        o.setMode(mode);
        o.setDurationMinutes(minutes);
        o.setDoorToDoorMinutes(doorToDoor);
        o.setTotalPrice(new BigDecimal(price));
        o.setTransfers(0);
        return o;
    }

    @Test
    void rankingTagsFastestBudgetAndCheapest() {
        TransportOption flight = opt(TransportMode.FLIGHT, 360, 510, "700");
        TransportOption train = opt(TransportMode.TRAIN, 3600, 3640, "450");
        TransportOption bus = opt(TransportMode.BUS, 3300, 3340, "260");
        TransportOption drive = opt(TransportMode.DRIVE, 2700, 4600, "650");
        List<TransportOption> all = new ArrayList<>(List.of(train, bus, drive, flight));
        TransportService.rank(all);

        assertThat(all.get(0)).isSameAs(flight);
        assertThat(flight.getRanking()).isEqualTo(1);
        assertThat(flight.hasTag(TransportService.FASTEST)).isTrue();
        assertThat(bus.hasTag(TransportService.CHEAPEST)).isTrue();
        assertThat(all.stream().filter(o -> o.hasTag(TransportService.BUDGET)).count()).isEqualTo(1);
        assertThat(all).extracting(TransportOption::getRanking).containsExactly(1, 2, 3, 4);
    }

    @Test
    void busWithPetsDropsToTheBottom() {
        TransportOption bus = opt(TransportMode.BUS, 240, 280, "40");
        bus.setNotes(TransportService.petNotes(TransportMode.BUS, 1));
        TransportOption train = opt(TransportMode.TRAIN, 240, 280, "120");
        List<TransportOption> all = new ArrayList<>(List.of(bus, train));
        TransportService.rank(all);
        assertThat(all.get(0)).isSameAs(train);
        assertThat(bus.hasTag(TransportService.CHEAPEST)).isTrue();
    }

    @Test
    void pickFlightsKeepsBestCheapestFastestWithoutDuplicates() {
        var a = new com.example.jarvis.dto.FlightQuote("A", 400, 1, new BigDecimal("300"), null, null, null, "LIVE");
        var b = new com.example.jarvis.dto.FlightQuote("B", 350, 0, new BigDecimal("500"), null, null, null, "LIVE");
        var c = new com.example.jarvis.dto.FlightQuote("C", 600, 2, new BigDecimal("250"), null, null, null, "LIVE");
        assertThat(TransportService.pickFlights(List.of(a, b, c))).containsExactly(a, c, b);
        assertThat(TransportService.pickFlights(List.of(a))).containsExactly(a);
    }

    @Test
    void weatherRulesFlagBlizzardAndSuggestIndoors() {
        WeatherDay blizzard = new WeatherDay(LocalDate.now(), 75, -5.0, -12.0, 30.0, 60.0, "Heavy snow", "FORECAST");
        assertThat(WeatherService.severeReason(blizzard)).contains("blizzard");
        assertThat(WeatherService.saferAlternative(ActivityType.ADVENTURE, blizzard)).isEqualTo(ActivityType.HISTORICAL);
        assertThat(WeatherService.saferAlternative(ActivityType.HISTORICAL, blizzard)).isNull();
        WeatherDay nice = new WeatherDay(LocalDate.now(), 1, 22.0, 12.0, 0.0, 10.0, "Partly cloudy", "FORECAST");
        assertThat(WeatherService.severeReason(nice)).isNull();

        DayPlan p = new DayPlan();
        p.setDate(LocalDate.now());
        p.setActivityType(ActivityType.ADVENTURE);
        DayPlannerService.applyWeather(p, blizzard);
        DayPlannerService.assess(p);
        assertThat(p.isSevereWeather()).isTrue();
        assertThat(p.getSuggestedType()).isEqualTo(ActivityType.HISTORICAL);
        assertThat(p.getWeatherWarning()).contains("Adventure").contains("historical");

        p.setActivityType(ActivityType.HISTORICAL);
        DayPlannerService.assess(p);
        assertThat(p.getSuggestedType()).isNull();
        assertThat(p.getWeatherWarning()).startsWith("Heads up");
    }

    @Test
    void bestMonthsFallbackFavoursSummerUpNorth() {
        WeatherService w = new WeatherService(org.springframework.web.client.RestClient.builder(), "http://127.0.0.1:1/f", "http://127.0.0.1:1/a");
        var best = w.bestMonths(47.6, -122.3, ActivityType.ADVENTURE);
        assertThat(best.months()).hasSize(3);
        assertThat(best.months()).containsAnyOf("June", "July", "August", "September");
        var south = w.bestMonths(-33.9, 151.2, ActivityType.SCENIC);
        assertThat(south.months()).containsAnyOf("December", "January", "February", "March", "November");
    }

    private static Trip tripWithStops(int count) {
        Trip t = new Trip();
        for (int i = 0; i < count; i++) {
            Stop s = new Stop();
            s.setId((long) (i + 1));
            s.setPosition(i);
            s.setName("City" + i);
            s.setLatitude(40.0 + i);
            s.setLongitude(-100.0 - i);
            s.setTrip(t);
            t.getStops().add(s);
        }
        return t;
    }

    private static DatesForm form(Trip t, LocalDate... dates) {
        // dates: leave0, arrive1, leave1, arrive2, leave2 ...
        DatesForm f = new DatesForm();
        int d = 0;
        for (int i = 0; i < t.getStops().size(); i++) {
            DatesForm.StopDates sd = new DatesForm.StopDates();
            sd.setStopId(t.getStops().get(i).getId());
            if (i > 0) sd.setArriveDate(dates[d++]);
            sd.setLeaveDate(dates[d++]);
            f.getStops().add(sd);
        }
        return f;
    }

    @Test
    void dateValidation() {
        TripService svc = new TripService(null, null, null);
        Trip t = tripWithStops(3);
        LocalDate base = LocalDate.now().plusDays(30);
        assertThat(svc.validateDates(t, form(t, base, base, base.plusDays(3), base.plusDays(3), base.plusDays(6)))).isEmpty();
        assertThat(svc.validateDates(t, form(t, base, base.minusDays(1), base.plusDays(3), base.plusDays(3), base.plusDays(6))))
                .anyMatch(e -> e.contains("before leaving"));
        assertThat(svc.validateDates(t, form(t, base, base, base.minusDays(2), base.plusDays(3), base.plusDays(6))))
                .anyMatch(e -> e.contains("leave City1 before you arrive"));
        assertThat(svc.validateDates(t, form(t, LocalDate.now().minusDays(5), base, base.plusDays(3), base.plusDays(3), base.plusDays(6))))
                .anyMatch(e -> e.contains("past"));
        DatesForm noPeople = form(t, base, base, base.plusDays(3), base.plusDays(3), base.plusDays(6));
        noPeople.setAdults(0);
        assertThat(svc.validateDates(t, noPeople)).anyMatch(e -> e.contains("adult"));
    }

    @Test
    void nightsSkipsAndBookingsOnAStop() {
        Trip t = tripWithStops(2);
        Stop s = t.getStops().get(1);
        LocalDate in = LocalDate.of(2026, 12, 1);
        s.setArriveDate(in);
        s.setLeaveDate(in.plusDays(4));
        assertThat(s.getNights()).hasSize(4);
        StayBooking b = new StayBooking();
        b.setCheckIn(in);
        b.setCheckOut(in.plusDays(2));
        s.getBookings().add(b);
        s.getSkippedNights().add(in.plusDays(3));
        assertThat(s.getOpenNights()).containsExactly(in.plusDays(2));
        assertThat(s.getFirstOpenNight()).isEqualTo(in.plusDays(2));
        assertThat(s.getFirstOpenBlockEnd()).isEqualTo(in.plusDays(3));
        s.getSkippedNights().add(in.plusDays(2));
        assertThat(s.isStaySorted()).isTrue();
        assertThat(t.getStops().get(0).getNights()).isEmpty();
    }

    @Test
    void daysForCoversEachDateOnce() {
        Trip t = tripWithStops(3);
        LocalDate d = LocalDate.of(2026, 12, 1);
        t.getStops().get(0).setLeaveDate(d);
        t.getStops().get(1).setArriveDate(d);
        t.getStops().get(1).setLeaveDate(d.plusDays(3));
        t.getStops().get(2).setArriveDate(d.plusDays(3));
        t.getStops().get(2).setLeaveDate(d.plusDays(5));
        List<Object[]> days = DayPlannerService.daysFor(t);
        assertThat(days).hasSize(6);
        assertThat(days.get(0)[2]).isEqualTo(true);
        assertThat(((Stop) days.get(3)[0]).getName()).isEqualTo("City2");
        assertThat(days.get(5)[1]).isEqualTo(d.plusDays(5));
    }

    @Test
    void fallbackPlannerAndModelJsonHandling() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ItineraryService svc = new ItineraryService(null, null, null, null, null, null, null);
        Trip t = tripWithStops(2);
        t.setAdults(2);
        t.setChildren(0);
        Stop s = t.getStops().get(1);
        ItineraryService.StopContext c = new ItineraryService.StopContext();
        c.stop = s;
        c.baseName = "Hotel X";
        c.pointNames.add("Hotel X");
        c.minutes = new int[][]{{0}};
        for (int i = 0; i < 2; i++) {
            DayPlan p = new DayPlan();
            p.setStop(s);
            p.setDate(LocalDate.of(2026, 12, 1 + i));
            p.setTravelDay(i == 0);
            p.setActivityType(i == 0 ? ActivityType.SCENIC : ActivityType.PARTY);
            c.days.add(p);
        }
        JsonNode plan = svc.fallbackPlan(t, c);
        assertThat(plan.path("days")).hasSize(2);
        JsonNode party = plan.path("days").get(1);
        assertThat(party.path("items").toString()).contains("NIGHTLIFE").contains("MEAL");
        assertThat(party.path("estimatedDayCost").asInt()).isPositive();
        ItineraryService.validatePlan(plan, c);

        var day = svc.toDay(party, 1);
        assertThat(day.getItems()).isNotEmpty();
        assertThat(day.getEstimatedCost()).isPositive();

        JsonNode fenced = svc.parseModelJson("```json\n{\"days\":[{\"date\":\"2026-12-01\",\"items\":[{}]},"
                + "{\"date\":\"2026-12-02\",\"items\":[{}]},{\"date\":\"2030-01-01\",\"items\":[{}]}]}\n```");
        ItineraryService.validatePlan(fenced, c);
        assertThat(fenced.path("days")).hasSize(2);
        assertThatThrownBy(() -> ItineraryService.validatePlan(om.readTree("{\"days\":[{\"date\":\"2026-12-01\",\"items\":[{}]}]}"), c))
                .hasMessageContaining("skipped");
    }
}
