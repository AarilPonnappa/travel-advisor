package com.example.jarvis.repository;

import com.example.jarvis.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByItineraryIdOrderByCreatedAtAscIdAsc(Long itineraryId);
}
