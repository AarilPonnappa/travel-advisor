package com.example.jarvis.controllers;

import com.example.jarvis.model.CabinClass;
import com.example.jarvis.model.Trip;
import com.example.jarvis.service.FlightService;
import com.example.jarvis.service.OpenRouteService;
import com.example.jarvis.service.TransportService;
import com.example.jarvis.service.TripService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/trips/{tripId}/transport")
public class TransportController {

    private final TripService tripService;
    private final TransportService transportService;
    private final FlightService flights;
    private final OpenRouteService ors;

    public TransportController(TripService tripService, TransportService transportService,
                               FlightService flights, OpenRouteService ors) {
        this.tripService = tripService;
        this.transportService = transportService;
        this.flights = flights;
        this.ors = ors;
    }

    @GetMapping
    public String page(@PathVariable Long tripId, Model model, RedirectAttributes ra) {
        Trip trip = tripService.get(tripId);
        if (!tripService.datesComplete(trip)) {
            ra.addFlashAttribute("error", "Pick your dates first.");
            return "redirect:/trips/" + tripId + "/dates";
        }
        transportService.ensureOptions(tripId, false);
        trip = tripService.get(tripId);
        model.addAttribute("trip", trip);
        model.addAttribute("money", tripService.money(trip));
        model.addAttribute("cabinClasses", CabinClass.values());
        model.addAttribute("liveFlights", flights.isConfigured());
        model.addAttribute("liveRoutes", ors.isConfigured());
        model.addAttribute("step", "transport");
        return "transport";
    }

    @PostMapping("/travellers")
    public String travellers(@PathVariable Long tripId, @RequestParam int adults, @RequestParam int children,
                             @RequestParam int pets, @RequestParam(required = false) CabinClass cabinClass,
                             RedirectAttributes ra) {
        try {
            transportService.updateTravellers(tripId, adults, children, pets, cabinClass);
            ra.addFlashAttribute("info", "Updated travellers and refreshed the options.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/trips/" + tripId + "/transport";
    }

    @PostMapping("/refresh")
    public String refresh(@PathVariable Long tripId, RedirectAttributes ra) {
        transportService.ensureOptions(tripId, true);
        ra.addFlashAttribute("info", "Fetched fresh options.");
        return "redirect:/trips/" + tripId + "/transport";
    }

    @PostMapping
    public String choose(@PathVariable Long tripId, @RequestParam Map<String, String> params, RedirectAttributes ra) {
        // radio buttons are named leg_<legId> with the option id as value
        Map<Long, Long> chosen = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!e.getKey().startsWith("leg_")) continue;
            try {
                chosen.put(Long.parseLong(e.getKey().substring(4)), Long.parseLong(e.getValue()));
            } catch (NumberFormatException ignored) {
                // a tampered field just counts as "not chosen"
            }
        }
        List<String> missing = transportService.select(tripId, chosen);
        if (!missing.isEmpty()) {
            ra.addFlashAttribute("error", "Pick a way to travel for: " + String.join(", ", missing));
            return "redirect:/trips/" + tripId + "/transport";
        }
        return "redirect:/trips/" + tripId + "/stays";
    }
}
