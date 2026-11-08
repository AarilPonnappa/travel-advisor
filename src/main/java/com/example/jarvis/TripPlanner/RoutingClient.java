package com.example.jarvis.TripPlanner;

import com.example.jarvis.Lodging.LatLon;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Turns a list of waypoints into a drivable road geometry using an OSRM server. */
@Component
public class RoutingClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingClient.class);

    public record DrivingRoute(List<LatLon> geometry, double minutes, double km) {}

    private final ObjectMapper mapper;
    private final RestTemplate rest;
    private final String baseUrl;

    public RoutingClient(ObjectMapper mapper,
                         @Value("${osrm.url:https://router.project-osrm.org}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.rest = new RestTemplate(factory);
    }

    /** Empty if the routing server is unreachable or cannot connect the waypoints. */
    public Optional<DrivingRoute> route(List<LatLon> waypoints) {
        if (waypoints == null || waypoints.size() < 2) return Optional.empty();
        String coords = waypoints.stream()
                .map(p -> String.format(Locale.ROOT, "%.5f,%.5f", p.lon(), p.lat())) // OSRM wants lon,lat
                .collect(Collectors.joining(";"));
        String url = baseUrl + "/route/v1/driving/" + coords + "?overview=simplified&geometries=geojson";
        try {
            JsonNode route = mapper.readTree(rest.getForObject(url, String.class)).path("routes").path(0);
            if (route.isMissingNode()) return Optional.empty();
            List<LatLon> geometry = new ArrayList<>();
            for (JsonNode c : route.path("geometry").path("coordinates")) {
                geometry.add(new LatLon(c.get(1).asDouble(), c.get(0).asDouble()));
            }
            if (geometry.size() < 2) return Optional.empty();
            return Optional.of(new DrivingRoute(geometry,
                    route.path("duration").asDouble() / 60.0,
                    route.path("distance").asDouble() / 1000.0));
        } catch (Exception e) {
            log.warn("Routing failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
