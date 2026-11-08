package com.example.jarvis.dto;

import com.example.jarvis.model.ActivityType;

import java.util.ArrayList;
import java.util.List;

public class PlannerForm {

    private List<DayChoice> days = new ArrayList<>();
    private String interests;
    private String cuisines;

    public static class DayChoice {
        private Long dayPlanId;
        private ActivityType activityType;

        public Long getDayPlanId() { return dayPlanId; }
        public void setDayPlanId(Long dayPlanId) { this.dayPlanId = dayPlanId; }
        public ActivityType getActivityType() { return activityType; }
        public void setActivityType(ActivityType activityType) { this.activityType = activityType; }
    }

    public List<DayChoice> getDays() { return days; }
    public void setDays(List<DayChoice> days) { this.days = days; }
    public String getInterests() { return interests; }
    public void setInterests(String interests) { this.interests = interests; }
    public String getCuisines() { return cuisines; }
    public void setCuisines(String cuisines) { this.cuisines = cuisines; }
}
