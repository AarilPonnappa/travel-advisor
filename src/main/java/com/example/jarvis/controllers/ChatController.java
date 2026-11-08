package com.example.jarvis.controllers;

import com.example.jarvis.dto.ChatReply;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.model.ChatMessage;
import com.example.jarvis.service.ChatService;
import com.example.jarvis.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/itineraries/{itineraryId}/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public List<Map<String, String>> history(@PathVariable Long itineraryId) {
        return chatService.history(itineraryId).stream()
                .map((ChatMessage m) -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();
    }

    @PostMapping
    public ChatReply ask(@PathVariable Long itineraryId, @RequestBody ChatRequest request) {
        return chatService.ask(itineraryId, request == null ? null : request.message());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
