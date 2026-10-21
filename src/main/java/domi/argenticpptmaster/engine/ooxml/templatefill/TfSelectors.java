// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Plan selector builders and text extraction shared by check-plan and apply. */
public final class TfSelectors {

    private TfSelectors() {}

    public static List<String> replacementSelectors(JsonNode replacement) {
        List<String> selectors = new ArrayList<>();
        if (replacement.hasNonNull("slot_id")) {
            selectors.add("slot_id:" + replacement.get("slot_id").asText());
        }
        if (replacement.hasNonNull("shape_id")) {
            selectors.add("shape_id:" + replacement.get("shape_id").asText());
        }
        if (replacement.hasNonNull("shape_name")) {
            selectors.add("shape_name:" + replacement.get("shape_name").asText());
        }
        return selectors;
    }

    public static List<String> tableSelectors(JsonNode tableEdit) {
        List<String> selectors = new ArrayList<>();
        if (tableEdit.hasNonNull("table_id")) {
            selectors.add("table_id:" + tableEdit.get("table_id").asText());
        }
        if (tableEdit.hasNonNull("shape_id")) {
            selectors.add("shape_id:" + tableEdit.get("shape_id").asText());
        }
        if (tableEdit.hasNonNull("shape_name")) {
            selectors.add("shape_name:" + tableEdit.get("shape_name").asText());
        }
        return selectors;
    }

    public static List<String> chartSelectors(JsonNode chartEdit) {
        List<String> selectors = new ArrayList<>();
        if (chartEdit.hasNonNull("chart_id")) {
            selectors.add("chart_id:" + chartEdit.get("chart_id").asText());
        }
        if (chartEdit.hasNonNull("shape_id")) {
            selectors.add("shape_id:" + chartEdit.get("shape_id").asText());
        }
        if (chartEdit.hasNonNull("shape_name")) {
            selectors.add("shape_name:" + chartEdit.get("shape_name").asText());
        }
        return selectors;
    }

    public static String replacementText(JsonNode replacement) {
        if (replacement.has("paragraphs") && replacement.get("paragraphs").isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : replacement.get("paragraphs")) {
                parts.add(item.asText(""));
            }
            return String.join("\n", parts);
        }
        return replacement.path("text").asText("");
    }

    public static String tableCellText(JsonNode cellEdit) {
        if (cellEdit.has("paragraphs") && cellEdit.get("paragraphs").isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : cellEdit.get("paragraphs")) {
                parts.add(item.asText(""));
            }
            return String.join("\n", parts);
        }
        return cellEdit.path("text").asText("");
    }
}
