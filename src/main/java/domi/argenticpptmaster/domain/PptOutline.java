package domi.argenticpptmaster.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 版本化的逐页 PPT 大纲。 */
public record PptOutline(int version, boolean locked, List<SlideOutline> slides) {

    public PptOutline {
        slides = slides == null ? List.of() : List.copyOf(slides);
    }

    public void validate() {
        if (version <= 0 || slides.isEmpty()) {
            throw new IllegalArgumentException("outline version and slides are required");
        }
        for (int index = 0; index < slides.size(); index++) {
            SlideOutline slide = slides.get(index);
            slide.validate();
            if (slide.slideNo() != index + 1) {
                throw new IllegalArgumentException("outline slide numbers must be continuous");
            }
        }
    }

    public PptOutline lock() {
        validate();
        return new PptOutline(version, true, slides);
    }

    public PptOutline apply(List<PptOutlineEdit> edits) {
        if (locked) {
            throw new IllegalStateException("locked outline cannot be edited");
        }
        List<SlideOutline> result = new ArrayList<>(slides);
        for (PptOutlineEdit edit : edits == null ? List.<PptOutlineEdit>of() : edits) {
            edit.validate();
            int index = edit.slideNo() - 1;
            switch (edit.operation()) {
                case ADD -> {
                    if (index < 0 || index > result.size()) {
                        throw new IllegalArgumentException("add slide number is invalid");
                    }
                    result.add(index, edit.slide());
                }
                case UPDATE -> {
                    if (index < 0 || index >= result.size()) {
                        throw new IllegalArgumentException("update slide number is invalid");
                    }
                    result.set(index, edit.slide());
                }
                case DELETE -> {
                    if (index < 0 || index >= result.size() || result.size() == 1) {
                        throw new IllegalArgumentException("delete slide number is invalid");
                    }
                    result.remove(index);
                }
            }
        }
        List<SlideOutline> renumbered = new ArrayList<>();
        for (int index = 0; index < result.size(); index++) {
            SlideOutline slide = result.get(index);
            renumbered.add(new SlideOutline(index + 1, slide.title(), slide.keyMessage(), slide.bullets(),
                    slide.visualSuggestion(), slide.imageRequirement()));
        }
        PptOutline next = new PptOutline(version + 1, false, renumbered);
        next.validate();
        return next;
    }

    /** 在显式影响确认后，从锁定版本创建新的未锁定修订草稿。 */
    public PptOutline reviseFromLocked(List<PptOutlineEdit> edits) {
        if (!locked) {
            return apply(edits);
        }
        return new PptOutline(version, false, slides).apply(edits);
    }

    /** 将确认 payload 中的通用 Map 转换为领域大纲。 */
    public static PptOutline fromPayload(int version, Object slidesValue) {
        if (!(slidesValue instanceof List<?> values)) {
            throw new IllegalArgumentException("outline slides are missing");
        }
        List<SlideOutline> slides = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("outline slide is invalid");
            }
            Object bulletsValue = map.get("bullets");
            if (!(bulletsValue instanceof List<?> bulletValues)) {
                throw new IllegalArgumentException("outline bullets are invalid");
            }
            List<String> bullets = bulletValues.stream().map(String::valueOf).toList();
            @SuppressWarnings("unchecked")
            Map<String, Object> imageRequirement = map.get("imageRequirement") instanceof Map<?, ?> image
                    ? (Map<String, Object>) image : null;
            slides.add(new SlideOutline(
                    number(map.get("slideNo")),
                    string(map.get("title")),
                    string(map.get("keyMessage")),
                    bullets,
                    string(map.get("visualSuggestion")),
                    imageRequirement));
        }
        PptOutline outline = new PptOutline(version, false, slides);
        outline.validate();
        return outline;
    }

    private static int number(Object value) {
        if (!(value instanceof Number number) || number.intValue() <= 0 || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("slide number is invalid");
        }
        return number.intValue();
    }

    private static String string(Object value) {
        return value instanceof String string ? string : "";
    }
}
