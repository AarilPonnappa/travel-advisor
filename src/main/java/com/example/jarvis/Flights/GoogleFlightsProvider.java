package com.example.jarvis.Flights;

import com.example.jarvis.dto.FlightQuote;
import com.example.jarvis.dto.GeoPoint;
import com.example.jarvis.model.CabinClass;
import com.example.jarvis.service.FlightService;
import com.example.jarvis.service.GeoService;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connects the combined /api/travel endpoint to the airline side: geocodes the place names with
 * {@link GeoService}, then asks {@link FlightService} (SerpApi, then SearchApi, then Duffel) for fares.
 */
@Component
public class GoogleFlightsProvider implements FlightProvider {

    private final FlightService flights;
    private final GeoService geo;
    private final String currency;

    public GoogleFlightsProvider(FlightService flights, GeoService geo,
                                 @Value("${jarvis.currency:USD}") String currency) {
        this.flights = flights;
        this.geo = geo;
        this.currency = currency;
    }

    @Override
    public String name() {
        return "google-flights";
    }

    @Override
    public boolean isConfigured() {
        return flights.isConfigured();
    }

    @Override
    public List<FlightOption> search(FlightSearchRequest request) {
        if (!flights.isConfigured() || request.departureDate() == null) return List.of();
        Optional<GeoPoint> from = geo.geocode(request.origin());
        Optional<GeoPoint> to = geo.geocode(request.destination());
        if (from.isEmpty() || to.isEmpty()) return List.of();

        return flights.search(from.get().latitude(), from.get().longitude(),
                        to.get().latitude(), to.get().longitude(),
                        request.departureDate(), request.travellers(), 0, CabinClass.ECONOMY)
                .stream()
                .map(this::toOption)
                .toList();
    }

    private FlightOption toOption(FlightQuote q) {
        return new FlightOption(q.carrier(), q.durationMinutes(), q.stops(), q.totalPrice(), currency,
                q.departTime(), q.arriveTime(), q.bookingUrl(), q.source());
    }
}
