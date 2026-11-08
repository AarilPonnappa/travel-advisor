package com.example.jarvis.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "jarvis_itinerary_items")
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "day_id")
    private ItineraryDay day;

    private int position;
    private String startTime;
    private String endTime;
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    // ACTIVITY, MEAL, TRAVEL, REST, CHECKIN, NIGHTLIFE
    private String category;
    private String place;

    // getting here from the previous stop
    private Integer travelMinutes;
    private String travelMode;

    @Column(precision = 12, scale = 2)
    private BigDecimal estimatedCost;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ItineraryDay getDay() { return day; }
    public void setDay(ItineraryDay day) { this.day = day; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place; }
    public Integer getTravelMinutes() { return travelMinutes; }
    public void setTravelMinutes(Integer travelMinutes) { this.travelMinutes = travelMinutes; }
    public String getTravelMode() { return travelMode; }
    public void setTravelMode(String travelMode) { this.travelMode = travelMode; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
}
