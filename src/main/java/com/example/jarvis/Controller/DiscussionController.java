package com.example.jarvis.Controller;

import com.example.jarvis.Discussion.DiscussionLink;
import com.example.jarvis.Discussion.RouteQuery;
import com.example.jarvis.Services.DiscussionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/discussions")
public class DiscussionController {
    private final DiscussionService service;

    public DiscussionController(DiscussionService service) {
        this.service = service;
    }

    @GetMapping
    public List<DiscussionLink> discussion(
            @RequestParam(required = false) String route,
            @RequestParam(required = false, defaultValue = "") String origin,
            @RequestParam(required = false, defaultValue = "") String destination
    ) {
        return service.findDiscussions(new RouteQuery(route, origin, destination));
    }
}
