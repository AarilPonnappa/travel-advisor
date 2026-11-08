package com.example.jarvis.controllers;

import com.example.jarvis.dto.HotelOffer;
import com.example.jarvis.model.Leg;
import com.example.jarvis.model.StayType;
import com.example.jarvis.model.Stop;
import com.example.jarvis.model.Trip;
import com.example.jarvis.service.StayService;
import com.example.jarvis.service.TripService;
import com.example.jarvis.service.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Controller
@RequestMapping("/trips/{tripId}/stays")
public class StayController {

    private final TripService tripService;
    private final StayService stayService;
    private final ObjectMapper json = JsonSupport.MAPPER;

    public StayController(TripService tripService, StayService stayService) {
        this.tripService = tripService;
        this.stayService = stayService;
    }

    @GetMapping
    public String page(@PathVariable Long tripId,
                       @RequestParam(required = false) Long stopId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                       @RequestParam(required = false) StayType type,
                       Model model, RedirectAttributes ra) throws Exception {
        Trip trip = tripService.get(tripId);
        if (!tripService.datesComplete(trip)) {
            ra.addFlashAttribute("error", "Pick your dates first.");
            return "redirect:/trips/" + tripId + "/dates";
        }
        for (Leg leg : trip.getLegs()) {
            if (leg.getSelectedOption() == null) {
                ra.addFlashAttribute("error", "Choose your transport first.");
                return "redirect:/trips/" + tripId + "/transport";
            }
        }

        model.addAttribute("trip", trip);
        model.addAttribute("money", tripService.money(trip));
        model.addAttribute("stayTypes", StayType.values());
        model.addAttribute("liveData", stayService.liveData());
        model.addAttribute("step", "stays");

        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Stop s : trip.getStops()) totals.put(s.getId(), stayService.stopTotal(s));
        model.addAttribute("stopTotals", totals);

        Long openStop = stopId;
        if (openStop == null) {
            // open the first destination that still needs a bed
            for (Stop s : trip.getStops()) {
                if (!s.isOrigin() && !s.isStaySorted()) {
                    openStop = s.getId();
                    break;
                }
            }
        }
        model.addAttribute("openStopId", openStop);

        if (stopId != null && checkIn != null && checkOut != null) {
            try {
                List<HotelOffer> offers = stayService.search(tripId, stopId, checkIn, checkOut, type);
                model.addAttribute("offers", offers);
                model.addAttribute("searchStopId", stopId);
                model.addAttribute("searchCheckIn", checkIn);
                model.addAttribute("searchCheckOut", checkOut);
                model.addAttribute("searchType", type == null ? StayType.ANY : type);
                List<Map<String, Object>> markers = new ArrayList<>();
                for (int i = 0; i < offers.size(); i++) {
                    HotelOffer o = offers.get(i);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("i", i);
                    m.put("name", o.name());
                    m.put("lat", o.latitude());
                    m.put("lng", o.longitude());
                    m.put("price", o.totalPrice());
                    m.put("type", o.type().getLabel());
                    markers.add(m);
                }
                model.addAttribute("markersJson", json.writeValueAsString(markers));
                Stop s = trip.getStops().stream().filter(x -> x.getId().equals(stopId)).findFirst().orElseThrow();
                model.addAttribute("centerJson", json.writeValueAsString(List.of(s.getLatitude(), s.getLongitude())));
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "stays";
    }

    @PostMapping("/{stopId}/book")
    public String book(@PathVariable Long tripId, @PathVariable Long stopId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                       @RequestParam(required = false) String externalId,
                       @RequestParam String name,
                       @RequestParam(required = false) StayType type,
                       @RequestParam(required = false) Double latitude,
                       @RequestParam(required = false) Double longitude,
                       @RequestParam BigDecimal totalPrice,
                       @RequestParam(required = false) Double rating,
                       @RequestParam(required = false) String address,
                       @RequestParam(required = false) String url,
                       @RequestParam(required = false) String source,
                       RedirectAttributes ra) {
        try {
            HotelOffer offer = new HotelOffer(externalId, name, type, latitude == null ? 0 : latitude,
                    longitude == null ? 0 : longitude, totalPrice, rating, null, address, null, url, false, source);
            stayService.book(tripId, stopId, checkIn, checkOut, offer);
            ra.addFlashAttribute("info", "Added " + name + " (" + checkIn + " to " + checkOut + ").");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/trips/" + tripId + "/stays?stopId=" + stopId;
    }

    @PostMapping("/{stopId}/skip")
    public String skip(@PathVariable Long tripId, @PathVariable Long stopId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate night,
                       @RequestParam(defaultValue = "true") boolean skip, RedirectAttributes ra) {
        try {
            stayService.skipNight(tripId, stopId, night, skip);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/trips/" + tripId + "/stays?stopId=" + stopId;
    }

    @PostMapping("/{stopId}/skip-rest")
    public String skipRest(@PathVariable Long tripId, @PathVariable Long stopId, RedirectAttributes ra) {
        try {
            stayService.skipAllOpen(tripId, stopId);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/trips/" + tripId + "/stays?stopId=" + stopId;
    }

    @PostMapping("/{stopId}/bookings/{bookingId}/delete")
    public String remove(@PathVariable Long tripId, @PathVariable Long stopId, @PathVariable Long bookingId,
                         RedirectAttributes ra) {
        stayService.removeBooking(tripId, stopId, bookingId);
        ra.addFlashAttribute("info", "Removed that stay.");
        return "redirect:/trips/" + tripId + "/stays?stopId=" + stopId;
    }

    @PostMapping("/done")
    public String done(@PathVariable Long tripId, RedirectAttributes ra) {
        List<String> open = stayService.unsortedStops(tripId);
        if (!open.isEmpty()) {
            ra.addFlashAttribute("error", "Some nights still have nowhere to stay: " + String.join(", ", open)
                    + ". Book them or mark them as skipped.");
            return "redirect:/trips/" + tripId + "/stays";
        }
        return "redirect:/trips/" + tripId + "/planner";
    }
}
