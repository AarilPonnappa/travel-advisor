package com.example.jarvis.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "jarvis_transport_options")
public class TransportOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leg_id")
    private Leg leg;

    @Enumerated(EnumType.STRING)
    private TransportMode mode;

    private String provider;
    private String summary;
    private String departTime;
    private String arriveTime;

    // time actually on the vehicle
    private Integer durationMinutes;
    // includes airport/station overhead, used for "fastest"
    private Integer doorToDoorMinutes;

    private Double distanceKm;
    private Integer transfers;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalPrice;

    private String travelClass;

    // 1 = most practical
    private int ranking;
    private double practicalityScore;

    // comma separated: FASTEST, BUDGET, CHEAPEST
    private String tags;

    @Column(columnDefinition = "text")
    private String notes;

    // LIVE when it came from an api, ESTIMATE when we had to guess
    private String source;

    @Column(columnDefinition = "text")
    private String bookingUrl;

    private boolean selected;

    public boolean hasTag(String tag) {
        return tags != null && (("," + tags + ",").contains("," + tag + ","));
    }

    public void addTag(String tag) {
        if (hasTag(tag)) return;
        tags = (tags == null || tags.isBlank()) ? tag : tags + "," + tag;
    }

    public String getDurationLabel() {
        return formatMinutes(durationMinutes);
    }

    public String getDoorToDoorLabel() {
        return formatMinutes(doorToDoorMinutes);
    }

    public static String formatMinutes(Integer minutes) {
        if (minutes == null) return "-";
        int days = minutes / (60 * 24);
        int hours = (minutes % (60 * 24)) / 60;
        int mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        sb.append(mins).append("m");
        return sb.toString();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Leg getLeg() { return leg; }
    public void setLeg(Leg leg) { this.leg = leg; }
    public TransportMode getMode() { return mode; }
    public void setMode(TransportMode mode) { this.mode = mode; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDepartTime() { return departTime; }
    public void setDepartTime(String departTime) { this.departTime = departTime; }
    public String getArriveTime() { return arriveTime; }
    public void setArriveTime(String arriveTime) { this.arriveTime = arriveTime; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public Integer getDoorToDoorMinutes() { return doorToDoorMinutes; }
    public void setDoorToDoorMinutes(Integer doorToDoorMinutes) { this.doorToDoorMinutes = doorToDoorMinutes; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public Integer getTransfers() { return transfers; }
    public void setTransfers(Integer transfers) { this.transfers = transfers; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public String getTravelClass() { return travelClass; }
    public void setTravelClass(String travelClass) { this.travelClass = travelClass; }
    public int getRanking() { return ranking; }
    public void setRanking(int ranking) { this.ranking = ranking; }
    public double getPracticalityScore() { return practicalityScore; }
    public void setPracticalityScore(double practicalityScore) { this.practicalityScore = practicalityScore; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getBookingUrl() { return bookingUrl; }
    public void setBookingUrl(String bookingUrl) { this.bookingUrl = bookingUrl; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
}
