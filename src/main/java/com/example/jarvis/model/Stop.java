package com.example.jarvis.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Entity
@Table(name = "jarvis_stops")
public class Stop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    // 0 is the starting point, the last one is the final destination
    private int position;

    private String name;
    private String formattedName;
    private Double latitude;
    private Double longitude;
    private String countryCode;

    private LocalDate arriveDate;
    private LocalDate leaveDate;

    @OneToMany(mappedBy = "stop", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("checkIn ASC")
    private List<StayBooking> bookings = new ArrayList<>();

    // nights the traveller sorts out themselves (friend's place etc)
    @ElementCollection
    @CollectionTable(name = "jarvis_stop_skipped_nights", joinColumns = @JoinColumn(name = "stop_id"))
    @Column(name = "night")
    private Set<LocalDate> skippedNights = new TreeSet<>();

    public boolean isOrigin() {
        return position == 0;
    }

    // every night spent here, check-in date of each night
    public List<LocalDate> getNights() {
        List<LocalDate> nights = new ArrayList<>();
        if (isOrigin() || arriveDate == null || leaveDate == null) return nights;
        for (LocalDate d = arriveDate; d.isBefore(leaveDate); d = d.plusDays(1)) {
            nights.add(d);
        }
        return nights;
    }

    public StayBooking bookingFor(LocalDate night) {
        for (StayBooking b : bookings) {
            if (b.covers(night)) return b;
        }
        return null;
    }

    public boolean isNightSkipped(LocalDate night) {
        return skippedNights.contains(night);
    }

    public List<LocalDate> getOpenNights() {
        List<LocalDate> open = new ArrayList<>();
        for (LocalDate n : getNights()) {
            if (bookingFor(n) == null && !isNightSkipped(n)) open.add(n);
        }
        return open;
    }

    // check-out date that covers the first run of open nights, for the search form default
    public LocalDate getFirstOpenBlockEnd() {
        List<LocalDate> open = getOpenNights();
        if (open.isEmpty()) return null;
        LocalDate end = open.get(0).plusDays(1);
        for (int i = 1; i < open.size() && open.get(i).equals(end); i++) end = end.plusDays(1);
        return end;
    }

    public LocalDate getFirstOpenNight() {
        List<LocalDate> open = getOpenNights();
        return open.isEmpty() ? null : open.get(0);
    }

    public boolean isStaySorted() {
        return getOpenNights().isEmpty();
    }

    public String getDisplayName() {
        return formattedName != null && !formattedName.isBlank() ? formattedName : name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFormattedName() { return formattedName; }
    public void setFormattedName(String formattedName) { this.formattedName = formattedName; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public LocalDate getArriveDate() { return arriveDate; }
    public void setArriveDate(LocalDate arriveDate) { this.arriveDate = arriveDate; }
    public LocalDate getLeaveDate() { return leaveDate; }
    public void setLeaveDate(LocalDate leaveDate) { this.leaveDate = leaveDate; }
    public List<StayBooking> getBookings() { return bookings; }
    public void setBookings(List<StayBooking> bookings) { this.bookings = bookings; }
    public Set<LocalDate> getSkippedNights() { return skippedNights; }
    public void setSkippedNights(Set<LocalDate> skippedNights) { this.skippedNights = skippedNights; }
}
