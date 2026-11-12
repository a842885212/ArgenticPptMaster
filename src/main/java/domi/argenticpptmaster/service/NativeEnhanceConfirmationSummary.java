package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.engine.file.fishaudio.FishVoicePresets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds a bounded enhancement_plan_confirmation contextData from the persisted draft. */
public final class NativeEnhanceConfirmationSummary {

    public static final int MAX_NOTE_CHARS = 120;

    private NativeEnhanceConfirmationSummary() {
    }

    public static Map<String, Object> fromWorkspace(PptJob job, Map<String, Object> agentContext) {
        ObjectNode plan = NativeEnhancePlanStore.readPlan(job)
                .orElseThrow(() -> new IllegalStateException("enhancement plan draft artifacts are missing"));
        if (!"draft".equals(plan.path("status").asText())) {
            throw new IllegalStateException("enhancement plan confirmation requires a draft");
        }
        NativeEnhancePlanStore.PlanMetadata meta = NativeEnhancePlanStore.readDraft(job)
                .orElseThrow(() -> new IllegalStateException("enhancement plan draft metadata is missing"));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", NativeEnhancePlanStore.CONTEXT_TYPE);
        context.put("version", meta.version());
        context.put("digest", meta.digest());
        context.put("sourcePageCount", plan.path("slide_count").asInt(0));
        context.put("moduleSwitches", NativeEnhancePlanStore.moduleSwitches(plan));
        context.put("notesCoverage", coverage(plan.path("modules").path("notes").path("coverage")));
        context.put("audioCoverage", coverage(plan.path("modules").path("audio").path("coverage")));
        context.put("transitionSummary", transitionSummary(plan));
        if (agentContext != null && agentContext.get("message") instanceof String message && !message.isBlank()) {
            context.put("message", truncate(message, MAX_NOTE_CHARS));
        }
        return context;
    }

    public static Map<String, Object> audioVoiceContext(PptJob job, Map<String, Object> agentContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "native_pptx_audio_voice");
        context.put("provider", FishVoicePresets.PROVIDER);
        context.put("voice", FishVoicePresets.DEFAULT_VOICE);
        context.put("voices", FishVoicePresets.voiceLabels());
        if (agentContext != null) {
            if (agentContext.get("voice") instanceof String voice
                    && FishVoicePresets.isAllowed(voice)) {
                context.put("voice", truncate(voice, MAX_NOTE_CHARS));
            }
        }
        // 旧 edge 草稿不得静默映射：始终以 fish 默认覆盖展示。
        return context;
    }

    private static Map<String, Object> coverage(com.fasterxml.jackson.databind.JsonNode coverage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", coverage.path("ready").asInt(0));
        result.put("total", coverage.path("total").asInt(0));
        return result;
    }

    private static Map<String, Object> transitionSummary(ObjectNode plan) {
        var transitions = plan.path("modules").path("transitions");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabled", transitions.path("enabled").asBoolean(false));
        summary.put("effect", truncate(transitions.path("effect").asText("fade"), MAX_NOTE_CHARS));
        summary.put("duration", transitions.path("duration").asDouble(0.5d));
        summary.put("applyWithoutAudio", transitions.path("apply_without_audio").asBoolean(false));
        return summary;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
