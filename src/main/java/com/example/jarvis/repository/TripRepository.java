package com.example.jarvis.repository;

import com.example.jarvis.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {

    List<Trip> findTop20ByOrderByCreatedAtDesc();
}
