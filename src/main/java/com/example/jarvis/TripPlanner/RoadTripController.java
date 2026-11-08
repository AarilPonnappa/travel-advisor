package com.example.jarvis.TripPlanner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/plan")
public class RoadTripController {

        private final TripPlanner planner;

        public RoadTripController(TripPlanner planner) {
            this.planner = planner;
        }

        @PostMapping
        public TripPlan plan(@RequestBody TripRequest request) {
            if (request.routes() == null || request.routes().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one route is required");
            }
            return planner.plan(request);
        }
}

