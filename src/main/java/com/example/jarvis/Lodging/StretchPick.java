package com.example.jarvis.Lodging;

import java.util.List;

public record StretchPick(int stretch, double fromKm, double toKm, List<RankedLodging> picks) {}


