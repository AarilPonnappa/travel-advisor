package com.example.jarvis.Flights;

import java.util.List;

/**
 * Connection point to the airline side of the project (itineraries + pricing).
 *
 * {@link GoogleFlightsProvider} adapts the airline side's FlightService. {@code TravelService} picks up
 * every bean of this type automatically, so another fare source only needs another implementation.
 */
public interface FlightProvider {

    /** A short name used in logs and notes, e.g. "airline-search". */
    String name();

    /** False when the provider has no API keys, so the planner can say why there are no flights. */
    default boolean isConfigured() {
        return true;
    }

    List<FlightOption> search(FlightSearchRequest request);
}
