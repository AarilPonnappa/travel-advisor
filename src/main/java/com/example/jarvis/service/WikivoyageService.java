package com.example.jarvis.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// free travel guide text, pulled first so gemini has less to figure out on its own
@Service
public class WikivoyageService {

    private static final Logger log = LoggerFactory.getLogger(WikivoyageService.class);
    private static final List<String> WANTED = List.of("Understand", "Get around", "See", "Do", "Eat", "Drink", "Stay safe");
    private static final Pattern HEADING = Pattern.compile("(?m)^==\\s*([^=].*?)\\s*==\\s*$");

    private final RestClient http;
    private final String apiUrl;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public WikivoyageService(RestClient.Builder builder, @Value("${jarvis.url.wikivoyage}") String apiUrl) {
        this.http = builder.build();
        this.apiUrl = apiUrl;
    }

    public Map<String, String> guide(String place) {
        String title = place.split(",")[0].trim();
        if (title.isEmpty()) return Map.of();
        Map<String, String> cached = cache.get(title.toLowerCase(Locale.ROOT));
        if (cached != null) return cached;
        try {
            URI uri = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("action", "query")
                    .queryParam("prop", "extracts")
                    .queryParam("explaintext", 1)
                    .queryParam("exsectionformat", "wiki")
                    .queryParam("redirects", 1)
                    .queryParam("format", "json")
                    .queryParam("formatversion", 2)
                    .queryParam("titles", title)
                    .build().encode().toUri();
            JsonNode root = JsonSupport.parse(http.get().uri(uri)
                    .header("User-Agent", "jarvis-travel-advisor/1.0")
                    .retrieve().body(String.class));
            String extract = root == null ? "" : root.path("query").path("pages").path(0).path("extract").asText("");
            Map<String, String> sections = splitSections(extract, 1200);
            if (!sections.isEmpty()) cache.put(title.toLowerCase(Locale.ROOT), sections);
            return sections;
        } catch (Exception e) {
            log.warn("wikivoyage lookup failed for {}: {}", title, e.getMessage());
            return Map.of();
        }
    }

    static Map<String, String> splitSections(String extract, int maxChars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (extract == null || extract.isBlank()) return out;
        Matcher m = HEADING.matcher(extract);
        List<int[]> marks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            names.add(m.group(1).trim());
        }
        // the bit before the first heading is the intro
        String intro = marks.isEmpty() ? extract : extract.substring(0, marks.get(0)[0]);
        if (!intro.isBlank()) out.put("Intro", clip(intro, maxChars));
        for (int i = 0; i < marks.size(); i++) {
            String name = names.get(i);
            if (!WANTED.contains(name)) continue;
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : extract.length();
            String body = extract.substring(marks.get(i)[1], end);
            // drop sub-headings, keep the text
            body = body.replaceAll("(?m)^=+.*?=+\\s*$", "");
            if (!body.isBlank()) out.put(name, clip(body, maxChars));
        }
        return out;
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\n{2,}", "\n").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    public String asPromptText(Map<String, String> sections) {
        StringBuilder sb = new StringBuilder();
        sections.forEach((k, v) -> sb.append("[").append(k).append("] ").append(v).append("\n"));
        return sb.toString();
    }
}
