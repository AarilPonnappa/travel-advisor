package com.example.jarvis.repository;

import com.example.jarvis.model.DayPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DayPlanRepository extends JpaRepository<DayPlan, Long> {
}
