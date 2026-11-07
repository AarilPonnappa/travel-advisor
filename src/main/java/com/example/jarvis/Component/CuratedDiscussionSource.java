package com.example.jarvis.Component;

import com.example.jarvis.Discussion.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CuratedDiscussionSource implements DiscussionSource {
    private final Map<String, List<DiscussionLink>> byRoute;

    public CuratedDiscussionSource(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("curated-discussions.json").getInputStream()) {
            Map<String, List<DiscussionLink>> raw = mapper
                    .readValue(in, new TypeReference<Map<String, List<DiscussionLink>>>() {});
            this.byRoute = raw.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), Map.Entry::getValue));
        }
    }

    @Override
    public List<DiscussionLink> find(RouteQuery query) {
        if (query.routeName() == null) {
            return List.of();
        }
        return byRoute.getOrDefault(query.routeName().trim().toLowerCase(), List.of());
    }
}
