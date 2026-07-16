package domi.argenticpptmaster.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** 相邻大纲版本之间的结构化差异。 */
public record PptOutlineVersionDiff(
        int fromVersion,
        int toVersion,
        List<PptOutlineSlideDiff> changes) {

    public PptOutlineVersionDiff {
        if (fromVersion <= 0 || toVersion <= fromVersion) {
            throw new IllegalArgumentException("outline diff versions are invalid");
        }
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public static PptOutlineVersionDiff between(PptOutline before, PptOutline after) {
        Objects.requireNonNull(before, "before outline");
        Objects.requireNonNull(after, "after outline");
        List<PptOutlineSlideDiff> result = new ArrayList<>();
        int common = Math.min(before.slides().size(), after.slides().size());
        Set<Integer> matchedBefore = new HashSet<>();
        Set<Integer> matchedAfter = new HashSet<>();
        Set<Integer> modifiedBefore = new HashSet<>();
        Set<Integer> modifiedAfter = new HashSet<>();
        for (int newIndex = 0; newIndex < after.slides().size(); newIndex++) {
            for (int oldIndex = 0; oldIndex < before.slides().size(); oldIndex++) {
                if (!matchedBefore.contains(oldIndex) && sameContent(before.slides().get(oldIndex), after.slides().get(newIndex))) {
                    matchedBefore.add(oldIndex);
                    matchedAfter.add(newIndex);
                    break;
                }
            }
        }
        for (int index = 0; index < common; index++) {
            SlideOutline oldSlide = before.slides().get(index);
            SlideOutline newSlide = after.slides().get(index);
            if (matchedBefore.contains(index) || matchedAfter.contains(index)) {
                continue;
            }
            LinkedHashMap<String, Object> fields = changedFields(oldSlide, newSlide);
            if (!fields.isEmpty()) {
                modifiedBefore.add(index);
                modifiedAfter.add(index);
                result.add(new PptOutlineSlideDiff(PptOutlineDiffType.MODIFIED, index + 1,
                        oldSlide, newSlide, fields));
            }
        }
        for (int index = 0; index < before.slides().size(); index++) {
            if (!matchedBefore.contains(index) && !modifiedBefore.contains(index)) {
                result.add(new PptOutlineSlideDiff(PptOutlineDiffType.REMOVED, index + 1,
                        before.slides().get(index), null, Map.of()));
            }
        }
        for (int index = 0; index < after.slides().size(); index++) {
            if (!matchedAfter.contains(index) && !modifiedAfter.contains(index)) {
                result.add(new PptOutlineSlideDiff(PptOutlineDiffType.ADDED, index + 1,
                        null, after.slides().get(index), Map.of()));
            }
        }
        return new PptOutlineVersionDiff(before.version(), after.version(), result);
    }

    private static boolean sameContent(SlideOutline before, SlideOutline after) {
        return Objects.equals(before.title(), after.title())
                && Objects.equals(before.keyMessage(), after.keyMessage())
                && Objects.equals(before.bullets(), after.bullets())
                && Objects.equals(before.visualSuggestion(), after.visualSuggestion())
                && Objects.equals(before.imageRequirement(), after.imageRequirement());
    }

    private static LinkedHashMap<String, Object> changedFields(SlideOutline before, SlideOutline after) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (!Objects.equals(before.title(), after.title())) fields.put("title", List.of(before.title(), after.title()));
        if (!Objects.equals(before.keyMessage(), after.keyMessage())) {
            fields.put("keyMessage", List.of(before.keyMessage(), after.keyMessage()));
        }
        if (!Objects.equals(before.bullets(), after.bullets())) fields.put("bullets", List.of(before.bullets(), after.bullets()));
        if (!Objects.equals(before.visualSuggestion(), after.visualSuggestion())) {
            fields.put("visualSuggestion", List.of(before.visualSuggestion(), after.visualSuggestion()));
        }
        if (!Objects.equals(before.imageRequirement(), after.imageRequirement())) {
            fields.put("imageRequirement", Arrays.asList(before.imageRequirement(), after.imageRequirement()));
        }
        return fields;
    }
}
