package com.example.jarvis.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "jarvis_legs")
public class Leg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    private int seq;

    @ManyToOne(optional = false)
    @JoinColumn(name = "from_stop_id")
    private Stop fromStop;

    @ManyToOne(optional = false)
    @JoinColumn(name = "to_stop_id")
    private Stop toStop;

    private LocalDate travelDate;

    // straight line distance, handy for the ranking heuristics
    private Double distanceKm;

    @OneToMany(mappedBy = "leg", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ranking ASC")
    private List<TransportOption> options = new ArrayList<>();

    public TransportOption getSelectedOption() {
        for (TransportOption o : options) {
            if (o.isSelected()) return o;
        }
        return null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public Stop getFromStop() { return fromStop; }
    public void setFromStop(Stop fromStop) { this.fromStop = fromStop; }
    public Stop getToStop() { return toStop; }
    public void setToStop(Stop toStop) { this.toStop = toStop; }
    public LocalDate getTravelDate() { return travelDate; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public List<TransportOption> getOptions() { return options; }
    public void setOptions(List<TransportOption> options) { this.options = options; }
}
