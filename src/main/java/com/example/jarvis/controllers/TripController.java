package com.example.jarvis.controllers;

import com.example.jarvis.dto.DatesForm;
import com.example.jarvis.model.Trip;
import com.example.jarvis.service.TripService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/trips/{tripId}")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @GetMapping
    public String overview(@PathVariable Long tripId) {
        return "redirect:/trips/" + tripId + "/dates";
    }

    @GetMapping("/dates")
    public String dates(@PathVariable Long tripId, Model model) {
        Trip trip = tripService.get(tripId);
        model.addAttribute("trip", trip);
        model.addAttribute("form", tripService.datesFormFor(trip));
        model.addAttribute("money", tripService.money(trip));
        model.addAttribute("step", "dates");
        return "dates";
    }

    @PostMapping("/dates")
    public String saveDates(@PathVariable Long tripId, @Valid @ModelAttribute("form") DatesForm form,
                            BindingResult binding, Model model) {
        List<String> errors = new ArrayList<>();
        if (binding.hasErrors()) {
            binding.getFieldErrors().forEach(fe -> errors.add(fieldLabel(fe.getField()) + ": invalid value"));
        } else {
            errors.addAll(tripService.saveDates(tripId, form));
        }
        if (!errors.isEmpty()) {
            Trip trip = tripService.get(tripId);
            // the names/flags aren't posted back, refill them for the template
            DatesForm fresh = tripService.datesFormFor(trip);
            for (int i = 0; i < fresh.getStops().size() && i < form.getStops().size(); i++) {
                form.getStops().get(i).setName(fresh.getStops().get(i).getName());
                form.getStops().get(i).setOrigin(fresh.getStops().get(i).isOrigin());
                form.getStops().get(i).setLast(fresh.getStops().get(i).isLast());
            }
            if (form.getStops().size() != fresh.getStops().size()) form.setStops(fresh.getStops());
            model.addAttribute("trip", trip);
            model.addAttribute("form", form);
            model.addAttribute("errors", errors);
            model.addAttribute("money", tripService.money(trip));
            model.addAttribute("step", "dates");
            return "dates";
        }
        return "redirect:/trips/" + tripId + "/transport";
    }

    private static String fieldLabel(String field) {
        if (field.contains("arriveDate")) return "Arrival date";
        if (field.contains("leaveDate")) return "Departure date";
        return switch (field) {
            case "adults" -> "Adults";
            case "children" -> "Children";
            case "pets" -> "Pets";
            case "budget" -> "Budget";
            default -> field;
        };
    }
}
