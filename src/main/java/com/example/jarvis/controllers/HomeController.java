package com.example.jarvis.controllers;

import com.example.jarvis.model.Trip;
import com.example.jarvis.service.ItineraryService;
import com.example.jarvis.service.TripService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
public class HomeController {

    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final String currency;

    public HomeController(TripService tripService, ItineraryService itineraryService,
                          @Value("${jarvis.currency:USD}") String currency) {
        this.tripService = tripService;
        this.itineraryService = itineraryService;
        this.currency = currency;
    }

    @GetMapping("/")
    public String index(Model model) {
        if (!model.containsAttribute("places")) model.addAttribute("places", List.of("", ""));
        model.addAttribute("trips", tripService.recent());
        model.addAttribute("itineraries", itineraryService.recent());
        return "index";
    }

    @PostMapping("/trips")
    public String create(@RequestParam(name = "place", required = false) List<String> places, RedirectAttributes ra) {
        try {
            Trip trip = tripService.create(places, currency);
            return "redirect:/trips/" + trip.getId() + "/dates";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            // keep what they typed so they don't have to start over
            List<String> keep = places == null ? new ArrayList<>() : new ArrayList<>(places);
            while (keep.size() < 2) keep.add("");
            ra.addFlashAttribute("places", keep);
            return "redirect:/";
        }
    }
}
