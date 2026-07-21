package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.TemplateFillCapabilityIndex;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 校验 fill plan 草案结构与 slide library / 能力索引引用（上游 template_fill_pptx_plan.v1）。 */
@Component
public class TemplateFillPlanValidator {

    public static final int MAX_PREVIEW_CHARS = 500;
    public static final String PLAN_SCHEMA = "template_fill_pptx_plan.v1";
    static final int MAX_LIST_ITEMS = 50;
    static final int MAX_LIST_ITEM_CHARS = 500;
    static final Set<String> SUPPORTED_TRANSITIONS = Set.of(
            "fade", "push", "wipe", "split", "strips", "cover", "random", "none", "keep");
    static final Set<String> CAPACITY_STRATEGIES = Set.of(
            "rewrite", "split-or-larger-layout", "font-adjust");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateFillCapabilityIndexLoader capabilityIndexLoader;

    public TemplateFillPlanValidator(TemplateFillCapabilityIndexLoader capabilityIndexLoader) {
        this.capabilityIndexLoader = capabilityIndexLoader;
    }

    public TemplateFillPlanValidator() {
        this(new TemplateFillCapabilityIndexLoader());
    }

    public JsonNode parseAndValidateDraft(String jsonPlan, Path slideLibraryPath) {
        return parseAndValidateDraft(jsonPlan, slideLibraryPath, TemplateFillConstraints.empty());
    }

    public JsonNode parseAndValidateDraft(
            String jsonPlan, Path slideLibraryPath, TemplateFillConstraints constraints) {
        if (jsonPlan == null || jsonPlan.isBlank()) {
            throw new PptJobStateException("fill plan JSON is required");
        }
        JsonNode plan;
        try {
            plan = objectMapper.readTree(jsonPlan);
        } catch (Exception ex) {
            throw new PptJobStateException("fill plan must be valid JSON");
        }
        if (plan == null || !plan.isObject()) {
            throw new PptJobStateException("fill plan must be a JSON object");
        }
        String status = textOrNull(plan.get("status"));
        if (!"draft".equalsIgnoreCase(status)) {
            throw new PptJobStateException("fill plan status must be draft");
        }
        String schema = textOrNull(plan.get("schema"));
        if (schema != null && !PLAN_SCHEMA.equals(schema)) {
            throw new PptJobStateException("fill plan schema must be " + PLAN_SCHEMA);
        }
        if (looksLikeLegacyServicePlan(plan)) {
            throw new PptJobStateException(
                    "legacy fill plan fields (outputOrder/templateSlideIndex/slotMappings) are not supported; "
                            + "use upstream " + PLAN_SCHEMA);
        }
        JsonNode slides = plan.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            throw new PptJobStateException("fill plan must contain at least one slide");
        }
        TemplateFillCapabilityIndex index = capabilityIndexLoader.load(slideLibraryPath);
        TemplateFillConstraints effective = constraints == null ? TemplateFillConstraints.empty() : constraints;
        validateAgainstConstraints(slides, index, effective);

        for (JsonNode slide : slides) {
            int sourceSlide = requirePositiveInt(slide.get("source_slide"), "source_slide");
            TemplateFillCapabilityIndex.SlideCapability slideCapability = index.slide(sourceSlide)
                    .orElseThrow(() -> new PptJobStateException(
                            "fill plan references unknown template slide: " + sourceSlide));
            rejectAnimationEdits(slide);
            validateTransition(slide);
            validateReplacements(slide, slideCapability);
            validateTableEdits(slide, slideCapability);
            validateChartEdits(slide, slideCapability);
            boundString(slide.get("notes"), "notes", MAX_LIST_ITEM_CHARS * 4);
            boundString(slide.get("speaker_notes"), "speaker_notes", MAX_LIST_ITEM_CHARS * 4);
            boundString(slide.path("layout_rationale").get("why_fit"), "layout_rationale.why_fit", MAX_PREVIEW_CHARS);
            boundString(slide.path("layout_rationale").get("risk"), "layout_rationale.risk", MAX_PREVIEW_CHARS);
        }
        requireBoundedList(plan.get("accepted_warnings"), "accepted_warnings");
        requireBoundedList(plan.get("acceptedWarnings"), "acceptedWarnings");
        return plan;
    }

    public ObjectNode normalizeDraft(JsonNode plan, int version) {
        ObjectNode normalized = plan.deepCopy();
        normalized.put("schema", PLAN_SCHEMA);
        normalized.put("status", "draft");
        // Service plan version lives in service-meta; keep optional echo for confirmation UX.
        normalized.put("service_version", version);
        ArrayNode slides = normalized.withArray("slides");
        for (JsonNode slideNode : slides) {
            ObjectNode slide = (ObjectNode) slideNode;
            if (!slide.hasNonNull("transition") || slide.get("transition").asText().isBlank()) {
                slide.put("transition", "fade");
            }
            if (!slide.has("table_edits")) {
                slide.putArray("table_edits");
            }
            if (!slide.has("chart_edits")) {
                slide.putArray("chart_edits");
            }
            if (!slide.has("replacements")) {
                slide.putArray("replacements");
            }
        }
        if (!normalized.has("accepted_warnings")) {
            normalized.putArray("accepted_warnings");
        }
        return normalized;
    }

    public void validateCapacityDecisions(JsonNode decisions) {
        if (decisions == null || decisions.isNull()) {
            return;
        }
        if (!decisions.isArray()) {
            throw new PptJobStateException("capacityDecisions must be an array");
        }
        if (decisions.size() > MAX_LIST_ITEMS) {
            throw new PptJobStateException("capacityDecisions exceeds item limit");
        }
        for (JsonNode decision : decisions) {
            JsonNode evaluated = decision.get("evaluated");
            if (evaluated == null || !evaluated.isArray() || evaluated.isEmpty()) {
                throw new PptJobStateException("capacity decision evaluated strategies are required");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode strategy : evaluated) {
                String value = textOrNull(strategy);
                if (value == null || !CAPACITY_STRATEGIES.contains(value)) {
                    throw new PptJobStateException("unknown capacity strategy: " + value);
                }
                seen.add(value);
            }
            String selected = textOrNull(decision.get("selected"));
            if (selected == null || !CAPACITY_STRATEGIES.contains(selected)) {
                throw new PptJobStateException("capacity decision selected strategy is invalid");
            }
            if ("font-adjust".equals(selected)) {
                if (!seen.contains("rewrite") || !seen.contains("split-or-larger-layout")) {
                    throw new PptJobStateException(
                            "font-adjust requires rewrite and split-or-larger-layout to be evaluated first");
                }
                throw new PptJobStateException(
                        "font-adjust is not supported by pinned upstream apply; rewrite or split instead");
            }
            boundString(decision.get("note"), "capacityDecision.note", MAX_PREVIEW_CHARS);
        }
    }

    public void validateServiceMetaFontAdjustments(JsonNode fontAdjustments) {
        if (fontAdjustments == null || fontAdjustments.isNull()) {
            return;
        }
        if (!fontAdjustments.isArray()) {
            throw new PptJobStateException("fontAdjustments must be an array");
        }
        if (!fontAdjustments.isEmpty()) {
            throw new PptJobStateException(
                    "executable font adjustments are unsupported by pinned template_fill_pptx apply");
        }
    }

    private void validateAgainstConstraints(
            JsonNode slides,
            TemplateFillCapabilityIndex index,
            TemplateFillConstraints constraints) {
        if (constraints.maxSlides() != null && slides.size() > constraints.maxSlides()) {
            throw new PptJobStateException("fill plan exceeds maxSlides=" + constraints.maxSlides());
        }
        boolean sawCover = false;
        boolean sawEnding = false;
        for (JsonNode slide : slides) {
            int sourceSlide = requirePositiveInt(slide.get("source_slide"), "source_slide");
            if (!constraints.allowedTemplateSlides().isEmpty()
                    && !constraints.allowedTemplateSlides().contains(sourceSlide)) {
                throw new PptJobStateException("source_slide is not in allowedTemplateSlides: " + sourceSlide);
            }
            if (constraints.excludedTemplateSlides().contains(sourceSlide)) {
                throw new PptJobStateException("source_slide is excluded: " + sourceSlide);
            }
            TemplateFillCapabilityIndex.SlideCapability capability = index.slide(sourceSlide).orElse(null);
            if (capability != null && capability.isCover()) {
                sawCover = true;
            }
            if (capability != null && capability.isEnding()) {
                sawEnding = true;
            }
        }
        if (constraints.preserveCover() && !sawCover) {
            throw new PptJobStateException("fill plan must preserve cover template slide");
        }
        if (constraints.preserveEnding() && !sawEnding) {
            throw new PptJobStateException("fill plan must preserve ending template slide");
        }
        if (constraints.maxSlides() != null
                && constraints.requiredBoundaryCount() > constraints.maxSlides()) {
            throw new PptJobStateException("maxSlides is smaller than required boundary slides");
        }
    }

    private void validateReplacements(
            JsonNode slide, TemplateFillCapabilityIndex.SlideCapability slideCapability) {
        JsonNode replacements = slide.get("replacements");
        if (replacements == null) {
            return;
        }
        if (!replacements.isArray()) {
            throw new PptJobStateException("replacements must be an array");
        }
        for (JsonNode replacement : replacements) {
            if (replacement.has("font_size_px") || replacement.has("fontSizePx") || replacement.has("font_adjust")) {
                throw new PptJobStateException(
                        "executable font adjustments are unsupported by pinned template_fill_pptx apply");
            }
            String slotId = textOrNull(replacement.get("slot_id"));
            if (slotId == null || !slideCapability.textSlots().containsKey(slotId)) {
                throw new PptJobStateException("fill plan references unknown slot: " + slotId);
            }
            boundString(replacement.get("text"), "replacement.text", MAX_LIST_ITEM_CHARS * 4);
            boundString(replacement.get("old_text"), "replacement.old_text", MAX_LIST_ITEM_CHARS * 4);
        }
    }

    private void validateTableEdits(
            JsonNode slide, TemplateFillCapabilityIndex.SlideCapability slideCapability) {
        JsonNode tableEdits = slide.get("table_edits");
        if (tableEdits == null) {
            return;
        }
        if (!tableEdits.isArray()) {
            throw new PptJobStateException("table_edits must be an array");
        }
        for (JsonNode edit : tableEdits) {
            String tableId = textOrNull(edit.get("table_id"));
            TemplateFillCapabilityIndex.TableCapability table = tableId == null
                    ? null
                    : slideCapability.tables().get(tableId);
            if (table == null) {
                throw new PptJobStateException("fill plan references unknown table: " + tableId);
            }
            JsonNode cells = edit.get("cells");
            if (cells == null || !cells.isArray()) {
                throw new PptJobStateException("table edit cells must be an array");
            }
            for (JsonNode cell : cells) {
                int row = requireNonNegativeInt(cell.get("row"), "table cell row");
                int col = requireNonNegativeInt(cell.get("col"), "table cell col");
                if (!table.inBounds(row, col)) {
                    throw new PptJobStateException(
                            "table cell out of bounds for " + tableId + ": row=" + row + " col=" + col);
                }
                boundString(cell.get("text"), "table cell text", MAX_LIST_ITEM_CHARS);
            }
        }
    }

    private void validateChartEdits(
            JsonNode slide, TemplateFillCapabilityIndex.SlideCapability slideCapability) {
        JsonNode chartEdits = slide.get("chart_edits");
        if (chartEdits == null) {
            return;
        }
        if (!chartEdits.isArray()) {
            throw new PptJobStateException("chart_edits must be an array");
        }
        for (JsonNode edit : chartEdits) {
            String chartId = textOrNull(edit.get("chart_id"));
            TemplateFillCapabilityIndex.ChartCapability chart = chartId == null
                    ? null
                    : slideCapability.charts().get(chartId);
            if (chart == null) {
                throw new PptJobStateException("fill plan references unknown chart: " + chartId);
            }
            JsonNode categories = edit.get("categories");
            JsonNode series = edit.get("series");
            if (categories != null && !categories.isArray()) {
                throw new PptJobStateException("chart categories must be an array");
            }
            if (series == null || !series.isArray() || series.isEmpty()) {
                throw new PptJobStateException("chart series must be a non-empty array");
            }
            int categoryCount = categories == null ? chart.categoryCount() : categories.size();
            for (JsonNode seriesNode : series) {
                JsonNode values = seriesNode.get("values");
                if (values == null || !values.isArray()) {
                    throw new PptJobStateException("chart series values must be an array");
                }
                if (categoryCount > 0 && values.size() != categoryCount) {
                    throw new PptJobStateException(
                            "chart series values size must match categories for " + chartId);
                }
                boundString(seriesNode.get("name"), "chart series name", MAX_PREVIEW_CHARS);
            }
        }
    }

    private void validateTransition(JsonNode slide) {
        String transition = textOrNull(slide.get("transition"));
        if (transition == null || transition.isBlank()) {
            return;
        }
        String normalized = transition.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TRANSITIONS.contains(normalized)) {
            throw new PptJobStateException("unsupported page transition: " + transition);
        }
    }

    private static void rejectAnimationEdits(JsonNode slide) {
        if (slide.has("animations") || slide.has("object_animations") || slide.has("entrance_animations")) {
            throw new PptJobStateException("object-level animation editing is not supported");
        }
    }

    private static boolean looksLikeLegacyServicePlan(JsonNode plan) {
        JsonNode slides = plan.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            return false;
        }
        JsonNode first = slides.get(0);
        return first.has("outputOrder") || first.has("templateSlideIndex") || first.has("slotMappings");
    }

    private static void requireBoundedList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw new PptJobStateException(field + " must be an array");
        }
        if (node.size() > MAX_LIST_ITEMS) {
            throw new PptJobStateException(field + " exceeds item limit");
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                boundString(item, field, MAX_LIST_ITEM_CHARS);
            }
        }
    }

    private static int requirePositiveInt(JsonNode node, String field) {
        if (node == null || !node.canConvertToInt() || node.asInt() <= 0) {
            throw new PptJobStateException("fill plan " + field + " must be a positive integer");
        }
        return node.asInt();
    }

    private static int requireNonNegativeInt(JsonNode node, String field) {
        if (node == null || !node.canConvertToInt() || node.asInt() < 0) {
            throw new PptJobStateException(field + " must be a non-negative integer");
        }
        return node.asInt();
    }

    private static String boundString(JsonNode node, String field, int maxChars) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new PptJobStateException(field + " must be a string");
        }
        String value = node.asText();
        if (value.length() > maxChars) {
            throw new PptJobStateException(field + " exceeds length limit");
        }
        return value;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
