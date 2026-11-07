package com.example.jarvis.Services;

import com.example.jarvis.Discussion.DiscussionLink;
import com.example.jarvis.Discussion.DiscussionSource;
import com.example.jarvis.Discussion.RouteQuery;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiscussionService {
    private final List<DiscussionSource> sources;

    public DiscussionService(List<DiscussionSource> sources) {
        this.sources = sources;
    }

    public List<DiscussionLink> findDiscussions(RouteQuery query) {
        Map<String, DiscussionLink> unique = new LinkedHashMap<>();
        sources.stream()
                .flatMap(s -> s.find(query).stream())
                .forEach(link -> unique.putIfAbsent(link.url(), link));

        return unique.values().stream()
                .sorted(Comparator.comparing((DiscussionLink l) -> !"curated".equals(l.kind())))
                .toList();
    }
}
