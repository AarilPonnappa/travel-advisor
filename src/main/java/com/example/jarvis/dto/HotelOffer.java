package com.example.jarvis.dto;

import com.example.jarvis.model.StayType;
import java.math.BigDecimal;

public record HotelOffer(String externalId, String name, StayType type, double latitude, double longitude, BigDecimal totalPrice, Double rating, Integer stars, String address, String photoUrl, String url, boolean petFriendly, String source) {
}
