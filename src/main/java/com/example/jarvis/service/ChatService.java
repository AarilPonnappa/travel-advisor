package com.example.jarvis.service;

import com.example.jarvis.dto.ChatReply;
import com.example.jarvis.dto.MoneySummary;
import com.example.jarvis.model.*;
import com.example.jarvis.repository.ChatMessageRepository;
import com.example.jarvis.repository.ItineraryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// read-only assistant: answers questions about a saved planner, never edits it
@Service
public class ChatService {

    private static final int HISTORY_LIMIT = 20;
    private static final int MAX_QUESTION = 2000;

    private final ItineraryRepository itineraries;
    private final ChatMessageRepository messages;
    private final GeminiService gemini;
    private final TripService tripService;

    public ChatService(ItineraryRepository itineraries, ChatMessageRepository messages, GeminiService gemini,
                       TripService tripService) {
        this.itineraries = itineraries;
        this.messages = messages;
        this.gemini = gemini;
        this.tripService = tripService;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(Long itineraryId) {
        if (!itineraries.existsById(itineraryId)) throw new NotFoundException("Itinerary not found");
        return messages.findByItineraryIdOrderByCreatedAtAscIdAsc(itineraryId);
    }

    @Transactional
    public ChatReply ask(Long itineraryId, String question) {
        Itinerary it = itineraries.findById(itineraryId).orElseThrow(() -> new NotFoundException("Itinerary not found"));
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Ask something first.");
        String q = question.trim();
        if (q.length() > MAX_QUESTION) q = q.substring(0, MAX_QUESTION);

        if (!gemini.chatConfigured()) {
            return new ChatReply("The trip assistant isn't set up yet - add GEMINI_CHAT_API_KEY to the environment and restart.", false);
        }

        List<ChatMessage> past = messages.findByItineraryIdOrderByCreatedAtAscIdAsc(itineraryId);
        List<String[]> history = new ArrayList<>();
        int from = Math.max(0, past.size() - HISTORY_LIMIT);
        // the window must open on a user turn
        while (from < past.size() && !"user".equals(past.get(from).getRole())) from++;
        for (ChatMessage m : past.subList(from, past.size())) history.add(new String[]{m.getRole(), m.getContent()});
        history.add(new String[]{"user", q});

        String answer;
        try {
            answer = gemini.chat(systemPrompt(it), history);
        } catch (GeminiService.GeminiException e) {
            return new ChatReply("Sorry, I couldn't reach the assistant right now (" + e.getMessage() + "). Try again in a moment.", false);
        }
        messages.save(new ChatMessage(it, "user", q));
        messages.save(new ChatMessage(it, "model", answer));
        return new ChatReply(answer, true);
    }

    String systemPrompt(Itinerary it) {
        Trip trip = it.getTrip();
        MoneySummary money = tripService.money(trip);
        StringBuilder booked = new StringBuilder();
        for (Leg leg : trip.getLegs()) {
            TransportOption o = leg.getSelectedOption();
            booked.append("- ").append(leg.getTravelDate()).append(": ").append(leg.getFromStop().getName()).append(" -> ")
                    .append(leg.getToStop().getName()).append(" by ")
                    .append(o == null ? "(not chosen)" : o.getMode().getLabel() + " (" + o.getProvider() + ", "
                            + o.getDurationLabel() + ", " + trip.getCurrency() + " " + o.getTotalPrice() + ")")
                    .append('\n');
        }
        for (Stop s : trip.getStops()) {
            for (StayBooking b : s.getBookings()) {
                booked.append("- Stay in ").append(s.getName()).append(": ").append(b.getPropertyName()).append(" ")
                        .append(b.getCheckIn()).append(" to ").append(b.getCheckOut()).append(" (")
                        .append(trip.getCurrency()).append(" ").append(b.getTotalPrice()).append(")\n");
            }
            for (var n : s.getSkippedNights()) booked.append("- ").append(n).append(" in ").append(s.getName())
                    .append(": own arrangement, no hotel\n");
        }
        return """
                You are Jarvis Chat, a friendly assistant for one traveller's saved trip planner.
                You can answer questions about the plan, explain choices, and suggest alternatives or extra ideas.
                You cannot change the saved planner. If asked to change it, give your suggestion and say they can regenerate the planner to apply changes.
                Keep answers short and practical. Use the trip data below; say so when you're guessing.

                TRIP: %s
                GROUP: %d adults, %d children, %d pets
                MONEY SO FAR (%s): transport %s, stays %s, activities/food estimate %s, total %s%s
                BOOKED:
                %s
                PLANNER JSON:
                %s
                """.formatted(it.getSummary(), trip.getAdults(), trip.getChildren(), trip.getPets(), trip.getCurrency(),
                money.transport(), money.stays(), money.activities(), money.total(),
                trip.getBudget() != null ? ", budget " + trip.getBudget() : "",
                booked, it.getRawJson());
    }
}
