// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.beautify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code beautify_inventory.build_inventory} (builder mode only — no summary/page views).
 */
public final class BeautifyInventoryBuilder {

    public static final String SCHEMA = "beautify_inventory.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BeautifyInventoryBuilder() {}

    public static ObjectNode buildInventory(JsonNode slideLibrary, JsonNode imageManifest) {
        Map<Integer, List<ObjectNode>> imagesBySlide = imagesBySlide(imageManifest);
        ArrayNode slidesOut = MAPPER.createArrayNode();
        for (JsonNode slide : iterable(slideLibrary.path("slides"))) {
            int idx = slide.path("slide_index").asInt();
            ArrayNode textBlocks = MAPPER.createArrayNode();
            for (JsonNode slot : iterable(slide.path("slots"))) {
                ObjectNode block = MAPPER.createObjectNode();
                putText(block, "slot_id", slot.path("slot_id"));
                putText(block, "role", slot.path("role"));
                block.put("text", slot.path("text").asText(""));
                if (slot.has("paragraph_count") && !slot.get("paragraph_count").isNull()) {
                    block.put("paragraph_count", slot.path("paragraph_count").asInt());
                } else {
                    block.putNull("paragraph_count");
                }
                block.set("geometry", slot.path("geometry").deepCopy());
                textBlocks.add(block);
            }

            ArrayNode tables = MAPPER.createArrayNode();
            for (JsonNode table : iterable(slide.path("tables"))) {
                ObjectNode item = MAPPER.createObjectNode();
                putText(item, "table_id", table.path("table_id"));
                item.put("row_count", table.path("row_count").asInt());
                item.put("column_count", table.path("column_count").asInt());
                item.set("cells", tableCells(table));
                item.set("rows", table.path("rows").deepCopy());
                tables.add(item);
            }

            ArrayNode charts = MAPPER.createArrayNode();
            for (JsonNode chart : iterable(slide.path("charts"))) {
                ObjectNode item = MAPPER.createObjectNode();
                putText(item, "chart_id", chart.path("chart_id"));
                putText(item, "chart_type", chart.path("chart_type"));
                item.put("category_count", chart.path("category_count").asInt());
                item.put("series_count", chart.path("series_count").asInt());
                item.set("categories", chart.path("categories").deepCopy());
                item.set("series", chart.path("series").deepCopy());
                charts.add(item);
            }

            ArrayNode diagrams = MAPPER.createArrayNode();
            for (JsonNode diagram : iterable(slide.path("diagrams"))) {
                ObjectNode item = MAPPER.createObjectNode();
                putText(item, "diagram_id", diagram.path("diagram_id"));
                putText(item, "shape_name", diagram.path("shape_name"));
                item.set("geometry", diagram.path("geometry").deepCopy());
                item.set("layout", diagram.path("layout").deepCopy());
                item.set("root_ids", diagram.path("root_ids").deepCopy());
                item.set("nodes", diagram.path("nodes").deepCopy());
                item.set("connections", diagram.path("connections").deepCopy());
                putText(item, "status", diagram.path("status"));
                item.set("warnings", diagram.path("warnings").deepCopy());
                diagrams.add(item);
            }

            ObjectNode slideOut = MAPPER.createObjectNode();
            slideOut.put("slide_index", idx);
            putText(slideOut, "page_type", slide.path("page_type"));
            slideOut.set("text_blocks", textBlocks);
            slideOut.set("tables", tables);
            slideOut.set("charts", charts);
            slideOut.set("diagrams", diagrams);
            ArrayNode images = MAPPER.createArrayNode();
            for (ObjectNode image : imagesBySlide.getOrDefault(idx, List.of())) {
                images.add(image);
            }
            slideOut.set("images", images);
            slideOut.set("ignored", MAPPER.createArrayNode());
            slideOut.set("needs_confirmation", MAPPER.createArrayNode());
            slidesOut.add(slideOut);
        }

        ObjectNode inventory = MAPPER.createObjectNode();
        inventory.put("schema", SCHEMA);
        if (slideLibrary.has("source_pptx") && !slideLibrary.get("source_pptx").isNull()) {
            inventory.put("source", slideLibrary.path("source_pptx").asText());
        } else {
            inventory.putNull("source");
        }
        inventory.put(
                "slide_count",
                slideLibrary.path("slide_count").asInt(slidesOut.size()));
        inventory.set("canvas_px", slideLibrary.path("canvas_px").deepCopy());
        inventory.set("slides", slidesOut);
        return inventory;
    }

    private static Map<Integer, List<ObjectNode>> imagesBySlide(JsonNode manifest) {
        Map<Integer, List<ObjectNode>> bySlide = new HashMap<>();
        if (manifest == null || !manifest.isArray()) {
            return bySlide;
        }
        for (JsonNode entry : manifest) {
            String filename = entry.path("filename").asText(null);
            for (JsonNode occ : iterable(entry.path("occurrences"))) {
                if (!occ.has("slide_index") || occ.get("slide_index").isNull()) {
                    continue;
                }
                int idx = occ.path("slide_index").asInt();
                ObjectNode image = MAPPER.createObjectNode();
                if (filename == null) {
                    image.putNull("filename");
                } else {
                    image.put("filename", filename);
                }
                putText(image, "shape_name", occ.path("shape_name"));
                putNullableNumber(image, "pixel_width", entry.path("pixel_width"));
                putNullableNumber(image, "pixel_height", entry.path("pixel_height"));
                putNullableNumber(image, "display_ratio", occ.path("display_ratio"));
                putNullableNumber(image, "display_left_emu", occ.path("display_left_emu"));
                putNullableNumber(image, "display_top_emu", occ.path("display_top_emu"));
                putNullableNumber(image, "display_width_emu", occ.path("display_width_emu"));
                putNullableNumber(image, "display_height_emu", occ.path("display_height_emu"));
                putNullableNumber(image, "usage_count", entry.path("usage_count"));
                bySlide.computeIfAbsent(idx, ignored -> new ArrayList<>()).add(image);
            }
        }
        return bySlide;
    }

    private static ArrayNode tableCells(JsonNode table) {
        ArrayNode grid = MAPPER.createArrayNode();
        for (JsonNode row : iterable(table.path("rows"))) {
            ArrayNode cells = MAPPER.createArrayNode();
            for (JsonNode cell : iterable(row.path("cells"))) {
                cells.add(cell.path("text").asText(""));
            }
            grid.add(cells);
        }
        return grid;
    }

    private static void putNullableNumber(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            target.putNull(field);
        } else if (value.isNumber()) {
            target.set(field, value.deepCopy());
        } else {
            target.putNull(field);
        }
    }

    private static void putText(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isTextual()) {
            target.putNull(field);
        } else {
            target.put(field, value.asText());
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return node;
    }
}
