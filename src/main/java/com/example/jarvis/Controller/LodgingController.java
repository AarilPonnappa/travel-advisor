package com.example.jarvis.Controller;


import java.util.List;

import com.example.jarvis.Lodging.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lodging")
public class LodgingController {

    public record RouteRequest(List<LatLon> route, Integer radiusMeters, Boolean petsRequired,
                               Integer limit, Double kmPerStretch, Integer perStretch) {}

    private final LodgingService service;

    public LodgingController(LodgingService service) {
        this.service = service;
    }

    @PostMapping("/best")
    public List<RankedLodging> best(@RequestBody RouteRequest r) {
        return service.best(r.route(),
                r.radiusMeters() != null ? r.radiusMeters() : 3000,
                LodgingPreferences.defaults(Boolean.TRUE.equals(r.petsRequired())),
                r.limit() != null ? r.limit() : 10);
    }

    @PostMapping("/by-stretch")
    public List<StretchPick> byStretch(@RequestBody RouteRequest r) {
        return service.byStretch(r.route(),
                r.radiusMeters() != null ? r.radiusMeters() : 3000,
                LodgingPreferences.defaults(Boolean.TRUE.equals(r.petsRequired())),
                r.kmPerStretch() != null ? r.kmPerStretch() : 300,
                r.perStretch() != null ? r.perStretch() : 3);
    }
}
