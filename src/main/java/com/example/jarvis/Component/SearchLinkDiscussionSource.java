package com.example.jarvis.Component;

import com.example.jarvis.Discussion.DiscussionLink;
import com.example.jarvis.Discussion.DiscussionSource;
import com.example.jarvis.Discussion.RouteQuery;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SearchLinkDiscussionSource implements DiscussionSource {
    private static final List<String> SUBREDDITS = List.of("roadtrip", "RoadTripPlanning", "travel");

    @Override
    public List<DiscussionLink> find(RouteQuery query) {
        String term = URLEncoder.encode(query.searchTerm(), StandardCharsets.UTF_8);
        List<DiscussionLink> links = new ArrayList<>();

        links.add(new DiscussionLink(
                "Reddit: discussions about " + query.searchTerm(),
                "https://www.reddit.com/search/?q=" + term + "&sort=relevance",
                "search"
        ));

        for (String sub : SUBREDDITS) {
            links.add( new DiscussionLink(
                    "r/" + sub + ": " + query.searchTerm(),
                    "https://www.reddit.com/r/" + sub + "/search/?q=" + term + "&restrict_sr=1&sort=top",
                    "search"
            ));
        }
        return links;
    }
}
