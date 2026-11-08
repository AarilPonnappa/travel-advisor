package com.example.jarvis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// nearest airports from the bundled OurAirports list, so flight search needs no lookup api
@Service
public class AirportService {

    private static final Logger log = LoggerFactory.getLogger(AirportService.class);
    static final double MAX_KM = 150;
    static final double METRO_KM = 60;

    public record Airport(String iata, boolean large, double lat, double lon, String country, String city, String name) {
    }

    private final List<Airport> airports;

    public AirportService() {
        this(AirportService.class.getResourceAsStream("/data/airports.csv"));
    }

    AirportService(InputStream in) {
        this.airports = load(in);
    }

    static List<Airport> load(InputStream in) {
        List<Airport> out = new ArrayList<>();
        if (in == null) {
            log.warn("airports.csv missing from the classpath, flight search will use estimates");
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split(",", 7);
                if (p.length < 7) continue;
                out.add(new Airport(p[0], "L".equals(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), p[4], p[5], p[6]));
            }
        } catch (Exception e) {
            log.warn("couldn't read airports.csv: {}", e.getMessage());
        }
        return out;
    }

    public int size() {
        return airports.size();
    }

    // big airports first, then distance. returns the main one plus others serving the same metro area
    public List<Airport> nearest(double lat, double lon, int max) {
        record Scored(Airport a, double km) {
        }
        List<Scored> close = new ArrayList<>();
        for (Airport a : airports) {
            double km = GeoService.haversineKm(lat, lon, a.lat(), a.lon());
            if (km <= MAX_KM) close.add(new Scored(a, km));
        }
        if (close.isEmpty()) return List.of();
        // a medium airport 20km away beats a large one 140km away
        close.sort(Comparator.comparingDouble(s -> s.km() * (s.a().large() ? 1.0 : 2.5)));
        List<Airport> out = new ArrayList<>();
        out.add(close.get(0).a());
        for (int i = 1; i < close.size() && out.size() < max; i++) {
            Scored s = close.get(i);
            if (s.a().large() && s.km() <= METRO_KM) out.add(s.a());
        }
        return out;
    }
}
