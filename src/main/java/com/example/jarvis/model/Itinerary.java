package com.example.jarvis.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "jarvis_itineraries")
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    private Instant createdAt = Instant.now();

    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String tips;

    // full json exactly as generated, also what the chatbot reads
    @Column(columnDefinition = "text")
    private String rawJson;

    @Column(precision = 12, scale = 2)
    private BigDecimal estimatedActivitySpend;

    private String generatedBy;

    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayIndex ASC")
    private List<ItineraryDay> days = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTips() { return tips; }
    public void setTips(String tips) { this.tips = tips; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
    public BigDecimal getEstimatedActivitySpend() { return estimatedActivitySpend; }
    public void setEstimatedActivitySpend(BigDecimal estimatedActivitySpend) { this.estimatedActivitySpend = estimatedActivitySpend; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public List<ItineraryDay> getDays() { return days; }
    public void setDays(List<ItineraryDay> days) { this.days = days; }
}
