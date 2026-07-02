package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;

public record PptConfirmation(
        String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment,
        Instant confirmedAt) {
}
