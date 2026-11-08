package com.example.jarvis.TripPlaner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

public class TripController {

    @RestController
    @RequestMapping("/api/plan")
    public class TripController {

        private final TripPlanner planner;

        public TripController(TripPlanner planner) {
            this.planner = planner;
        }

        @PostMapping
        public TripPlan plan(@RequestBody TripRequest request) {
            if (request.answers() == null || request.routes() == null || request.routes().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answers and at least one route are required");
            }
            return planner.plan(request);
        }
    }
}
