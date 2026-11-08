package com.example.jarvis.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "jarvis_stay_bookings")
public class StayBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id")
    private Stop stop;

    private String propertyName;

    @Enumerated(EnumType.STRING)
    private StayType stayType;

    private LocalDate checkIn;
    private LocalDate checkOut;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalPrice;

    private Double latitude;
    private Double longitude;
    private Double rating;

    @Column(columnDefinition = "text")
    private String address;

    private String externalId;

    @Column(columnDefinition = "text")
    private String url;

    private String source;

    public boolean covers(LocalDate night) {
        return !night.isBefore(checkIn) && night.isBefore(checkOut);
    }

    public long getNightCount() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Stop getStop() { return stop; }
    public void setStop(Stop stop) { this.stop = stop; }
    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
    public StayType getStayType() { return stayType; }
    public void setStayType(StayType stayType) { this.stayType = stayType; }
    public LocalDate getCheckIn() { return checkIn; }
    public void setCheckIn(LocalDate checkIn) { this.checkIn = checkIn; }
    public LocalDate getCheckOut() { return checkOut; }
    public void setCheckOut(LocalDate checkOut) { this.checkOut = checkOut; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
