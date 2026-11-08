package com.example.jarvis.model;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "jarvis_day_plans")
public class DayPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @ManyToOne(optional = false)
    @JoinColumn(name = "stop_id")
    private Stop stop;

    private LocalDate date;

    @Enumerated(EnumType.STRING)
    private ActivityType activityType = ActivityType.RELAXING;

    // arrival days get a lighter plan
    private boolean travelDay;

    // weather snapshot used for warnings and handed to gemini
    private String weatherSummary;
    private Integer weatherCode;
    private Double tempMaxC;
    private Double tempMinC;
    private Double precipitationMm;
    private Double windKmh;
    private String weatherSource;
    private boolean severeWeather;

    // short reason like "heavy snow", independent of the chosen activity
    private String weatherRisk;

    @Column(columnDefinition = "text")
    private String weatherWarning;

    @Enumerated(EnumType.STRING)
    private ActivityType suggestedType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public Stop getStop() { return stop; }
    public void setStop(Stop stop) { this.stop = stop; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public ActivityType getActivityType() { return activityType; }
    public void setActivityType(ActivityType activityType) { this.activityType = activityType; }
    public boolean isTravelDay() { return travelDay; }
    public void setTravelDay(boolean travelDay) { this.travelDay = travelDay; }
    public String getWeatherSummary() { return weatherSummary; }
    public void setWeatherSummary(String weatherSummary) { this.weatherSummary = weatherSummary; }
    public Integer getWeatherCode() { return weatherCode; }
    public void setWeatherCode(Integer weatherCode) { this.weatherCode = weatherCode; }
    public Double getTempMaxC() { return tempMaxC; }
    public void setTempMaxC(Double tempMaxC) { this.tempMaxC = tempMaxC; }
    public Double getTempMinC() { return tempMinC; }
    public void setTempMinC(Double tempMinC) { this.tempMinC = tempMinC; }
    public Double getPrecipitationMm() { return precipitationMm; }
    public void setPrecipitationMm(Double precipitationMm) { this.precipitationMm = precipitationMm; }
    public Double getWindKmh() { return windKmh; }
    public void setWindKmh(Double windKmh) { this.windKmh = windKmh; }
    public String getWeatherSource() { return weatherSource; }
    public void setWeatherSource(String weatherSource) { this.weatherSource = weatherSource; }
    public boolean isSevereWeather() { return severeWeather; }
    public void setSevereWeather(boolean severeWeather) { this.severeWeather = severeWeather; }
    public String getWeatherRisk() { return weatherRisk; }
    public void setWeatherRisk(String weatherRisk) { this.weatherRisk = weatherRisk; }
    public String getWeatherWarning() { return weatherWarning; }
    public void setWeatherWarning(String weatherWarning) { this.weatherWarning = weatherWarning; }
    public ActivityType getSuggestedType() { return suggestedType; }
    public void setSuggestedType(ActivityType suggestedType) { this.suggestedType = suggestedType; }
}
