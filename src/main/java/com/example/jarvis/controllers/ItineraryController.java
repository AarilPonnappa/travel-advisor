package com.example.jarvis.controllers;

import com.example.jarvis.model.Itinerary;
import com.example.jarvis.service.GeminiService;
import com.example.jarvis.service.ItineraryService;
import com.example.jarvis.service.TripService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Arrays;
import java.util.List;

@Controller
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final TripService tripService;
    private final GeminiService gemini;

    public ItineraryController(ItineraryService itineraryService, TripService tripService, GeminiService gemini) {
        this.itineraryService = itineraryService;
        this.tripService = tripService;
        this.gemini = gemini;
    }

    @GetMapping("/itineraries/{id}")
    public String show(@PathVariable Long id, Model model) {
        Itinerary it = itineraryService.get(id);
        model.addAttribute("itinerary", it);
        model.addAttribute("trip", it.getTrip());
        model.addAttribute("money", tripService.money(it.getTrip()));
        List<String> tips = it.getTips() == null || it.getTips().isBlank() ? List.of()
                : Arrays.stream(it.getTips().split("\n")).filter(s -> !s.isBlank()).toList();
        model.addAttribute("tips", tips);
        model.addAttribute("chatReady", gemini.chatConfigured());
        model.addAttribute("step", "itinerary");
        return "itinerary";
    }

    @GetMapping("/itineraries")
    public String list(Model model) {
        model.addAttribute("itineraries", itineraryService.recent());
        return "itineraries";
    }
}
