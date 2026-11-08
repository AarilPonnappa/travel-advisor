package com.example.jarvis.Travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.jarvis.Flights.FlightOption;
import com.example.jarvis.Flights.FlightProvider;
import com.example.jarvis.Flights.FlightSearchRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Runs without a Gemini key, so it only exercises the wiring and the flight hand-off. */
@SpringBootTest(properties = "gemini.api-key=")
class TravelServiceTest {

    @TestConfiguration
    static class FakeAirline {
        @Bean
        FlightProvider fakeFlights() {
            return new FlightProvider() {
                public String name() { return "fake"; }
                public List<FlightOption> search(FlightSearchRequest r) {
                    return List.of(
                            new FlightOption("B", List.of("B2"), null, null, 1, 300, 450, "CAD", "pricier", null),
                            new FlightOption("A", List.of("A1"), null, null, 0, 180, 300 * r.travellers(), "CAD", "cheap", null));
                }
            };
        }
    }

    @Autowired
    TravelService travel;

    @Test
    void returnsFlightsSortedByPriceAndExplainsMissingRoutes() {
        TravelOptions options = travel.plan(new TravelRequest("Vancouver", "Calgary",
                LocalDate.of(2027, 7, 1), null, 1, "quick trip", null, null, null, null));

        assertEquals(List.of("A", "B"), options.flights().stream().map(FlightOption::carrier).toList());
        assertNull(options.roadTrip());
        assertTrue(options.notes().stream().anyMatch(n -> n.contains("Gemini is not configured")));
        assertEquals(3, options.preferences().budget());
    }
}
