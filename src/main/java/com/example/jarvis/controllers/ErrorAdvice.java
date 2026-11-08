package com.example.jarvis.controllers;

import com.example.jarvis.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

// friendly page instead of a stack trace for missing trips/itineraries
@ControllerAdvice(assignableTypes = {HomeController.class, TripController.class, TransportController.class,
        StayController.class, PlannerController.class, ItineraryController.class})
public class ErrorAdvice {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException e, Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("message", e.getMessage());
        return "error";
    }
}
