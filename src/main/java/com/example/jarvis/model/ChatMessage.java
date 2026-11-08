package com.example.jarvis.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "jarvis_chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itinerary_id")
    private Itinerary itinerary;

    // "user" or "model", same words gemini uses
    private String role;

    @Column(columnDefinition = "text")
    private String content;

    private Instant createdAt = Instant.now();

    public ChatMessage() {
    }

    public ChatMessage(Itinerary itinerary, String role, String content) {
        this.itinerary = itinerary;
        this.role = role;
        this.content = content;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Itinerary getItinerary() { return itinerary; }
    public void setItinerary(Itinerary itinerary) { this.itinerary = itinerary; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
