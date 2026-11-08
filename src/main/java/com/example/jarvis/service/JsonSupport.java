package com.example.jarvis.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

// our own json mapper for third-party responses, so we don't depend on whichever jackson spring boot wires up
public final class JsonSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    // empty or broken bodies become a MissingNode, so .path(...) chains just come back empty
    public static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return MissingNode.getInstance();
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("response was not valid json: " + e.getMessage(), e);
        }
    }
}
