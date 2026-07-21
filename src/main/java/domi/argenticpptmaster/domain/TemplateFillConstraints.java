package domi.argenticpptmaster.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Structured hard constraints for TEMPLATE_FILL page selection.
 * Soft preferences remain in free-text {@code instruction}.
 */
public record TemplateFillConstraints(
        List<Integer> allowedTemplateSlides,
        List<Integer> excludedTemplateSlides,
        boolean preserveCover,
        boolean preserveEnding,
        Integer maxSlides) {

    public TemplateFillConstraints {
        allowedTemplateSlides = normalizePositiveUnique(allowedTemplateSlides);
        excludedTemplateSlides = normalizePositiveUnique(excludedTemplateSlides);
        if (maxSlides != null && maxSlides <= 0) {
            throw new IllegalArgumentException("maxSlides must be a positive integer");
        }
        Set<Integer> intersection = new LinkedHashSet<>(allowedTemplateSlides);
        intersection.retainAll(Set.copyOf(excludedTemplateSlides));
        if (!intersection.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedTemplateSlides and excludedTemplateSlides must not intersect: " + intersection);
        }
    }

    public static TemplateFillConstraints empty() {
        return new TemplateFillConstraints(List.of(), List.of(), false, false, null);
    }

    public boolean isEmpty() {
        return allowedTemplateSlides.isEmpty()
                && excludedTemplateSlides.isEmpty()
                && !preserveCover
                && !preserveEnding
                && maxSlides == null;
    }

    public int requiredBoundaryCount() {
        int count = 0;
        if (preserveCover) {
            count++;
        }
        if (preserveEnding) {
            count++;
        }
        return count;
    }

    private static List<Integer> normalizePositiveUnique(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (Integer value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException("template slide numbers must be positive integers");
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException("template slide numbers must be unique");
            }
        }
        return List.copyOf(unique);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TemplateFillConstraints that)) {
            return false;
        }
        return preserveCover == that.preserveCover
                && preserveEnding == that.preserveEnding
                && Objects.equals(maxSlides, that.maxSlides)
                && allowedTemplateSlides.equals(that.allowedTemplateSlides)
                && excludedTemplateSlides.equals(that.excludedTemplateSlides);
    }
}
