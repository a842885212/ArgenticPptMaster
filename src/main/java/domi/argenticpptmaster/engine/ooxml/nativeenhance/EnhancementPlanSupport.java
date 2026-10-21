// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Plan / transition resolution helpers for native enhance. */
public final class EnhancementPlanSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SLIDE_KEY = Pattern.compile("[1-9]\\d*");
    private static final Set<String> TRANSITION_MODULE_FIELDS = Set.of(
            "enabled",
            "requires_confirmation",
            "status",
            "effect",
            "duration",
            "effect_options",
            "apply_without_audio",
            "slides");

    public record ResolvedTransitionPlan(
            SlideMotionSupport.EnterUpdate globalEnter,
            Map<Integer, SlideMotionSupport.EnterUpdate> slideEnters,
            boolean applyWithoutAudio) {}

    private EnhancementPlanSupport() {}

    public static Set<String> enabledModules(JsonNode plan) {
        if (plan == null || !plan.path("modules").isObject()) {
            return new LinkedHashSet<>(NativeEnhanceConstants.WRITABLE_MODULES);
        }
        Set<String> enabled = new LinkedHashSet<>();
        JsonNode modules = plan.get("modules");
        for (String name : NativeEnhanceConstants.WRITABLE_MODULES) {
            JsonNode config = modules.get(name);
            if (config != null && config.path("enabled").asBoolean(false)) {
                enabled.add(name);
            }
        }
        return enabled;
    }

    public static boolean planConfirmed(JsonNode plan) {
        return plan != null && "confirmed".equals(plan.path("status").asText());
    }

    public static JsonNode loadPlan(Path project) throws IOException {
        Path plan = project.resolve("analysis/enhancement_plan.json");
        if (!Files.isRegularFile(plan)) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(plan.toFile());
    }

    public static void validatePlanModules(JsonNode plan, boolean allowLegacyAudioWithoutNotes) {
        if (plan != null && plan.size() > 0 && !NativeEnhanceConstants.PLAN_SCHEMA.equals(plan.path("schema").asText(null))) {
            throw new IllegalArgumentException(
                    "unsupported enhancement plan schema: " + plan.path("schema").asText());
        }
        JsonNode modulesCfg = plan == null ? null : plan.get("modules");
        if (modulesCfg == null) {
            return;
        }
        if (!modulesCfg.isObject()) {
            throw new IllegalArgumentException("enhancement plan modules must be an object");
        }
        modulesCfg.fieldNames().forEachRemaining(name -> {
            if (!NativeEnhanceConstants.WRITABLE_MODULES.contains(name)) {
                throw new IllegalArgumentException("enhancement plan has unknown module(s): " + name);
            }
        });
        if (NativeEnhanceConstants.PLAN_SCHEMA.equals(plan.path("schema").asText())) {
            for (String name : NativeEnhanceConstants.WRITABLE_MODULES) {
                if (!modulesCfg.has(name)) {
                    throw new IllegalArgumentException("enhancement plan is missing module(s): " + name);
                }
            }
        }
        for (String name : NativeEnhanceConstants.WRITABLE_MODULES) {
            JsonNode config = modulesCfg.get(name);
            if (config != null && !config.isObject()) {
                throw new IllegalArgumentException("enhancement plan module " + name + " must be an object");
            }
            if (config != null && (!config.has("enabled") || !config.get("enabled").isBoolean())) {
                throw new IllegalArgumentException("enhancement plan module " + name + ".enabled must be a boolean");
            }
        }
        JsonNode notesConfig = modulesCfg.get("notes");
        JsonNode audioConfig = modulesCfg.get("audio");
        boolean legacy = allowLegacyAudioWithoutNotes
                && notesConfig != null
                && notesConfig.path("enabled").asBoolean(true) == false;
        if (audioConfig != null
                && audioConfig.path("enabled").asBoolean(false)
                && !legacy
                && (notesConfig == null || !notesConfig.path("enabled").asBoolean(false))) {
            throw new IllegalArgumentException("enhancement plan audio requires notes.enabled: true");
        }
    }

    public static ObjectNode buildEnhancementPlan(
            JsonNode project,
            int slideCount,
            int notesCount,
            int audioCount,
            String transition,
            Double transitionDuration,
            Double narrationPadding,
            Double narrationStartFloor,
            Boolean applyTransitionWithoutAudio,
            JsonNode existingPlan) {
        JsonNode previous = existingPlan == null ? MAPPER.createObjectNode() : existingPlan;
        boolean audioEnabled = preservedEnabled(previous, "audio", true);
        boolean notesEnabled = preservedEnabled(previous, "notes", true) || audioEnabled;
        boolean timingsEnabled = preservedEnabled(previous, "timings", true);

        JsonNode previousTimings = moduleConfig(previous, "timings");
        double resolvedPadding = SlideMotionSupport.validateSeconds(
                narrationPadding != null
                        ? narrationPadding
                        : previousTimings.path("narration_padding").asDouble(NativeEnhanceConstants.DEFAULT_NARRATION_PADDING),
                "narration padding",
                true);
        double resolvedStartFloor = SlideMotionSupport.validateSeconds(
                narrationStartFloor != null
                        ? narrationStartFloor
                        : previousTimings
                                .path("narration_start_floor")
                                .asDouble(NativeEnhanceConstants.DEFAULT_NARRATION_START_FLOOR),
                "narration start floor",
                true);

        TransitionDraft draft = resolvedDraftTransitionConfig(
                project, previous, transition, transitionDuration, applyTransitionWithoutAudio);

        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("schema", NativeEnhanceConstants.PLAN_SCHEMA);
        plan.put("status", "draft");
        plan.put("source_pptx", project.path("source_pptx").asText(null));
        plan.put("slide_count", slideCount);
        ObjectNode modules = plan.putObject("modules");

        ObjectNode notes = modules.putObject("notes");
        notes.put("enabled", notesEnabled);
        notes.put("requires_confirmation", true);
        notes.put(
                "status",
                !notesEnabled ? "disabled" : (notesCount == slideCount ? "coverage_complete" : "needs_notes"));
        ObjectNode notesCoverage = notes.putObject("coverage");
        notesCoverage.put("present", notesCount);
        notesCoverage.put("total", slideCount);

        ObjectNode audio = modules.putObject("audio");
        audio.put("enabled", audioEnabled);
        audio.put("requires_confirmation", true);
        audio.put(
                "status",
                !audioEnabled ? "disabled" : (audioCount == slideCount ? "coverage_complete" : "needs_audio"));
        ObjectNode audioCoverage = audio.putObject("coverage");
        audioCoverage.put("present", audioCount);
        audioCoverage.put("total", slideCount);
        audio.put("decodability", "unchecked");

        ObjectNode timings = modules.putObject("timings");
        timings.put("enabled", timingsEnabled);
        timings.put("requires_confirmation", true);
        timings.put(
                "status",
                !timingsEnabled
                        ? "disabled"
                        : (audioEnabled && audioCount == slideCount ? "audio_coverage_complete" : "blocked_until_audio"));
        timings.put("source", "audio_duration");
        timings.put("narration_padding", resolvedPadding);
        timings.put("narration_start_floor", resolvedStartFloor);

        ObjectNode transitions = modules.putObject("transitions");
        transitions.put("enabled", draft.enabled());
        transitions.put("requires_confirmation", true);
        transitions.put(
                "status",
                draft.enabled()
                                || "none".equals(draft.config().path("effect").asText())
                                || draft.config().path("slides").isObject()
                        ? "ready"
                        : "disabled");
        transitions.setAll((ObjectNode) draft.config());

        plan.putArray("not_in_v1")
                .add("object_animation")
                .add("visible_watermark")
                .add("footer_or_logo_insertion")
                .add("background_music")
                .add("media_compression");
        return plan;
    }

    public static ResolvedTransitionPlan resolveTransitionPlan(
            JsonNode project,
            JsonNode plan,
            List<SlidePartRef> slides,
            String cliEffect,
            Double cliDuration,
            boolean cliApplyWithoutAudio) {
        validatePlanModules(plan, false);
        if (plan.has("slide_count") && plan.path("slide_count").asInt() != slides.size()) {
            throw new IllegalArgumentException(
                    "enhancement plan slide_count no longer matches the archived source: "
                            + plan.path("slide_count").asInt()
                            + " != "
                            + slides.size());
        }
        Set<String> modules = enabledModules(plan);
        JsonNode transitionsCfg = moduleConfig(plan, "transitions");
        transitionsCfg.fieldNames().forEachRemaining(name -> {
            if (!TRANSITION_MODULE_FIELDS.contains(name)) {
                throw new IllegalArgumentException("transition module has unknown field(s): " + name);
            }
        });

        JsonNode projectTransition =
                project.path("transition").isObject() ? project.get("transition") : MAPPER.createObjectNode();
        String configuredEffect;
        if (transitionsCfg.has("effect")) {
            configuredEffect = transitionsCfg.path("effect").asText();
        } else if (projectTransition.has("effect")) {
            configuredEffect = projectTransition.path("effect").asText();
        } else {
            configuredEffect = "fade";
        }
        double duration = SlideMotionSupport.validateSeconds(
                cliDuration != null
                        ? cliDuration
                        : transitionsCfg.has("duration")
                                ? transitionsCfg.path("duration").asDouble()
                                : projectTransition.has("duration")
                                        ? projectTransition.path("duration").asDouble()
                                        : NativeEnhanceConstants.DEFAULT_TRANSITION_DURATION,
                "transition duration",
                false);

        SlideMotionSupport.EnterUpdate globalEnter =
                resolveEnterUpdate(cliEffect, configuredEffect, modules.contains("transitions"), duration);

        boolean applyWithoutAudio = cliApplyWithoutAudio || transitionsCfg.path("apply_without_audio").asBoolean(false);
        if (!modules.contains("audio")
                && (modules.contains("transitions")
                        || cliEffect != null
                        || "none".equals(globalEnter.policy()))) {
            applyWithoutAudio = true;
        }

        Map<Integer, SlideMotionSupport.EnterUpdate> slideEnters = new LinkedHashMap<>();
        JsonNode rawSlides = transitionsCfg.path("slides");
        if (rawSlides.isObject()) {
            Set<Integer> valid = new LinkedHashSet<>();
            slides.forEach(s -> valid.add(s.index()));
            SlideMotionSupport.EnterUpdate selectedBase =
                    resolveEnterUpdate(cliEffect, configuredEffect, true, duration);
            rawSlides.fields().forEachRemaining(entry -> {
                if (!SLIDE_KEY.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException(
                            "transition slide key must be a canonical 1-based index: " + entry.getKey());
                }
                int slideIndex = Integer.parseInt(entry.getKey());
                if (!valid.contains(slideIndex)) {
                    throw new IllegalArgumentException(
                            "transition slide index is outside the source roster: " + slideIndex);
                }
                if (!entry.getValue().isObject()) {
                    throw new IllegalArgumentException("transition slides." + slideIndex + " must be an object");
                }
                slideEnters.put(slideIndex, resolveSlideEnter(selectedBase, entry.getValue(), slideIndex));
            });
        }
        return new ResolvedTransitionPlan(globalEnter, slideEnters, applyWithoutAudio);
    }

    public static SlideMotionSupport.EnterUpdate resolveEnterUpdate(
            String cliEffect, String configuredEffect, boolean transitionsEnabled, double duration) {
        if (cliEffect == null && !transitionsEnabled) {
            if ("none".equals(configuredEffect)) {
                SlideMotionSupport.normalizeTransitionEffect("none", true);
                return SlideMotionSupport.EnterUpdate.none(duration);
            }
            return SlideMotionSupport.EnterUpdate.preserve(duration);
        }
        String effect = cliEffect != null ? cliEffect : configuredEffect;
        if ("none".equals(effect)) {
            SlideMotionSupport.normalizeTransitionEffect("none", true);
            return SlideMotionSupport.EnterUpdate.none(duration);
        }
        String normalized = SlideMotionSupport.normalizeTransitionEffect(effect, false);
        return SlideMotionSupport.EnterUpdate.replace(normalized, duration);
    }

    private static SlideMotionSupport.EnterUpdate resolveSlideEnter(
            SlideMotionSupport.EnterUpdate base, JsonNode override, int slideIndex) {
        double duration = SlideMotionSupport.validateSeconds(
                override.has("duration") ? override.path("duration").asDouble() : base.duration(),
                "transition slides." + slideIndex + ".duration",
                false);
        if (override.has("effect") && "preserve".equals(override.path("effect").asText())) {
            return SlideMotionSupport.EnterUpdate.preserve(duration);
        }
        if (!override.has("effect")) {
            if ("preserve".equals(base.policy())) {
                return SlideMotionSupport.EnterUpdate.preserve(duration);
            }
            if ("none".equals(base.policy())) {
                return SlideMotionSupport.EnterUpdate.none(duration);
            }
            return SlideMotionSupport.EnterUpdate.replace(base.effect(), duration);
        }
        String effect = override.path("effect").asText();
        if ("none".equals(effect)) {
            return SlideMotionSupport.EnterUpdate.none(duration);
        }
        return SlideMotionSupport.EnterUpdate.replace(
                SlideMotionSupport.normalizeTransitionEffect(effect, false), duration);
    }

    private static TransitionDraft resolvedDraftTransitionConfig(
            JsonNode project,
            JsonNode existingPlan,
            String transition,
            Double transitionDuration,
            Boolean applyTransitionWithoutAudio) {
        JsonNode existing = moduleConfig(existingPlan, "transitions");
        JsonNode projectDefault =
                project.path("transition").isObject() ? project.get("transition") : MAPPER.createObjectNode();
        String rawEffect;
        if (transition != null) {
            rawEffect = transition;
        } else if (existing.has("effect")) {
            rawEffect = existing.path("effect").asText();
        } else if (projectDefault.has("effect")) {
            rawEffect = projectDefault.path("effect").asText();
        } else {
            rawEffect = "fade";
        }
        double duration = SlideMotionSupport.validateSeconds(
                transitionDuration != null
                        ? transitionDuration
                        : existing.has("duration")
                                ? existing.path("duration").asDouble()
                                : projectDefault.has("duration")
                                        ? projectDefault.path("duration").asDouble()
                                        : NativeEnhanceConstants.DEFAULT_TRANSITION_DURATION,
                "transition duration",
                false);
        ObjectNode config = MAPPER.createObjectNode();
        if ("none".equals(rawEffect)) {
            config.put("effect", "none");
            config.put("duration", duration);
        } else {
            config.put("effect", SlideMotionSupport.normalizeTransitionEffect(rawEffect, false));
            config.put("duration", duration);
        }
        boolean applyWithoutAudio = applyTransitionWithoutAudio != null
                ? applyTransitionWithoutAudio
                : existing.path("apply_without_audio").asBoolean(false);
        config.put("apply_without_audio", applyWithoutAudio);
        if (existing.has("slides") && existing.get("slides").isObject()) {
            config.set("slides", existing.get("slides").deepCopy());
        }
        boolean enabled = transition != null ? !"none".equals(transition) : preservedEnabled(existingPlan, "transitions", !"none".equals(rawEffect));
        return new TransitionDraft(enabled, config);
    }

    private static boolean preservedEnabled(JsonNode plan, String name, boolean defaultValue) {
        JsonNode config = moduleConfig(plan, name);
        if (!config.has("enabled")) {
            return defaultValue;
        }
        if (!config.get("enabled").isBoolean()) {
            throw new IllegalArgumentException("enhancement plan module " + name + ".enabled must be a boolean");
        }
        return config.path("enabled").asBoolean();
    }

    private static JsonNode moduleConfig(JsonNode plan, String name) {
        JsonNode modules = plan == null ? null : plan.get("modules");
        if (modules == null || !modules.isObject()) {
            return MAPPER.createObjectNode();
        }
        JsonNode config = modules.get(name);
        return config != null && config.isObject() ? config : MAPPER.createObjectNode();
    }

    private record TransitionDraft(boolean enabled, ObjectNode config) {}
}
