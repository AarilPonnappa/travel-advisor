package com.example.jarvis.Lodging;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

/** Finds lodging within a radius of a polyline using the Overpass API (OpenStreetMap data). */
@Component
public class OverPassClient {

    private final String url;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final RestTemplate rest;
    private final Map<String, List<Lodging>> cache = new ConcurrentHashMap<>(); // be kind to the public server

    public OverPassClient(tools.jackson.databind.ObjectMapper mapper,
                          @Value("${overpass.url:https://overpass-api.de/api/interpreter}") String url) {
        this.mapper = mapper;
        this.url = url;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(45_000);
        this.rest = new RestTemplate(factory);
    }

    public List<Lodging> findLodging(List<LatLon> line, int radiusMeters) {
        String query = buildQuery(line, radiusMeters);
        List<Lodging> cached = cache.get(query);
        if (cached != null) return cached;
        List<Lodging> result = execute(query);
        cache.put(query, result);
        return result;
    }

    /** "around" with a coordinate list matches anything within the radius of the whole polyline. */
    static String buildQuery(List<LatLon> line, int radiusMeters) {
        StringBuilder coords = new StringBuilder();
        for (LatLon p : line) {
            if (coords.length() > 0) coords.append(',');
            coords.append(String.format(Locale.ROOT, "%.5f,%.5f", p.lat(), p.lon()));
        }
        return "[out:json][timeout:25];"
                + "nwr[\"tourism\"~\"^(hotel|motel|guest_house|hostel)$\"][\"name\"]"
                + "(around:" + radiusMeters + "," + coords + ");"
                + "out center tags;";
    }

    private List<Lodging> execute(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // Replace with your project name and contact so server admins can reach you
        headers.set("User-Agent", "sidekick-roadtrip-hackathon/0.1 (contact: your-email@example.com)");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);

        String body = rest.postForObject(url, new HttpEntity<>(form, headers), String.class);
        try {
            return parse(mapper.readTree(body));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read Overpass response", e);
        }
    }

    private List<Lodging> parse(JsonNode root) {
        List<Lodging> out = new ArrayList<>();
        for (JsonNode e : root.path("elements")) {
            JsonNode c = e.has("center") ? e.get("center") : e; // ways/relations come back with a center
            if (!c.has("lat") || !c.has("lon")) continue;
            Map<String, String> tags = new HashMap<>();
            for (Map.Entry<String, JsonNode> tag : e.path("tags").properties()) {
                tags.put(tag.getKey(), tag.getValue().textValue());
            }
            out.add(new Lodging(
                    e.path("type").textValue() + "/" + e.path("id").asLong(),
                    tags.get("name"),
                    tags.getOrDefault("tourism", "hotel"),
                    new LatLon(c.get("lat").asDouble(), c.get("lon").asDouble()),
                    tags));
        }
        return out;
    }

}
