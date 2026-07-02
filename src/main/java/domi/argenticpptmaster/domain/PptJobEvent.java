package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;

public record PptJobEvent(
        Instant occurredAt,
        PptJobEventType type,
        String message,
        Map<String, Object> data) {

    public static PptJobEvent of(PptJobEventType type, String message) {
        return new PptJobEvent(Instant.now(), type, message, Map.of());
    }

    public static PptJobEvent of(PptJobEventType type, String message, Map<String, Object> data) {
        return new PptJobEvent(Instant.now(), type, message, data == null ? Map.of() : Map.copyOf(data));
    }
}
