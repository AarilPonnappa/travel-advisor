package com.example.jarvis.Lodging;

import java.util.Map;

public record Lodging(String id, String name, String type, LatLon location, Map<String, String> tags) { }
