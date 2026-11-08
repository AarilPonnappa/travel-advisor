package com.example.jarvis.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DatesForm {

    @Valid
    private List<StopDates> stops = new ArrayList<>();

    @Min(1) @Max(20)
    private int adults = 1;

    @Min(0) @Max(20)
    private int children = 0;

    @Min(0) @Max(10)
    private int pets = 0;

    private BigDecimal budget;

    public static class StopDates {
        private Long stopId;
        private String name;
        private boolean origin;
        private boolean last;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate arriveDate;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate leaveDate;

        public Long getStopId() { return stopId; }
        public void setStopId(Long stopId) { this.stopId = stopId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isOrigin() { return origin; }
        public void setOrigin(boolean origin) { this.origin = origin; }
        public boolean isLast() { return last; }
        public void setLast(boolean last) { this.last = last; }
        public LocalDate getArriveDate() { return arriveDate; }
        public void setArriveDate(LocalDate arriveDate) { this.arriveDate = arriveDate; }
        public LocalDate getLeaveDate() { return leaveDate; }
        public void setLeaveDate(LocalDate leaveDate) { this.leaveDate = leaveDate; }
    }

    public List<StopDates> getStops() { return stops; }
    public void setStops(List<StopDates> stops) { this.stops = stops; }
    public int getAdults() { return adults; }
    public void setAdults(int adults) { this.adults = adults; }
    public int getChildren() { return children; }
    public void setChildren(int children) { this.children = children; }
    public int getPets() { return pets; }
    public void setPets(int pets) { this.pets = pets; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
}
