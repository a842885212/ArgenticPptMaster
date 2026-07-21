package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.TemplateFillCapabilityIndex;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Loads and validates the native capability index from slide_library.json. */
@Component
public class TemplateFillCapabilityIndexLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TemplateFillCapabilityIndex load(Path slideLibraryPath) {
        if (!Files.isRegularFile(slideLibraryPath)) {
            throw analysisFailed("slide library is missing");
        }
        try {
            JsonNode root = MAPPER.readTree(slideLibraryPath.toFile());
            String schema = textOrNull(root.get("schema"));
            if (schema == null || schema.isBlank()) {
                throw analysisFailed("slide library schema version is missing");
            }
            JsonNode slides = root.get("slides");
            if (slides == null || !slides.isArray()) {
                throw analysisFailed("slide library slides array is missing");
            }
            Map<Integer, TemplateFillCapabilityIndex.SlideCapability> byIndex = new LinkedHashMap<>();
            Set<String> globalSlotIds = new HashSet<>();
            Set<String> globalTableIds = new HashSet<>();
            Set<String> globalChartIds = new HashSet<>();
            for (JsonNode slide : slides) {
                JsonNode indexNode = slide.get("slide_index");
                if (indexNode == null || !indexNode.canConvertToInt() || indexNode.asInt() <= 0) {
                    throw analysisFailed("slide is missing stable slide_index");
                }
                int slideIndex = indexNode.asInt();
                if (byIndex.containsKey(slideIndex)) {
                    throw analysisFailed("duplicate slide_index: " + slideIndex);
                }
                Map<String, TemplateFillCapabilityIndex.TextSlotCapability> textSlots = new LinkedHashMap<>();
                for (JsonNode slot : iterable(slide.get("slots"))) {
                    String slotId = textOrNull(slot.get("slot_id"));
                    if (slotId == null || slotId.isBlank()) {
                        throw analysisFailed("text slot is missing stable slot_id on slide " + slideIndex);
                    }
                    if (!globalSlotIds.add(slotId) || textSlots.containsKey(slotId)) {
                        throw analysisFailed("duplicate slot_id: " + slotId);
                    }
                    Double fontSize = null;
                    JsonNode metrics = slot.get("text_metrics");
                    if (metrics != null && metrics.has("font_size_px") && metrics.get("font_size_px").isNumber()) {
                        fontSize = metrics.get("font_size_px").asDouble();
                    }
                    // Upstream apply cannot mutate font size; capability flag stays false.
                    textSlots.put(slotId, new TemplateFillCapabilityIndex.TextSlotCapability(
                            slotId,
                            textOrNull(slot.get("role")),
                            null,
                            fontSize,
                            false));
                }
                Map<String, TemplateFillCapabilityIndex.TableCapability> tables = new LinkedHashMap<>();
                for (JsonNode table : iterable(slide.get("tables"))) {
                    String tableId = textOrNull(table.get("table_id"));
                    if (tableId == null || tableId.isBlank()) {
                        throw analysisFailed("table is missing stable table_id on slide " + slideIndex);
                    }
                    if (!globalTableIds.add(tableId) || tables.containsKey(tableId)) {
                        throw analysisFailed("duplicate table_id: " + tableId);
                    }
                    tables.put(tableId, new TemplateFillCapabilityIndex.TableCapability(
                            tableId,
                            table.path("row_count").asInt(0),
                            table.path("column_count").asInt(0)));
                }
                Map<String, TemplateFillCapabilityIndex.ChartCapability> charts = new LinkedHashMap<>();
                for (JsonNode chart : iterable(slide.get("charts"))) {
                    String chartId = textOrNull(chart.get("chart_id"));
                    if (chartId == null || chartId.isBlank()) {
                        throw analysisFailed("chart is missing stable chart_id on slide " + slideIndex);
                    }
                    if (!globalChartIds.add(chartId) || charts.containsKey(chartId)) {
                        throw analysisFailed("duplicate chart_id: " + chartId);
                    }
                    List<String> categories = new ArrayList<>();
                    for (JsonNode category : iterable(chart.get("categories"))) {
                        categories.add(category.asText());
                    }
                    charts.put(chartId, new TemplateFillCapabilityIndex.ChartCapability(
                            chartId,
                            textOrNull(chart.get("chart_type")),
                            categories.size(),
                            chart.path("series").isArray() ? chart.path("series").size() : 0,
                            categories));
                }
                byIndex.put(slideIndex, new TemplateFillCapabilityIndex.SlideCapability(
                        slideIndex,
                        textOrNull(slide.get("page_type")),
                        textSlots,
                        tables,
                        charts));
            }
            int slideCount = root.has("slide_count") ? root.get("slide_count").asInt(byIndex.size()) : byIndex.size();
            return new TemplateFillCapabilityIndex(schema, slideCount, byIndex);
        } catch (PptTemplateFillExecutionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw analysisFailed("failed to read slide library");
        }
    }

    private static List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return values;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static PptTemplateFillExecutionException analysisFailed(String message) {
        return new PptTemplateFillExecutionException(
                "ANALYZE", message, TemplateFillErrorCode.TEMPLATE_ANALYSIS_FAILED);
    }
}
