package com.example.jarvis.controllers;

import com.example.jarvis.dto.PlannerForm;
import com.example.jarvis.model.ActivityType;
import com.example.jarvis.model.DayPlan;
import com.example.jarvis.model.Itinerary;
import com.example.jarvis.model.Trip;
import com.example.jarvis.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/trips/{tripId}/planner")
public class PlannerController {

    private final TripService tripService;
    private final StayService stayService;
    private final DayPlannerService dayPlanner;
    private final ItineraryService itineraryService;
    private final GeminiService gemini;

    public PlannerController(TripService tripService, StayService stayService, DayPlannerService dayPlanner,
                             ItineraryService itineraryService, GeminiService gemini) {
        this.tripService = tripService;
        this.stayService = stayService;
        this.dayPlanner = dayPlanner;
        this.itineraryService = itineraryService;
        this.gemini = gemini;
    }

    @GetMapping
    public String page(@PathVariable Long tripId, Model model, RedirectAttributes ra) {
        Trip trip = tripService.get(tripId);
        if (!tripService.datesComplete(trip)) {
            ra.addFlashAttribute("error", "Pick your dates first.");
            return "redirect:/trips/" + tripId + "/dates";
        }
        List<String> open = stayService.unsortedStops(tripId);
        if (!open.isEmpty()) {
            ra.addFlashAttribute("error", "Sort out where you're staying first: " + String.join(", ", open));
            return "redirect:/trips/" + tripId + "/stays";
        }
        trip = dayPlanner.prepare(tripId);
        model.addAttribute("trip", trip);
        model.addAttribute("money", tripService.money(trip));
        model.addAttribute("form", formFor(trip));
        model.addAttribute("activityTypes", ActivityType.values());
        model.addAttribute("bestMonths", dayPlanner.bestMonths(trip));
        model.addAttribute("geminiReady", gemini.plannerConfigured());
        model.addAttribute("latest", itineraryService.latestFor(tripId).orElse(null));
        model.addAttribute("step", "planner");
        return "planner";
    }

    @PostMapping
    public String submit(@PathVariable Long tripId, @ModelAttribute("form") PlannerForm form,
                         @RequestParam(defaultValue = "generate") String action, RedirectAttributes ra) {
        dayPlanner.save(tripId, form);
        if ("save".equals(action)) {
            ra.addFlashAttribute("info", "Saved. Weather warnings updated for your choices.");
            return "redirect:/trips/" + tripId + "/planner";
        }
        try {
            Itinerary it = itineraryService.generate(tripId);
            return "redirect:/itineraries/" + it.getId();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/trips/" + tripId + "/planner";
        }
    }

    private static PlannerForm formFor(Trip trip) {
        PlannerForm f = new PlannerForm();
        f.setInterests(trip.getInterests());
        f.setCuisines(trip.getCuisines());
        for (DayPlan p : trip.getDayPlans()) {
            PlannerForm.DayChoice c = new PlannerForm.DayChoice();
            c.setDayPlanId(p.getId());
            c.setActivityType(p.getActivityType());
            f.getDays().add(c);
        }
        return f;
    }
}
