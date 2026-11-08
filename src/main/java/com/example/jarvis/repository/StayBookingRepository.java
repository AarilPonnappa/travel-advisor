package com.example.jarvis.repository;

import com.example.jarvis.model.StayBooking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StayBookingRepository extends JpaRepository<StayBooking, Long> {
}
