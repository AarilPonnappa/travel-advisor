package com.example.jarvis.Flights;

import java.util.List;

/**
 * Connection point to the airline side of the project (itineraries + pricing).
 *
 * The airline module should expose one Spring bean implementing this interface, adapting its own
 * itinerary/price objects into {@link FlightOption}. {@code TravelService} picks up every bean of this
 * type automatically; if none exist the combined endpoint simply returns no flights.
 */
public interface FlightProvider {

    /** A short name used in logs and notes, e.g. "airline-search". */
    String name();

    List<FlightOption> search(FlightSearchRequest request);
}
