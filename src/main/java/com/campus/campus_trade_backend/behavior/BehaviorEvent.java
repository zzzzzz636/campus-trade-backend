package com.campus.campus_trade_backend.behavior;

import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

public record BehaviorEvent(int schemaVersion, String eventId, String type,
                            long productId, Instant occurredAt) {
    public static final int CURRENT_SCHEMA = 1;
    public static final String VIEW = "PRODUCT_VIEW";
    public static final String FAVORITE = "PRODUCT_FAVORITE";

    public static BehaviorEvent parse(ObjectMapper json, String payload) {
        BehaviorEvent event = json.readValue(payload, BehaviorEvent.class);
        if (event.schemaVersion() != CURRENT_SCHEMA || event.eventId() == null
                || event.eventId().length() > 80 || event.productId() < 1
                || event.occurredAt() == null || event.occurredAt().isAfter(Instant.now().plusSeconds(300))
                || (!VIEW.equals(event.type()) && !FAVORITE.equals(event.type()))) {
            throw new IllegalArgumentException("Invalid behavior event");
        }
        return event;
    }
}
