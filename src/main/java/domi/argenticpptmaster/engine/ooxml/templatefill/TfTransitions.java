// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import domi.argenticpptmaster.engine.ooxml.nativeenhance.SlideMotionSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Page-to-page transitions for cloned slides.
 *
 * <p>Preserves each source transition unless the CLI default or a per-slide plan entry
 * requests a replacement. Delegates OOXML mutation to {@link SlideMotionSupport}.</p>
 */
public final class TfTransitions {

    public record ResolvedTransition(
            String effect, Map<String, String> effectOptions, double duration, Double advanceAfter) {}

    private TfTransitions() {}

    public static List<String> transitionUnknownFields(JsonNode raw) {
        List<String> unknown = new ArrayList<>();
        raw.fieldNames().forEachRemaining(name -> {
            if (!TfConstants.TRANSITION_OBJECT_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        unknown.sort(String::compareTo);
        return unknown;
    }

    public static ResolvedTransition resolveSlideTransition(
            JsonNode item, String defaultEffect, double defaultDuration) {
        if (!item.has("transition")) {
            if (defaultEffect == null
                    || "none".equals(defaultEffect)
                    || TfConstants.KEEP_TRANSITION.equals(defaultEffect)) {
                return new ResolvedTransition(defaultEffect, Map.of(), defaultDuration, null);
            }
            String effect = SlideMotionSupport.normalizeTransitionEffect(defaultEffect, false);
            return new ResolvedTransition(effect, Map.of(), defaultDuration, null);
        }
        JsonNode raw = item.get("transition");
        String effect;
        Map<String, String> effectOptions = new LinkedHashMap<>();
        double duration = defaultDuration;
        Double advanceAfter = null;
        if (raw != null && raw.isObject()) {
            List<String> unknown = transitionUnknownFields(raw);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Transition has unknown field(s): " + String.join(", ", unknown));
            }
            effect = raw.has("effect")
                    ? (raw.get("effect").isNull() ? null : raw.get("effect").asText())
                    : defaultEffect;
            JsonNode rawOptions = raw.get("effect_options");
            if (rawOptions != null && !rawOptions.isNull() && !raw.has("effect")) {
                throw new IllegalArgumentException("Transition effect_options requires an explicit effect");
            }
            if (raw.has("duration") && !raw.get("duration").isNull()) {
                duration = SlideMotionSupport.validateSeconds(
                        raw.get("duration").numberValue(), "transition duration", false);
            }
            if (raw.has("advance_after") && !raw.get("advance_after").isNull()) {
                advanceAfter = raw.get("advance_after").asDouble();
            }
            if (rawOptions != null && rawOptions.isObject()) {
                rawOptions.fields()
                        .forEachRemaining(entry -> effectOptions.put(entry.getKey(), entry.getValue().asText()));
            }
        } else {
            effect = raw == null || raw.isNull() ? null : raw.asText();
        }
        if (effect != null && !"none".equals(effect) && !TfConstants.KEEP_TRANSITION.equals(effect)) {
            effect = SlideMotionSupport.normalizeTransitionEffect(effect, false);
        } else if (!effectOptions.isEmpty()) {
            throw new IllegalArgumentException("Transition effect_options requires an explicit native effect");
        }
        duration = SlideMotionSupport.validateSeconds(duration, "transition duration", false);
        return new ResolvedTransition(effect, Map.copyOf(effectOptions), duration, advanceAfter);
    }

    public static boolean setSlideTransition(
            Element slideRoot,
            String effect,
            Map<String, String> effectOptions,
            double duration,
            Double advanceAfter) {
        SlideMotionSupport.EnterUpdate enter;
        SlideMotionSupport.AdvanceUpdate advance;
        if (effect == null || TfConstants.KEEP_TRANSITION.equals(effect)) {
            enter = SlideMotionSupport.EnterUpdate.preserve(duration);
            advance = advanceAfter == null
                    ? SlideMotionSupport.AdvanceUpdate.preserve()
                    : new SlideMotionSupport.AdvanceUpdate("both", advanceAfter);
        } else if ("none".equals(effect)) {
            enter = SlideMotionSupport.EnterUpdate.none(duration);
            advance = advanceAfter == null
                    ? new SlideMotionSupport.AdvanceUpdate("click", null)
                    : new SlideMotionSupport.AdvanceUpdate("both", advanceAfter);
        } else {
            enter = new SlideMotionSupport.EnterUpdate("replace", effect, duration, effectOptions);
            advance = advanceAfter == null
                    ? new SlideMotionSupport.AdvanceUpdate("click", null)
                    : new SlideMotionSupport.AdvanceUpdate("both", advanceAfter);
        }
        try {
            return SlideMotionSupport.applySlideMotion(slideRoot, enter, advance);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }
}
