package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a bounded beautify_plan_confirmation contextData from the persisted draft. */
public final class BeautifyConfirmationSummary {

    public static final int MAX_ALERT_ITEMS = 20;
    public static final int MAX_NOTE_CHARS = 120;
    public static final int MAX_CANDIDATES = 8;

    private BeautifyConfirmationSummary() {
    }

    public static Map<String, Object> fromWorkspace(PptJob job, Map<String, Object> agentContext) {
        ObjectNode plan = BeautifyPptxPlanStore.readPlan(job)
                .orElseThrow(() -> new IllegalStateException("beautify plan draft artifacts are missing"));
        if (!"draft".equals(plan.path("status").asText())) {
            throw new IllegalStateException("beautify plan confirmation requires a draft");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "beautify_pptx_plan");
        context.put("version", plan.path("version").asInt());
        context.put("digest", plan.path("digest").asText());
        context.put("sourcePageCount", plan.path("sourcePageCount").asInt());
        context.put("validationStrictness", plan.path("validationStrictness").asText("strict"));
        context.put("identityRecommendation", boundIdentity(plan.path("identityRecommendation")));
        context.put("ignored", boundAlerts(plan.path("ignored")));
        context.put("needsConfirmation", boundAlerts(plan.path("needsConfirmation")));
        context.put("ignoredCount", plan.path("ignored").isArray() ? plan.path("ignored").size() : 0);
        context.put("needsConfirmationCount",
                plan.path("needsConfirmation").isArray() ? plan.path("needsConfirmation").size() : 0);
        if (agentContext != null && agentContext.get("message") instanceof String message && !message.isBlank()) {
            context.put("message", truncate(message, MAX_NOTE_CHARS));
        }
        return context;
    }

    private static Map<String, Object> boundIdentity(JsonNode recommendation) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("source", recommendation.path("source").asText());
        identity.put("paletteCandidateId", recommendation.path("paletteCandidateId").asText());
        identity.put("fontCandidateId", recommendation.path("fontCandidateId").asText());
        identity.put("bodySizeCandidateId", recommendation.path("bodySizeCandidateId").asText());
        identity.put("rationale", truncate(recommendation.path("rationale").asText(""), MAX_NOTE_CHARS));
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (recommendation.path("candidates").isArray()) {
            for (JsonNode candidate : recommendation.path("candidates")) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                if (candidate.isObject()) {
                    item.put("id", truncate(candidate.path("id").asText(""), MAX_NOTE_CHARS));
                    if (candidate.path("label").isTextual()) {
                        item.put("label", truncate(candidate.path("label").asText(), MAX_NOTE_CHARS));
                    }
                } else {
                    item.put("id", truncate(candidate.asText(""), MAX_NOTE_CHARS));
                }
                candidates.add(item);
            }
        }
        identity.put("candidates", candidates);
        return identity;
    }

    private static List<Map<String, Object>> boundAlerts(JsonNode alerts) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!alerts.isArray()) {
            return result;
        }
        for (JsonNode alert : alerts) {
            if (result.size() >= MAX_ALERT_ITEMS) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (alert.isObject()) {
                item.put("page", alert.path("page").asInt(alert.path("slide_index").asInt(0)));
                item.put("type", truncate(alert.path("type").asText(alert.path("kind").asText("")), MAX_NOTE_CHARS));
                item.put("reason", truncate(alert.path("reason").asText(""), MAX_NOTE_CHARS));
                if (alert.path("suggestion").isTextual()) {
                    item.put("suggestion", truncate(alert.path("suggestion").asText(), MAX_NOTE_CHARS));
                }
            } else {
                item.put("reason", truncate(alert.asText(""), MAX_NOTE_CHARS));
            }
            result.add(item);
        }
        return result;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
