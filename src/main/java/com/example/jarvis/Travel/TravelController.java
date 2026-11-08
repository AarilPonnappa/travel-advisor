package com.example.jarvis.Travel;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Combined endpoint: one request in, road-trip plan and flight options out. */
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private final TravelService travel;

    public TravelController(TravelService travel) {
        this.travel = travel;
    }

    @PostMapping
    public TravelOptions plan(@RequestBody TravelRequest request) {
        if (isBlank(request.origin()) || isBlank(request.destination())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origin and destination are required");
        }
        return travel.plan(request);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
