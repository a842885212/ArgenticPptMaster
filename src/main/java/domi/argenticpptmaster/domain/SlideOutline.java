package domi.argenticpptmaster.domain;

import java.util.List;
import java.util.Map;

/** 单页 PPT 大纲。 */
public record SlideOutline(
        int slideNo,
        String title,
        String keyMessage,
        List<String> bullets,
        String visualSuggestion,
        Map<String, Object> imageRequirement) {

    public SlideOutline {
        bullets = bullets == null ? List.of() : bullets.stream().toList();
        imageRequirement = imageRequirement == null ? null : Map.copyOf(imageRequirement);
    }

    public void validate() {
        if (slideNo <= 0 || title == null || title.isBlank() || keyMessage == null || keyMessage.isBlank()
                || bullets.isEmpty() || bullets.stream().anyMatch(item -> item == null || item.isBlank())
                || visualSuggestion == null || visualSuggestion.isBlank()) {
            throw new IllegalArgumentException("slide outline has invalid required fields");
        }
    }
}
