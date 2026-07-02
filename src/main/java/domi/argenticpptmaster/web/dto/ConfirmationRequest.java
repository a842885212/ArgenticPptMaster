package domi.argenticpptmaster.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record ConfirmationRequest(
        @NotBlank String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment) {
}
