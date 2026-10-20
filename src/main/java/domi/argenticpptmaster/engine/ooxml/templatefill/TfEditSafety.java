// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Classify template-fill chart and table edits before package mutation. */
public final class TfEditSafety {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> XY_PLOTS = Set.of("bubbleChart", "scatterChart");

    private TfEditSafety() {}

    public static ObjectNode unsupportedChartCapability(String code, String message) {
        return unsupportedChartCapability(code, message, null, 0, "unknown");
    }

    public static ObjectNode unsupportedChartCapability(
            String code, String message, String plotType, int plotCount, String dataModel) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("supported", false);
        node.put("code", code);
        node.put("message", message);
        if (plotType == null) {
            node.putNull("plot_type");
        } else {
            node.put("plot_type", plotType);
        }
        node.put("plot_count", plotCount);
        node.put("data_model", dataModel);
        return node;
    }

    public static ObjectNode chartEditCapability(Element chartRoot) {
        String rootNs = chartRoot.getNamespaceURI() == null ? "" : chartRoot.getNamespaceURI();
        if (rootNs.toLowerCase().contains("chartex")) {
            return unsupportedChartCapability(
                    "chart_edit_chartex_unsupported", "template-fill chart edits do not support ChartEx");
        }
        if (!TfConstants.CHART_NS.equals(rootNs)) {
            return unsupportedChartCapability(
                    "chart_edit_plot_type_unsupported",
                    "template-fill chart edits require a classic DrawingML chart part");
        }
        Element plotArea = TfXml.firstDescendant(chartRoot, "plotArea");
        if (plotArea == null) {
            return unsupportedChartCapability(
                    "chart_edit_plot_type_unsupported",
                    "template-fill chart edits require a classic chart plotArea");
        }
        List<Element> plotNodes = new ArrayList<>();
        for (org.w3c.dom.Node node = plotArea.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) {
                continue;
            }
            String ns = child.getNamespaceURI() == null ? "" : child.getNamespaceURI();
            String local = child.getLocalName();
            if (TfConstants.CHART_NS.equals(ns) && local != null && local.endsWith("Chart")) {
                plotNodes.add(child);
            }
        }
        if (plotNodes.size() > 1) {
            return unsupportedChartCapability(
                    "chart_edit_multi_plot_unsupported",
                    "template-fill chart edits do not support multi-plot or combination charts",
                    null,
                    plotNodes.size(),
                    "unknown");
        }
        if (plotNodes.isEmpty()) {
            return unsupportedChartCapability(
                    "chart_edit_plot_type_unsupported",
                    "template-fill chart edits require exactly one recognized chart plot");
        }
        Element plot = plotNodes.get(0);
        String plotType = plot.getLocalName();
        if ("scatterChart".equals(plotType)) {
            return unsupportedChartCapability(
                    "chart_edit_scatter_unsupported",
                    "template-fill chart edits do not support scatter xVal/yVal data",
                    plotType,
                    1,
                    "xy");
        }
        if ("bubbleChart".equals(plotType)) {
            return unsupportedChartCapability(
                    "chart_edit_bubble_unsupported",
                    "template-fill chart edits do not support bubble xVal/yVal/bubbleSize data",
                    plotType,
                    1,
                    "bubble");
        }
        List<Element> seriesNodes = TfXml.childrenByLocalName(plot, "ser");
        if (seriesNodes.isEmpty()) {
            return unsupportedChartCapability(
                    "chart_edit_no_series",
                    "template-fill chart edits require at least one editable series",
                    plotType,
                    1,
                    "category");
        }
        for (Element series : seriesNodes) {
            if (TfXml.firstChild(series, "xVal") != null
                    || TfXml.firstChild(series, "yVal") != null
                    || TfXml.firstChild(series, "bubbleSize") != null) {
                return unsupportedChartCapability(
                        "chart_edit_data_model_unsupported",
                        "template-fill chart edits require c:cat/c:val series",
                        plotType,
                        1,
                        "unknown");
            }
            if (TfXml.firstChild(series, "cat") == null || TfXml.firstChild(series, "val") == null) {
                return unsupportedChartCapability(
                        "chart_edit_data_model_unsupported",
                        "template-fill chart edits require c:cat/c:val on every series",
                        plotType,
                        1,
                        "unknown");
            }
        }
        ArrayNode warnings = MAPPER.createArrayNode();
        if (TfXml.firstDescendant(plotArea, "dateAx") != null) {
            ObjectNode warning = MAPPER.createObjectNode();
            warning.put("code", "chart_edit_date_axis_flattened");
            warning.put(
                    "message",
                    "template-fill will flatten date-axis categories to the replacement single-level category cache");
            warnings.add(warning);
        }
        for (Element series : seriesNodes) {
            Element cat = TfXml.firstChild(series, "cat");
            if (cat != null && TfXml.firstDescendant(cat, "multiLvlStrRef") != null) {
                ObjectNode warning = MAPPER.createObjectNode();
                warning.put("code", "chart_edit_multilevel_categories_flattened");
                warning.put(
                        "message",
                        "template-fill will flatten multi-level categories to the replacement single-level category cache");
                warnings.add(warning);
                break;
            }
        }
        ObjectNode capability = MAPPER.createObjectNode();
        capability.put("supported", true);
        capability.put("code", "chart_edit_category_single_plot");
        capability.put("message", "single classic plot uses c:cat/c:val series");
        capability.put("plot_type", plotType);
        capability.put("plot_count", 1);
        capability.put("data_model", "category");
        capability.set("warnings", warnings);
        return capability;
    }

    public static boolean isVerifiedCategoryCapability(JsonNode capability) {
        return capability != null
                && capability.path("supported").asBoolean(false)
                && "chart_edit_category_single_plot".equals(capability.path("code").asText())
                && "category".equals(capability.path("data_model").asText())
                && capability.path("plot_count").asInt(0) == 1
                && capability.hasNonNull("plot_type")
                && !capability.path("plot_type").asText().isBlank()
                && !XY_PLOTS.contains(capability.path("plot_type").asText());
    }

    public static ObjectNode requireSupportedChartEdit(Element chartRoot) {
        ObjectNode capability = chartEditCapability(chartRoot);
        if (!isVerifiedCategoryCapability(capability)) {
            String code = capability.path("code").asText("chart_edit_capability_unknown");
            String message = capability.path("message").asText("chart edit capability is unknown");
            throw new IllegalStateException(message + " [" + code + "]");
        }
        return capability;
    }

    public static Map<String, Object> tableCellMergeInfo(Element cell) {
        boolean hMerge = ooxmlBool(cell.getAttribute("hMerge"));
        boolean vMerge = ooxmlBool(cell.getAttribute("vMerge"));
        int rowSpan = positiveSpan(cell.getAttribute("rowSpan"));
        int colSpan = positiveSpan(cell.getAttribute("gridSpan"));
        boolean isMergeSlave = hMerge || vMerge;
        boolean isMergeAnchor = !isMergeSlave && (rowSpan > 1 || colSpan > 1);
        String mergeRole = isMergeSlave ? "slave" : isMergeAnchor ? "anchor" : "none";
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("merge_role", mergeRole);
        info.put("is_merge_anchor", isMergeAnchor);
        info.put("is_merge_slave", isMergeSlave);
        info.put("row_span", rowSpan);
        info.put("col_span", colSpan);
        info.put("h_merge", hMerge);
        info.put("v_merge", vMerge);
        return info;
    }

    public static Map<String, Object> tableMergeTopology(Element table) {
        List<int[]> cellStates = new ArrayList<>();
        List<Map<String, Integer>> anchors = new ArrayList<>();
        List<Map<String, Object>> cellInfos = new ArrayList<>();
        List<Element> rows = TfXml.childrenByLocalName(table, "tr");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Element> cells = TfXml.childrenByLocalName(rows.get(rowIndex), "tc");
            for (int colIndex = 0; colIndex < cells.size(); colIndex++) {
                Map<String, Object> info = tableCellMergeInfo(cells.get(colIndex));
                cellStates.add(new int[] {rowIndex, colIndex});
                cellInfos.add(info);
                if (Boolean.TRUE.equals(info.get("is_merge_anchor"))) {
                    Map<String, Integer> anchor = new LinkedHashMap<>();
                    anchor.put("row", rowIndex);
                    anchor.put("col", colIndex);
                    anchor.put("row_span", (Integer) info.get("row_span"));
                    anchor.put("col_span", (Integer) info.get("col_span"));
                    anchors.add(anchor);
                }
            }
        }
        Map<String, Map<String, Integer>> coveredBy = new LinkedHashMap<>();
        for (Map<String, Integer> anchor : anchors) {
            for (int rowIndex = anchor.get("row"); rowIndex < anchor.get("row") + anchor.get("row_span"); rowIndex++) {
                for (int colIndex = anchor.get("col");
                        colIndex < anchor.get("col") + anchor.get("col_span");
                        colIndex++) {
                    if (rowIndex == anchor.get("row") && colIndex == anchor.get("col")) {
                        continue;
                    }
                    coveredBy.putIfAbsent(
                            rowIndex + "," + colIndex, Map.of("row", anchor.get("row"), "col", anchor.get("col")));
                }
            }
        }
        List<Map<String, Object>> slaveCells = new ArrayList<>();
        for (int i = 0; i < cellStates.size(); i++) {
            Map<String, Object> info = cellInfos.get(i);
            if (!Boolean.TRUE.equals(info.get("is_merge_slave"))) {
                continue;
            }
            int rowIndex = cellStates.get(i)[0];
            int colIndex = cellStates.get(i)[1];
            Map<String, Object> slave = new LinkedHashMap<>();
            slave.put("row", rowIndex);
            slave.put("col", colIndex);
            Map<String, Integer> anchor = coveredBy.get(rowIndex + "," + colIndex);
            if (anchor != null) {
                slave.put("anchor", anchor);
            }
            slaveCells.add(slave);
        }
        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("has_merges", !anchors.isEmpty() || !slaveCells.isEmpty());
        topology.put("anchors", anchors);
        topology.put("slave_cells", slaveCells);
        return topology;
    }

    private static boolean ooxmlBool(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.strip().toLowerCase();
        return "1".equals(normalized) || "on".equals(normalized) || "true".equals(normalized);
    }

    private static int positiveSpan(String value) {
        try {
            return Math.max(Integer.parseInt(value == null || value.isBlank() ? "1" : value), 1);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
