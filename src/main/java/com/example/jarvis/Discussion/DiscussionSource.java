package com.example.jarvis.Discussion;

import java.util.List;

public interface DiscussionSource {
    List<DiscussionLink> find(RouteQuery query);
}
