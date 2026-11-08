package com.example.jarvis.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
// prefixed so it can share a database with other apps
@Table(name = "jarvis_trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant createdAt = Instant.now();

    private int adults = 1;
    private int children = 0;
    private int pets = 0;

    @Enumerated(EnumType.STRING)
    private CabinClass cabinClass = CabinClass.ECONOMY;

    // optional, only used to draw the spend bar against a target
    @Column(precision = 12, scale = 2)
    private BigDecimal budget;

    @Column(columnDefinition = "text")
    private String interests;

    @Column(columnDefinition = "text")
    private String cuisines;

    private String currency = "USD";

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<Stop> stops = new ArrayList<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<Leg> legs = new ArrayList<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("date ASC")
    private List<DayPlan> dayPlans = new ArrayList<>();

    public int getTravellerCount() {
        return adults + children;
    }

    public boolean hasPets() {
        return pets > 0;
    }

    public String getCreatedLabel() {
        if (createdAt == null) return "";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(createdAt);
    }

    public String getRouteLabel() {
        StringBuilder sb = new StringBuilder();
        for (Stop s : stops) {
            if (!sb.isEmpty()) sb.append(" → ");
            sb.append(s.getName());
        }
        return sb.toString();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getAdults() { return adults; }
    public void setAdults(int adults) { this.adults = adults; }
    public int getChildren() { return children; }
    public void setChildren(int children) { this.children = children; }
    public int getPets() { return pets; }
    public void setPets(int pets) { this.pets = pets; }
    public CabinClass getCabinClass() { return cabinClass; }
    public void setCabinClass(CabinClass cabinClass) { this.cabinClass = cabinClass; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public String getInterests() { return interests; }
    public void setInterests(String interests) { this.interests = interests; }
    public String getCuisines() { return cuisines; }
    public void setCuisines(String cuisines) { this.cuisines = cuisines; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public List<Stop> getStops() { return stops; }
    public void setStops(List<Stop> stops) { this.stops = stops; }
    public List<Leg> getLegs() { return legs; }
    public void setLegs(List<Leg> legs) { this.legs = legs; }
    public List<DayPlan> getDayPlans() { return dayPlans; }
    public void setDayPlans(List<DayPlan> dayPlans) { this.dayPlans = dayPlans; }
}
