package com.example.jarvis.repository;

import com.example.jarvis.model.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StopRepository extends JpaRepository<Stop, Long> {

    Optional<Stop> findByIdAndTripId(Long id, Long tripId);
}
