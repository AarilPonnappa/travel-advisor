package com.example.jarvis.repository;

import com.example.jarvis.model.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    Optional<Itinerary> findFirstByTripIdOrderByCreatedAtDesc(Long tripId);

    List<Itinerary> findTop20ByOrderByCreatedAtDesc();
}
