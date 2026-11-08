package com.example.jarvis.dto;

import java.util.List;

public record PlaceInfo(String name, String address, Double latitude, Double longitude, Double rating, String priceLevel, List<String> types) {
}
