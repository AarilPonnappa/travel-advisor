package com.example.jarvis.Lodging;

import java.util.List;

public record RankedLodging(Lodging lodging, double score, double offRouteKm,
                            double alongRouteKm, List<String> reasons ){}
