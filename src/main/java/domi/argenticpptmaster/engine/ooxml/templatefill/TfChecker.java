// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Check planned text/table/chart edits against slide-library capacity. */
public final class TfChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REUSE_WARN_THRESHOLD = 3;

    private TfChecker() {}

    public static ObjectNode checkPlan(JsonNode library, JsonNode plan) {
        Map<String, JsonNode> lookup = slotLookup(library);
        Map<String, JsonNode> tableLookup = tableLookup(library);
        Map<String, JsonNode> chartLookup = chartLookup(library);
        ArrayNode results = MAPPER.createArrayNode();
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("ok", 0);
        summary.put("warn", 0);
        summary.put("error", 0);

        JsonNode slides = plan.path("slides");
        int slideIndex = 0;
        for (JsonNode slide : slides) {
            slideIndex++;
            int sourceSlide = slide.path("source_slide").asInt(0);
            JsonNode transition = slide.get("transition");
            if (transition != null && transition.isObject()) {
                List<String> unknown = TfTransitions.transitionUnknownFields(transition);
                if (!unknown.isEmpty()) {
                    addResult(results, summary, "ERROR", "transition_unknown_fields", slideIndex, sourceSlide,
                            "transition has unknown field(s): " + String.join(", ", unknown));
                }
            }
            JsonNode replacements = slide.path("replacements");
            if (!replacements.isMissingNode() && !replacements.isArray()) {
                addResult(results, summary, "ERROR", "replacements_not_list", slideIndex, sourceSlide,
                        "replacements must be a list");
            } else if (replacements.isArray()) {
                for (JsonNode replacement : replacements) {
                    checkReplacement(lookup, results, summary, slideIndex, sourceSlide, replacement);
                }
            }
            JsonNode tableEdits = slide.path("table_edits");
            if (!tableEdits.isMissingNode() && !tableEdits.isArray()) {
                addResult(results, summary, "ERROR", "table_edits_not_list", slideIndex, sourceSlide,
                        "table_edits must be a list");
            } else if (tableEdits.isArray()) {
                for (JsonNode tableEdit : tableEdits) {
                    checkTableEdit(tableLookup, results, summary, slideIndex, sourceSlide, tableEdit);
                }
            }
            JsonNode chartEdits = slide.path("chart_edits");
            if (!chartEdits.isMissingNode() && !chartEdits.isArray()) {
                addResult(results, summary, "ERROR", "chart_edits_not_list", slideIndex, sourceSlide,
                        "chart_edits must be a list");
            } else if (chartEdits.isArray()) {
                for (JsonNode chartEdit : chartEdits) {
                    checkChartEdit(chartLookup, results, summary, slideIndex, sourceSlide, chartEdit);
                }
            }
        }

        Map<Integer, JsonNode> libSlides = librarySlideIndex(library);
        slideIndex = 0;
        for (JsonNode slide : slides) {
            slideIndex++;
            int sourceSlide = slide.path("source_slide").asInt(0);
            JsonNode libSlide = libSlides.get(sourceSlide);
            if (libSlide == null) {
                continue;
            }
            boolean hasTables = libSlide.path("tables").isArray() && !libSlide.path("tables").isEmpty();
            boolean hasCharts = libSlide.path("charts").isArray() && !libSlide.path("charts").isEmpty();
            boolean hasDiagrams = libSlide.path("diagrams").isArray() && !libSlide.path("diagrams").isEmpty();
            if (!hasTables && !hasCharts && !hasDiagrams) {
                continue;
            }
            List<String> uncovered = new ArrayList<>();
            if (hasTables && (!slide.path("table_edits").isArray() || slide.path("table_edits").isEmpty())) {
                uncovered.add("table");
            }
            if (hasCharts && (!slide.path("chart_edits").isArray() || slide.path("chart_edits").isEmpty())) {
                uncovered.add("chart");
            }
            if (hasDiagrams) {
                uncovered.add("smartart");
            }
            if (uncovered.isEmpty()) {
                continue;
            }
            String guidance = hasDiagrams
                    ? "SmartArt remains unchanged, so pick another source slide or accept this warning"
                    : "add table_edits/chart_edits, or pick another source slide";
            if (hasDiagrams && uncovered.stream().anyMatch(k -> k.equals("table") || k.equals("chart"))) {
                guidance = "add table/chart edits where supported; " + guidance;
            }
            ObjectNode item = MAPPER.createObjectNode();
            item.put("status", "WARN");
            item.put("code", "non_text_content_unedited");
            item.put("plan_slide", slideIndex);
            item.put("source_slide", sourceSlide);
            ArrayNode kinds = item.putArray("uncovered_kinds");
            uncovered.forEach(kinds::add);
            item.put(
                    "message",
                    "source slide " + sourceSlide + " has non-text content (" + String.join("/", uncovered)
                            + ") outside the plan's supported edits; template-fill leaves it untouched "
                            + "and original template content may show through (" + guidance + ")");
            results.add(item);
            summary.put("warn", summary.get("warn").asInt() + 1);
        }

        Map<Integer, Integer> sourceUseCounts = new HashMap<>();
        for (JsonNode slide : slides) {
            int src = slide.path("source_slide").asInt(0);
            if (src > 0) {
                sourceUseCounts.merge(src, 1, Integer::sum);
            }
        }
        Set<Integer> allLib = new HashSet<>(libSlides.keySet());
        Set<Integer> used = new HashSet<>(sourceUseCounts.keySet());
        List<Integer> unused = new ArrayList<>(allLib);
        unused.removeAll(used);
        unused.sort(Integer::compareTo);
        if (!unused.isEmpty()) {
            for (Map.Entry<Integer, Integer> entry : sourceUseCounts.entrySet()) {
                if (entry.getValue() >= REUSE_WARN_THRESHOLD) {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("status", "WARN");
                    item.put("code", "source_reuse_concentration");
                    item.put("source_slide", entry.getKey());
                    item.put("reuse_count", entry.getValue());
                    ArrayNode unusedNode = item.putArray("unused_source_slides");
                    unused.forEach(unusedNode::add);
                    item.put(
                            "message",
                            "source slide " + entry.getKey() + " is reused " + entry.getValue()
                                    + " times while " + unused.size()
                                    + " source layout(s) are never used (indices: "
                                    + unused.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                                    + "); consider using other layouts for more variety");
                    results.add(item);
                    summary.put("warn", summary.get("warn").asInt() + 1);
                }
            }
        }

        ObjectNode report = MAPPER.createObjectNode();
        report.put("schema", TfConstants.CHECK_SCHEMA);
        report.set("summary", summary);
        report.set("results", results);
        return report;
    }

    private static void checkReplacement(
            Map<String, JsonNode> lookup,
            ArrayNode results,
            ObjectNode summary,
            int slideIndex,
            int sourceSlide,
            JsonNode replacement) {
        List<String> selectors = TfSelectors.replacementSelectors(replacement);
        JsonNode slot = null;
        for (String selector : selectors) {
            slot = lookup.get(sourceSlide + "|" + selector);
            if (slot != null) {
                break;
            }
        }
        String text = TfSelectors.replacementText(replacement);
        if (slot == null) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("status", "ERROR");
            item.put("code", "replacement_target_not_found");
            item.put("plan_slide", slideIndex);
            item.put("source_slide", sourceSlide);
            item.put("selector", selectors.isEmpty() ? "" : selectors.get(0));
            item.put("message", "replacement target not found in slide library");
            results.add(item);
            summary.put("error", summary.get("error").asInt() + 1);
            return;
        }
        String oldText = slot.path("text").asText("");
        double oldWidth = visualWidth(oldText);
        double newWidth = visualWidth(text);
        int oldParagraphs = slot.path("paragraph_count").asInt(1);
        int newParagraphs = Math.max((int) text.lines().filter(line -> !line.isBlank()).count(), 1);
        String role = slot.path("role").asText("");
        String[] fit = fitStatus(role, oldWidth, newWidth, oldParagraphs, newParagraphs, slot.path("geometry"), slot.path("text_metrics"));
        ObjectNode item = MAPPER.createObjectNode();
        item.put("status", fit[0]);
        item.put("code", "WARN".equals(fit[0]) ? "text_capacity" : "text_fit");
        item.put("plan_slide", slideIndex);
        item.put("source_slide", sourceSlide);
        item.put("slot_id", slot.path("slot_id").asText());
        item.put("role", role);
        putDisplayWidth(item, "old_len", oldWidth);
        putDisplayWidth(item, "new_len", newWidth);
        putDisplayWidth(item, "old_visual_width", oldWidth);
        putDisplayWidth(item, "new_visual_width", newWidth);
        Double capacity = capacityForReport(role, oldWidth, oldParagraphs, newParagraphs, slot.path("geometry"), slot.path("text_metrics"));
        if (capacity == null) {
            item.putNull("capacity_visual_width");
        } else {
            putDisplayWidth(item, "capacity_visual_width", capacity);
        }
        item.put("ratio", Math.round(newWidth / Math.max(oldWidth, 1.0) * 100.0) / 100.0);
        item.put("old_paragraphs", oldParagraphs);
        item.put("new_paragraphs", newParagraphs);
        item.put("message", fit[1]);
        item.put("old_text", oldText);
        item.put("new_text", text);
        results.add(item);
        if ("WARN".equals(fit[0])) {
            summary.put("warn", summary.get("warn").asInt() + 1);
        } else {
            summary.put("ok", summary.get("ok").asInt() + 1);
        }
    }

    private static void checkTableEdit(
            Map<String, JsonNode> tableLookup,
            ArrayNode results,
            ObjectNode summary,
            int slideIndex,
            int sourceSlide,
            JsonNode tableEdit) {
        List<String> selectors = TfSelectors.tableSelectors(tableEdit);
        JsonNode table = null;
        for (String selector : selectors) {
            table = tableLookup.get(sourceSlide + "|" + selector);
            if (table != null) {
                break;
            }
        }
        if (table == null) {
            addResult(results, summary, "ERROR", "table_target_not_found", slideIndex, sourceSlide,
                    "table target not found in slide library");
            return;
        }
        JsonNode cells = tableEdit.path("cells");
        if (!cells.isArray()) {
            addResult(results, summary, "ERROR", "table_cells_not_list", slideIndex, sourceSlide,
                    "table edit cells must be a list");
            return;
        }
        int rowCount = table.path("row_count").asInt(0);
        int columnCount = table.path("column_count").asInt(0);
        Set<String> mergeSlaves = new HashSet<>();
        JsonNode topology = table.path("merge_topology");
        if (topology.isObject()) {
            for (JsonNode slave : topology.path("slave_cells")) {
                mergeSlaves.add(slave.path("row").asInt() + "," + slave.path("col").asInt());
            }
        }
        for (JsonNode cell : cells) {
            int row = cell.path("row").asInt(-1);
            int col = cell.path("col").asInt(-1);
            if (row < 0 || col < 0 || row >= rowCount || col >= columnCount) {
                addResult(results, summary, "ERROR", "table_cell_out_of_bounds", slideIndex, sourceSlide,
                        "table cell out of bounds: row=" + row + " col=" + col);
                continue;
            }
            if (mergeSlaves.contains(row + "," + col)) {
                addResult(results, summary, "ERROR", "table_cell_is_merge_slave", slideIndex, sourceSlide,
                        "table cell row=" + row + " col=" + col + " is a merged-cell slave");
                continue;
            }
            ObjectNode item = MAPPER.createObjectNode();
            item.put("status", "OK");
            item.put("code", "table_target_exists");
            item.put("plan_slide", slideIndex);
            item.put("source_slide", sourceSlide);
            item.put("table_id", table.path("table_id").asText());
            item.put("row", row);
            item.put("col", col);
            item.put("message", "table cell target exists");
            results.add(item);
            summary.put("ok", summary.get("ok").asInt() + 1);
        }
    }

    private static void checkChartEdit(
            Map<String, JsonNode> chartLookup,
            ArrayNode results,
            ObjectNode summary,
            int slideIndex,
            int sourceSlide,
            JsonNode chartEdit) {
        List<String> selectors = TfSelectors.chartSelectors(chartEdit);
        JsonNode chart = null;
        for (String selector : selectors) {
            chart = chartLookup.get(sourceSlide + "|" + selector);
            if (chart != null) {
                break;
            }
        }
        if (chart == null) {
            addResult(results, summary, "ERROR", "chart_target_not_found", slideIndex, sourceSlide,
                    "chart target not found in slide library");
            return;
        }
        JsonNode capability = chart.get("edit_capability");
        if (capability == null || !capability.isObject()) {
            addResult(results, summary, "ERROR", "chart_edit_capability_unknown", slideIndex, sourceSlide,
                    "chart edit capability is missing; runtime will inspect the actual chart XML before mutation, and re-running template analysis is recommended");
            return;
        }
        if (!capability.path("supported").asBoolean(false)) {
            addResult(results, summary, "ERROR",
                    capability.path("code").asText("chart_edit_capability_unknown"),
                    slideIndex, sourceSlide,
                    capability.path("message").asText("chart edit capability is unsupported"));
            return;
        }
        if (!TfEditSafety.isVerifiedCategoryCapability(capability)) {
            addResult(results, summary, "ERROR", "chart_edit_capability_unknown", slideIndex, sourceSlide,
                    "chart edit capability is not the verified single-plot c:cat/c:val model");
            return;
        }
        for (JsonNode warning : capability.path("warnings")) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("status", "WARN");
            item.put("code", warning.path("code").asText("chart_edit_category_flattened"));
            item.put("plan_slide", slideIndex);
            item.put("source_slide", sourceSlide);
            item.put("chart_id", chart.path("chart_id").asText());
            item.put("message", warning.path("message").asText("chart categories will be flattened"));
            results.add(item);
            summary.put("warn", summary.get("warn").asInt() + 1);
        }
        JsonNode categories = chartEdit.path("categories");
        JsonNode series = chartEdit.path("series");
        if (!categories.isArray() || !series.isArray() || series.isEmpty()) {
            addResult(results, summary, "ERROR", "chart_data_invalid", slideIndex, sourceSlide,
                    "chart edit requires categories list and non-empty series list");
            return;
        }
        for (JsonNode item : series) {
            if (!item.isObject() || !item.path("values").isArray() || item.path("values").size() != categories.size()) {
                addResult(results, summary, "ERROR", "chart_series_length_mismatch", slideIndex, sourceSlide,
                        "each chart series needs values matching categories length");
                return;
            }
        }
        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("status", "OK");
        ok.put("code", "chart_target_valid");
        ok.put("plan_slide", slideIndex);
        ok.put("source_slide", sourceSlide);
        ok.put("chart_id", chart.path("chart_id").asText());
        ok.put("category_count", categories.size());
        ok.put("series_count", series.size());
        ok.put("message", "chart edit target and data shape are valid");
        results.add(ok);
        summary.put("ok", summary.get("ok").asInt() + 1);
    }

    private static String[] fitStatus(
            String role,
            double oldWidth,
            double newWidth,
            int oldParagraphs,
            int newParagraphs,
            JsonNode geometry,
            JsonNode textMetrics) {
        oldWidth = Math.max(oldWidth, 1.0);
        double ratio = newWidth / oldWidth;
        Integer width = geometry.has("width") && !geometry.get("width").isNull() ? geometry.get("width").asInt() : null;
        Integer height = geometry.has("height") && !geometry.get("height").isNull() ? geometry.get("height").asInt() : null;
        Double capacityWidth = geometryCapacityWidth(role, oldParagraphs, newParagraphs, geometry, textMetrics);
        if ("label_candidate".equals(role) || (oldWidth <= 8 && oldParagraphs <= 1)) {
            if (capacityWidth != null && newWidth <= capacityWidth && !(oldWidth <= 8)) {
                return new String[] {"OK", "short label fits estimated text-box capacity"};
            }
            double labelLimit = oldWidth;
            if (width != null && width >= 220) {
                labelLimit = Math.max(labelLimit, oldWidth * 1.25);
            }
            if (newWidth > labelLimit) {
                return new String[] {"WARN", "short label exceeds original visual width; rewrite shorter"};
            }
            return new String[] {"OK", "short label fits original visual width"};
        }
        if ("title_candidate".equals(role) && oldParagraphs <= 1) {
            if (capacityWidth != null && newWidth <= capacityWidth) {
                return new String[] {"OK", "title fits estimated text-box capacity"};
            }
            double limit = oldWidth <= 12 ? 1.15 : 1.35;
            if (ratio > limit) {
                return new String[] {"WARN", "title is too long for the original slot; rewrite first"};
            }
            return new String[] {"OK", "title stays near original capacity"};
        }
        int paragraphLimit = Math.max(Math.max(oldParagraphs + 2, oldParagraphs * 2), 2);
        if (newParagraphs > paragraphLimit) {
            return new String[] {"WARN", "body paragraph count changed too much; compress or split pages"};
        }
        if (width != null && height != null && width * height < 30000 && ratio > 2.0) {
            return new String[] {"WARN", "small text box with much longer text; rewrite shorter"};
        }
        if (capacityWidth != null && newWidth > capacityWidth) {
            return new String[] {"WARN", "text exceeds estimated text-box capacity; rewrite or split"};
        }
        double bodyLimit = "body_candidate".equals(role) ? 3.0 : 2.2;
        if (ratio > bodyLimit) {
            return new String[] {"WARN", "text is much longer than source slot; rewrite or choose another page"};
        }
        return new String[] {"OK", "within estimated slot capacity"};
    }

    private static Double geometryCapacityWidth(
            String role, int oldParagraphs, int newParagraphs, JsonNode geometry, JsonNode textMetrics) {
        if (!geometry.has("width") || geometry.get("width").isNull()
                || !geometry.has("height") || geometry.get("height").isNull()) {
            return null;
        }
        int width = geometry.get("width").asInt();
        int height = geometry.get("height").asInt();
        if (width <= 0 || height <= 0) {
            return null;
        }
        double fontSizePx = textMetrics.path("font_size_px").asDouble(0);
        if (fontSizePx <= 0) {
            fontSizePx = fallbackFontSizePx(role, height, oldParagraphs);
        }
        double lineHeight = Math.max(fontSizePx * 1.25, 1.0);
        int maxLines = Math.max(Math.max((int) (height / lineHeight), oldParagraphs), Math.max(newParagraphs, 1));
        double horizontalPadding = width >= 180 ? 24 : 12;
        double usableWidth = Math.max(Math.max(width - horizontalPadding, width * 0.72), 1);
        double latinUnitsPerLine = usableWidth / Math.max(fontSizePx * 0.52, 1);
        double capacity = latinUnitsPerLine * maxLines;
        if ("label_candidate".equals(role)) {
            return capacity * 0.7;
        }
        if ("title_candidate".equals(role)) {
            return capacity * 0.85;
        }
        return capacity;
    }

    private static double fallbackFontSizePx(String role, int height, int oldParagraphs) {
        if (oldParagraphs > 0) {
            double inferred = height / (double) Math.max(oldParagraphs, 1) / 1.25;
            if (inferred >= 8 && inferred <= 56) {
                return inferred;
            }
        }
        if ("title_candidate".equals(role)) {
            return 28.0;
        }
        if ("body_candidate".equals(role)) {
            return 16.0;
        }
        return 14.0;
    }

    private static Double capacityForReport(
            String role, double oldWidth, int oldParagraphs, int newParagraphs, JsonNode geometry, JsonNode textMetrics) {
        Double capacity = geometryCapacityWidth(role, oldParagraphs, newParagraphs, geometry, textMetrics);
        if (capacity == null) {
            return null;
        }
        return Math.max(capacity, oldWidth);
    }

    private static double visualWidth(String text) {
        double width = 0.0;
        for (int cp : text.replaceAll("\\s+", "").codePoints().toArray()) {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                width += 2.0;
            } else {
                width += 1.0;
            }
        }
        return width;
    }

    private static void putDisplayWidth(ObjectNode item, String field, double value) {
        if (Math.rint(value) == value) {
            item.put(field, (int) value);
        } else {
            item.put(field, Math.round(value * 10.0) / 10.0);
        }
    }

    private static Map<String, JsonNode> slotLookup(JsonNode library) {
        Map<String, JsonNode> lookup = new HashMap<>();
        for (JsonNode slide : library.path("slides")) {
            int slideIndex = slide.path("slide_index").asInt(0);
            for (JsonNode slot : slide.path("slots")) {
                if (slot.hasNonNull("slot_id")) {
                    lookup.put(slideIndex + "|slot_id:" + slot.get("slot_id").asText(), slot);
                }
                if (slot.hasNonNull("shape_id")) {
                    lookup.put(slideIndex + "|shape_id:" + slot.get("shape_id").asText(), slot);
                }
                if (slot.hasNonNull("shape_name")) {
                    lookup.put(slideIndex + "|shape_name:" + slot.get("shape_name").asText(), slot);
                }
            }
        }
        return lookup;
    }

    private static Map<String, JsonNode> tableLookup(JsonNode library) {
        Map<String, JsonNode> lookup = new HashMap<>();
        for (JsonNode slide : library.path("slides")) {
            int slideIndex = slide.path("slide_index").asInt(0);
            for (JsonNode table : slide.path("tables")) {
                if (table.hasNonNull("table_id")) {
                    lookup.put(slideIndex + "|table_id:" + table.get("table_id").asText(), table);
                }
                if (table.hasNonNull("shape_id")) {
                    lookup.put(slideIndex + "|shape_id:" + table.get("shape_id").asText(), table);
                }
                if (table.hasNonNull("shape_name")) {
                    lookup.put(slideIndex + "|shape_name:" + table.get("shape_name").asText(), table);
                }
            }
        }
        return lookup;
    }

    private static Map<String, JsonNode> chartLookup(JsonNode library) {
        Map<String, JsonNode> lookup = new HashMap<>();
        for (JsonNode slide : library.path("slides")) {
            int slideIndex = slide.path("slide_index").asInt(0);
            for (JsonNode chart : slide.path("charts")) {
                if (chart.hasNonNull("chart_id")) {
                    lookup.put(slideIndex + "|chart_id:" + chart.get("chart_id").asText(), chart);
                }
                if (chart.hasNonNull("shape_id")) {
                    lookup.put(slideIndex + "|shape_id:" + chart.get("shape_id").asText(), chart);
                }
                if (chart.hasNonNull("shape_name")) {
                    lookup.put(slideIndex + "|shape_name:" + chart.get("shape_name").asText(), chart);
                }
            }
        }
        return lookup;
    }

    private static Map<Integer, JsonNode> librarySlideIndex(JsonNode library) {
        Map<Integer, JsonNode> map = new HashMap<>();
        for (JsonNode slide : library.path("slides")) {
            map.put(slide.path("slide_index").asInt(0), slide);
        }
        return map;
    }

    private static void addResult(
            ArrayNode results,
            ObjectNode summary,
            String status,
            String code,
            int planSlide,
            int sourceSlide,
            String message) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("status", status);
        item.put("code", code);
        item.put("plan_slide", planSlide);
        item.put("source_slide", sourceSlide);
        item.put("message", message);
        results.add(item);
        String key = "ERROR".equals(status) ? "error" : "WARN".equals(status) ? "warn" : "ok";
        summary.put(key, summary.get(key).asInt() + 1);
    }
}
