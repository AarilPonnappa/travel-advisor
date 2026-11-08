package com.example.jarvis.service;

import com.example.jarvis.dto.RedditSnippet;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// pulls real traveller opinions from reddit to feed the planner
@Service
public class RedditService {

    private static final Logger log = LoggerFactory.getLogger(RedditService.class);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String userAgent;
    private final String oauthUrl;
    private final String authUrl;
    private final String publicUrl;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public RedditService(RestClient.Builder builder,
                         @Value("${jarvis.reddit.client-id:}") String clientId,
                         @Value("${jarvis.reddit.client-secret:}") String clientSecret,
                         @Value("${jarvis.reddit.user-agent}") String userAgent,
                         @Value("${jarvis.url.reddit-oauth}") String oauthUrl,
                         @Value("${jarvis.url.reddit-auth}") String authUrl,
                         @Value("${jarvis.url.reddit-public}") String publicUrl) {
        this.http = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.userAgent = userAgent;
        this.oauthUrl = oauthUrl;
        this.authUrl = authUrl;
        this.publicUrl = publicUrl;
    }

    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public List<RedditSnippet> search(String query, int limit) {
        List<RedditSnippet> out = new ArrayList<>();
        try {
            boolean oauth = hasCredentials();
            String base = oauth ? oauthUrl + "/search" : publicUrl + "/search.json";
            URI uri = UriComponentsBuilder.fromUriString(base)
                    .queryParam("q", query)
                    .queryParam("sort", "relevance")
                    .queryParam("t", "all")
                    .queryParam("type", "link")
                    .queryParam("limit", limit)
                    .build().encode().toUri();
            JsonNode root = get(uri, oauth);
            if (root == null) return out;
            int withComments = 0;
            for (JsonNode child : root.path("data").path("children")) {
                JsonNode d = child.path("data");
                String text = d.path("selftext").asText("");
                // top comments are usually where the actual recommendations are
                if (withComments < 2 && d.hasNonNull("id")) {
                    String comments = topComments(d.path("id").asText(), oauth);
                    if (!comments.isBlank()) {
                        text = (text + "\nTop replies: " + comments).trim();
                        withComments++;
                    }
                }
                out.add(new RedditSnippet(d.path("title").asText(), d.path("subreddit").asText(),
                        d.path("score").asInt(), clip(text, 900), "https://www.reddit.com" + d.path("permalink").asText("")));
            }
        } catch (Exception e) {
            log.warn("reddit search failed for '{}': {}", query, e.getMessage());
        }
        return out;
    }

    private String topComments(String postId, boolean oauth) {
        try {
            String base = oauth ? oauthUrl + "/comments/" + postId : publicUrl + "/comments/" + postId + ".json";
            URI uri = UriComponentsBuilder.fromUriString(base)
                    .queryParam("limit", 5)
                    .queryParam("depth", 1)
                    .queryParam("sort", "top")
                    .build().encode().toUri();
            JsonNode root = get(uri, oauth);
            if (root == null || !root.isArray() || root.size() < 2) return "";
            List<String> bodies = new ArrayList<>();
            for (JsonNode c : root.get(1).path("data").path("children")) {
                String body = c.path("data").path("body").asText("");
                if (!body.isBlank() && bodies.size() < 4) bodies.add(clip(body, 250));
            }
            return String.join(" | ", bodies);
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode get(URI uri, boolean oauth) {
        RestClient.RequestHeadersSpec<?> req = http.get().uri(uri).header("User-Agent", userAgent);
        if (oauth) req = req.header("Authorization", "Bearer " + accessToken());
        return JsonSupport.parse(req.retrieve().body(String.class));
    }

    // app-only oauth token, cached until it is about to expire
    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpires)) return token;
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode res = JsonSupport.parse(http.post().uri(authUrl)
                .header("Authorization", "Basic " + basic)
                .header("User-Agent", userAgent)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(String.class));
        if (res == null || !res.hasNonNull("access_token")) throw new IllegalStateException("reddit did not return a token");
        token = res.path("access_token").asText();
        tokenExpires = Instant.now().plusSeconds(Math.max(60, res.path("expires_in").asLong(3600) - 60));
        return token;
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
