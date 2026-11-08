package com.example.jarvis.service;

import com.example.jarvis.dto.HotelOffer;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.StopRepository;
import com.example.jarvis.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class StayService {

    private final TripRepository trips;
    private final StopRepository stops;
    private final HotelService hotels;

    public StayService(TripRepository trips, StopRepository stops, HotelService hotels) {
        this.trips = trips;
        this.stops = stops;
        this.hotels = hotels;
    }

    public boolean liveData() {
        return hotels.isConfigured();
    }

    @Transactional(readOnly = true)
    public List<HotelOffer> search(Long tripId, Long stopId, LocalDate checkIn, LocalDate checkOut, StayType type) {
        Stop stop = stopOf(tripId, stopId);
        Trip trip = stop.getTrip();
        checkRange(stop, checkIn, checkOut);
        StayType t = type == null ? StayType.ANY : type;
        List<HotelOffer> offers;
        if (hotels.isConfigured()) {
            offers = new ArrayList<>(hotels.search(stop.getName(), checkIn, checkOut, trip.getAdults(), trip.getChildren(),
                    trip.hasPets(), t));
        } else {
            offers = new ArrayList<>(hotels.demoOffers(stop.getName(), stop.getLatitude(), stop.getLongitude(), checkIn,
                    checkOut, trip.getAdults(), trip.getChildren(), trip.hasPets(), t));
        }
        offers.sort(Comparator.comparing(HotelOffer::totalPrice));
        return offers;
    }

    @Transactional
    public StayBooking book(Long tripId, Long stopId, LocalDate checkIn, LocalDate checkOut, HotelOffer offer) {
        Stop stop = stopOf(tripId, stopId);
        checkRange(stop, checkIn, checkOut);
        if (offer.name() == null || offer.name().isBlank()) throw new IllegalArgumentException("Pick a place to stay.");
        if (offer.totalPrice() == null || offer.totalPrice().signum() < 0) throw new IllegalArgumentException("Invalid price.");
        // every night in the range has to still be open
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            if (stop.bookingFor(d) != null) {
                throw new IllegalArgumentException("The night of " + d + " is already booked at " + stop.bookingFor(d).getPropertyName() + ".");
            }
            if (stop.isNightSkipped(d)) {
                throw new IllegalArgumentException("The night of " + d + " is marked as skipped. Un-skip it first.");
            }
        }
        StayBooking b = new StayBooking();
        b.setStop(stop);
        b.setPropertyName(offer.name());
        b.setStayType(offer.type() == null ? StayType.HOTEL : offer.type());
        b.setCheckIn(checkIn);
        b.setCheckOut(checkOut);
        b.setTotalPrice(offer.totalPrice());
        b.setLatitude(offer.latitude());
        b.setLongitude(offer.longitude());
        b.setRating(offer.rating());
        b.setAddress(offer.address());
        b.setExternalId(offer.externalId());
        b.setUrl(offer.url());
        b.setSource(offer.source());
        stop.getBookings().add(b);
        stops.save(stop);
        return b;
    }

    @Transactional
    public void removeBooking(Long tripId, Long stopId, Long bookingId) {
        Stop stop = stopOf(tripId, stopId);
        boolean removed = stop.getBookings().removeIf(b -> b.getId().equals(bookingId));
        if (!removed) throw new NotFoundException("Booking not found");
        stops.save(stop);
    }

    @Transactional
    public void skipNight(Long tripId, Long stopId, LocalDate night, boolean skip) {
        Stop stop = stopOf(tripId, stopId);
        if (!stop.getNights().contains(night)) throw new IllegalArgumentException("That night isn't part of this stay.");
        if (skip) {
            if (stop.bookingFor(night) != null) {
                throw new IllegalArgumentException("That night is booked at " + stop.bookingFor(night).getPropertyName()
                        + ". Remove the booking first.");
            }
            stop.getSkippedNights().add(night);
        } else {
            stop.getSkippedNights().remove(night);
        }
        stops.save(stop);
    }

    @Transactional
    public void skipAllOpen(Long tripId, Long stopId) {
        Stop stop = stopOf(tripId, stopId);
        stop.getSkippedNights().addAll(stop.getOpenNights());
        stops.save(stop);
    }

    // stops that still have nights with nowhere to sleep
    @Transactional(readOnly = true)
    public List<String> unsortedStops(Long tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NotFoundException("Trip not found"));
        List<String> out = new ArrayList<>();
        for (Stop s : trip.getStops()) {
            if (!s.isOrigin() && !s.isStaySorted()) out.add(s.getName() + " (" + s.getOpenNights().size() + " night(s))");
        }
        return out;
    }

    public BigDecimal stopTotal(Stop stop) {
        BigDecimal t = BigDecimal.ZERO;
        for (StayBooking b : stop.getBookings()) if (b.getTotalPrice() != null) t = t.add(b.getTotalPrice());
        return t;
    }

    private Stop stopOf(Long tripId, Long stopId) {
        Stop stop = stops.findByIdAndTripId(stopId, tripId).orElseThrow(() -> new NotFoundException("Stop not found"));
        if (stop.isOrigin()) throw new IllegalArgumentException("You don't need a stay at your starting point.");
        return stop;
    }

    private static void checkRange(Stop stop, LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) throw new IllegalArgumentException("Pick check-in and check-out dates.");
        if (!checkOut.isAfter(checkIn)) throw new IllegalArgumentException("Check-out has to be after check-in.");
        List<LocalDate> nights = stop.getNights();
        if (nights.isEmpty()) throw new IllegalArgumentException("There are no nights to book at " + stop.getName() + ".");
        if (checkIn.isBefore(nights.get(0)) || checkOut.isAfter(stop.getLeaveDate())) {
            throw new IllegalArgumentException("Dates must be between " + nights.get(0) + " and " + stop.getLeaveDate() + ".");
        }
    }
}
