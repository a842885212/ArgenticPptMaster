package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds bounded {@code template_fill_plan} confirmation contextData from stored plan artifacts.
 * Never includes raw notes/cell/chart text or absolute paths.
 */
public final class TemplateFillConfirmationSummary {

    public static final int MAX_PAGES = 40;
    public static final int MAX_IDS_PER_PAGE = 20;
    public static final int MAX_CAPACITY_ITEMS = 20;
    public static final int MAX_NOTE_CHARS = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemplateFillConfirmationSummary() {
    }

    public static Map<String, Object> enrich(
            PptJob job,
            TemplateFillPlanMetadata metadata,
            JsonNode plan,
            JsonNode serviceMeta,
            Map<String, Object> agentContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "template_fill_plan");
        context.put("version", metadata.version());
        context.put("digest", metadata.digest());
        context.put("constraintSatisfaction", constraintSatisfaction(job.templateConstraints(), plan));
        context.put("pages", pageSummaries(plan));
        context.put("capacityRisks", capacityRisks(serviceMeta));
        context.put("fontAdjustments", List.of()); // executable font adjust unsupported upstream
        context.put("aggregates", Map.of(
                "notesMappingCount", job.notesMappingCount(),
                "tableMappingCount", job.tableMappingCount(),
                "chartMappingCount", job.chartMappingCount(),
                "capacityRiskCount", job.capacityRiskCount(),
                "fontAdjustmentCount", job.fontAdjustmentCount(),
                "constraintStatus", job.constraintValidationStatus() == null
                        ? "NONE" : job.constraintValidationStatus()));
        // Preserve agent-provided acceptedWarnings if present (bounded codes only).
        Object accepted = agentContext.get("acceptedWarnings");
        if (accepted instanceof List<?> list) {
            context.put("acceptedWarnings", boundStringList(list, MAX_CAPACITY_ITEMS, MAX_NOTE_CHARS));
        } else if (plan.path("accepted_warnings").isArray()) {
            List<String> warnings = new ArrayList<>();
            for (JsonNode node : plan.path("accepted_warnings")) {
                if (warnings.size() >= MAX_CAPACITY_ITEMS) {
                    break;
                }
                String text = node.asText("");
                if (!text.isBlank()) {
                    warnings.add(truncate(text, MAX_NOTE_CHARS));
                }
            }
            context.put("acceptedWarnings", warnings);
        }
        return context;
    }

    public static Map<String, Object> fromWorkspace(PptJob job, Map<String, Object> agentContext) {
        Path analysis = job.workspacePath().toAbsolutePath().normalize().resolve("analysis");
        Path planPath = analysis.resolve("fill_plan.json");
        Path metaPath = analysis.resolve("fill_plan.meta.json");
        Path serviceMetaPath = analysis.resolve("fill_plan.service-meta.json");
        try {
            if (!Files.isRegularFile(planPath) || !Files.isRegularFile(metaPath)) {
                throw new IllegalStateException("template fill plan draft artifacts are missing");
            }
            JsonNode metaJson = MAPPER.readTree(Files.readAllBytes(metaPath));
            java.time.Instant approvedAt = metaJson.hasNonNull("approvedAt")
                    ? java.time.Instant.parse(metaJson.get("approvedAt").asText())
                    : null;
            String confirmationId = metaJson.hasNonNull("confirmationId")
                    ? metaJson.get("confirmationId").asText()
                    : null;
            TemplateFillPlanMetadata metadata = new TemplateFillPlanMetadata(
                    metaJson.path("version").asInt(),
                    metaJson.path("digest").asText(),
                    metaJson.path("status").asText("draft"),
                    confirmationId,
                    approvedAt);
            JsonNode plan = MAPPER.readTree(Files.readAllBytes(planPath));
            JsonNode serviceMeta = Files.isRegularFile(serviceMetaPath)
                    ? MAPPER.readTree(Files.readAllBytes(serviceMetaPath))
                    : MAPPER.createObjectNode();
            Map<String, Object> safeAgent = agentContext == null ? Map.of() : agentContext;
            return enrich(job, metadata, plan, serviceMeta, safeAgent);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to build template fill confirmation summary", ex);
        }
    }

    private static Map<String, Object> constraintSatisfaction(
            TemplateFillConstraints constraints, JsonNode plan) {
        TemplateFillConstraints effective = constraints == null
                ? TemplateFillConstraints.empty() : constraints;
        List<Integer> usedSlides = new ArrayList<>();
        for (JsonNode slide : plan.path("slides")) {
            if (slide.has("source_slide") && slide.get("source_slide").canConvertToInt()) {
                usedSlides.add(slide.get("source_slide").asInt());
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allowedTemplateSlides", effective.allowedTemplateSlides());
        summary.put("excludedTemplateSlides", effective.excludedTemplateSlides());
        summary.put("preserveCover", effective.preserveCover());
        summary.put("preserveEnding", effective.preserveEnding());
        summary.put("maxSlides", effective.maxSlides());
        summary.put("usedTemplateSlides", usedSlides.size() > MAX_PAGES
                ? usedSlides.subList(0, MAX_PAGES) : usedSlides);
        summary.put("planSlideCount", plan.path("slides").isArray() ? plan.path("slides").size() : 0);
        summary.put("satisfied", true);
        return summary;
    }

    private static List<Map<String, Object>> pageSummaries(JsonNode plan) {
        List<Map<String, Object>> pages = new ArrayList<>();
        if (!plan.path("slides").isArray()) {
            return pages;
        }
        for (JsonNode slide : plan.path("slides")) {
            if (pages.size() >= MAX_PAGES) {
                break;
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("sourceSlide", slide.path("source_slide").asInt(0));
            page.put("purpose", truncate(slide.path("purpose").asText(""), MAX_NOTE_CHARS));
            String notes = slide.path("notes").asText("");
            if (notes.isBlank()) {
                notes = slide.path("speaker_notes").asText("");
            }
            page.put("notesMappingCount", notes.isBlank() ? 0 : 1);
            page.put("tableMappingCount", slide.path("table_edits").isArray()
                    ? slide.path("table_edits").size() : 0);
            page.put("chartMappingCount", slide.path("chart_edits").isArray()
                    ? slide.path("chart_edits").size() : 0);
            page.put("transition", slide.path("transition").asText("fade"));
            page.put("tableIds", collectIds(slide.path("table_edits"), "table_id"));
            page.put("chartIds", collectIds(slide.path("chart_edits"), "chart_id"));
            page.put("slotIds", collectIds(slide.path("replacements"), "slot_id"));
            pages.add(page);
        }
        return pages;
    }

    private static List<Map<String, Object>> capacityRisks(JsonNode serviceMeta) {
        List<Map<String, Object>> risks = new ArrayList<>();
        JsonNode decisions = serviceMeta.path("capacityDecisions");
        if (!decisions.isArray()) {
            return risks;
        }
        for (JsonNode decision : decisions) {
            if (risks.size() >= MAX_CAPACITY_ITEMS) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceSlide", decision.path("source_slide").asInt(0));
            item.put("slotId", truncate(decision.path("slot_id").asText(""), MAX_NOTE_CHARS));
            item.put("selected", truncate(decision.path("selected").asText(""), MAX_NOTE_CHARS));
            List<String> evaluated = new ArrayList<>();
            if (decision.path("evaluated").isArray()) {
                for (JsonNode strategy : decision.path("evaluated")) {
                    evaluated.add(truncate(strategy.asText(""), MAX_NOTE_CHARS));
                }
            }
            item.put("evaluated", evaluated);
            item.put("note", truncate(decision.path("note").asText(""), MAX_NOTE_CHARS));
            risks.add(item);
        }
        return risks;
    }

    private static List<String> collectIds(JsonNode array, String field) {
        List<String> ids = new ArrayList<>();
        if (!array.isArray()) {
            return ids;
        }
        for (JsonNode node : array) {
            if (ids.size() >= MAX_IDS_PER_PAGE) {
                break;
            }
            String id = node.path(field).asText("");
            if (!id.isBlank()) {
                ids.add(truncate(id, MAX_NOTE_CHARS));
            }
        }
        return ids;
    }

    private static List<String> boundStringList(List<?> list, int maxItems, int maxChars) {
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (out.size() >= maxItems) {
                break;
            }
            if (item instanceof String text && !text.isBlank()) {
                out.add(truncate(text, maxChars));
            }
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
