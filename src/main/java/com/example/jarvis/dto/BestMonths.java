package com.example.jarvis.dto;

import com.example.jarvis.model.ActivityType;
import java.util.List;

public record BestMonths(ActivityType activityType, List<String> months, String note) {
}
