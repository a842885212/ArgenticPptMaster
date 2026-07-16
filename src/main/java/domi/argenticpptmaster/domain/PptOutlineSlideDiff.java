package domi.argenticpptmaster.domain;

import java.util.Map;

/** 单页大纲的可审阅差异。 */
public record PptOutlineSlideDiff(
        PptOutlineDiffType type,
        int slideNo,
        SlideOutline before,
        SlideOutline after,
        Map<String, Object> changedFields) {

    public PptOutlineSlideDiff {
        if (type == null || slideNo <= 0) {
            throw new IllegalArgumentException("outline diff type and slide number are required");
        }
        changedFields = changedFields == null ? Map.of() : Map.copyOf(changedFields);
    }
}
