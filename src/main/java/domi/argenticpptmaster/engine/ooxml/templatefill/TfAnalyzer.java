// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Analyze a PPTX as reusable text, table, chart, and SmartArt source facts. */
public final class TfAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] THANKS = {
        "thank", "thanks", "q&a", "qa", "contact", "致谢", "谢谢", "感谢", "答疑", "联系方式"
    };
    private static final String[] TOC = {"agenda", "contents", "content", "outline", "目录", "议程"};
    private static final String[] CHAPTER = {"chapter", "part", "section", "章节", "部分"};

    private TfAnalyzer() {}

    public static ObjectNode analyzePptx(Path pptxPath) throws Exception {
        Path extractDir = Files.createTempDirectory("java-tf-analyze-");
        try {
            PptxPackage.extract(pptxPath, extractDir);
            Map<String, byte[]> entries = readEntries(extractDir);
            Document presentation = TfXml.parse(entries.get("ppt/presentation.xml"));
            List<SlidePartRef> slideRefs = SlideParts.read(extractDir);
            ArrayNode slides = MAPPER.createArrayNode();
            for (SlidePartRef slideRef : slideRefs) {
                Document slideDoc = TfXml.parse(entries.get(slideRef.partName()));
                Element slideRoot = slideDoc.getDocumentElement();
                ArrayNode slots = MAPPER.createArrayNode();
                int order = 0;
                for (Element container : TfShapes.textContainers(slideRoot)) {
                    order++;
                    TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
                    List<String> paragraphs = TfShapes.paragraphTexts(container);
                    String text = String.join("\n", paragraphs);
                    Map<String, Integer> geometry = TfShapes.containerGeometry(container);
                    String role = slotRole(text, identity.name(), geometry, TfXml.descendants(container, "t").size(), order);
                    ObjectNode slot = MAPPER.createObjectNode();
                    slot.put("slot_id", "s%02d_sh%s".formatted(slideRef.index(), identity.id()));
                    slot.put("role", role);
                    slot.put("text", text);
                    slot.put("paragraph_count", paragraphs.size());
                    slot.set("geometry", geometryNode(geometry));
                    slot.set("text_metrics", textMetrics(container, paragraphs.size()));
                    slots.add(slot);
                }
                ArrayNode tables = analyzeTables(slideRoot, slideRef.index());
                ArrayNode charts = analyzeCharts(entries, slideRoot, slideRef);
                ArrayNode diagrams = TfDiagramRead.readSmartArtDiagrams(entries, slideRef.partName(), slideRef.index());
                StringBuilder slideText = new StringBuilder();
                for (var slot : slots) {
                    String t = slot.path("text").asText("");
                    if (!t.isBlank()) {
                        if (slideText.length() > 0) {
                            slideText.append('\n');
                        }
                        slideText.append(t);
                    }
                }
                ObjectNode slide = MAPPER.createObjectNode();
                slide.put("slide_index", slideRef.index());
                slide.put(
                        "page_type",
                        classifyPageType(slideRef.index(), slideRefs.size(), slideText.toString(), slots.size()));
                slide.put("text_summary", slideText.substring(0, Math.min(500, slideText.length())));
                slide.set("slots", slots);
                slide.set("tables", tables);
                slide.set("charts", charts);
                slide.set("diagrams", diagrams);
                ObjectNode risk = fillRisk(tables, charts, diagrams);
                if (risk != null) {
                    slide.set("fill_risk", risk);
                }
                slides.add(slide);
            }
            ObjectNode library = MAPPER.createObjectNode();
            library.put("schema", TfConstants.LIBRARY_SCHEMA);
            library.put("source_pptx", pptxPath.toAbsolutePath().normalize().toString());
            library.put("slide_count", slides.size());
            library.set("canvas_px", canvasPx(presentation.getDocumentElement()));
            library.set("slides", slides);
            library.set("plan_contract", defaultPlanContract());
            return library;
        } finally {
            deleteRecursively(extractDir);
        }
    }

    private static ArrayNode analyzeTables(Element slideRoot, int sourceSlide) {
        ArrayNode tables = MAPPER.createArrayNode();
        int order = 0;
        for (Element container : TfShapes.tableContainers(slideRoot)) {
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
            Element table = TfXml.firstDescendant(container, "tbl");
            if (table == null) {
                continue;
            }
            Map<String, Object> mergeTopology = TfEditSafety.tableMergeTopology(table);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> slaveCells =
                    (List<Map<String, Object>>) mergeTopology.getOrDefault("slave_cells", List.of());
            Map<String, Map<String, Integer>> mergeAnchors = new java.util.HashMap<>();
            for (Map<String, Object> item : slaveCells) {
                Object anchor = item.get("anchor");
                if (anchor instanceof Map<?, ?> map) {
                    mergeAnchors.put(
                            item.get("row") + "," + item.get("col"),
                            Map.of("row", ((Number) map.get("row")).intValue(), "col", ((Number) map.get("col")).intValue()));
                }
            }
            ArrayNode rows = MAPPER.createArrayNode();
            int maxColumns = 0;
            List<Element> trs = TfXml.childrenByLocalName(table, "tr");
            for (int rowIndex = 0; rowIndex < trs.size(); rowIndex++) {
                ArrayNode cells = MAPPER.createArrayNode();
                List<Element> tcs = TfXml.childrenByLocalName(trs.get(rowIndex), "tc");
                for (int colIndex = 0; colIndex < tcs.size(); colIndex++) {
                    Element cell = tcs.get(colIndex);
                    Map<String, Object> mergeInfo = TfEditSafety.tableCellMergeInfo(cell);
                    ObjectNode cellNode = MAPPER.createObjectNode();
                    cellNode.put("row", rowIndex);
                    cellNode.put("col", colIndex);
                    cellNode.put("text", String.join("\n", TfShapes.paragraphTexts(cell)));
                    cellNode.put("merge_role", String.valueOf(mergeInfo.get("merge_role")));
                    cellNode.put("is_merge_anchor", Boolean.TRUE.equals(mergeInfo.get("is_merge_anchor")));
                    cellNode.put("is_merge_slave", Boolean.TRUE.equals(mergeInfo.get("is_merge_slave")));
                    cellNode.put("row_span", (Integer) mergeInfo.get("row_span"));
                    cellNode.put("col_span", (Integer) mergeInfo.get("col_span"));
                    cellNode.put("h_merge", Boolean.TRUE.equals(mergeInfo.get("h_merge")));
                    cellNode.put("v_merge", Boolean.TRUE.equals(mergeInfo.get("v_merge")));
                    Map<String, Integer> mergeAnchor = mergeAnchors.get(rowIndex + "," + colIndex);
                    if (mergeAnchor != null) {
                        cellNode.set("merge_anchor", MAPPER.valueToTree(mergeAnchor));
                        cellNode.put("anchor_row", mergeAnchor.get("row"));
                        cellNode.put("anchor_col", mergeAnchor.get("col"));
                    } else if (Boolean.TRUE.equals(mergeInfo.get("is_merge_slave"))) {
                        cellNode.putNull("anchor_row");
                        cellNode.putNull("anchor_col");
                    } else {
                        cellNode.put("anchor_row", rowIndex);
                        cellNode.put("anchor_col", colIndex);
                    }
                    cells.add(cellNode);
                }
                maxColumns = Math.max(maxColumns, cells.size());
                ObjectNode rowNode = MAPPER.createObjectNode();
                rowNode.put("row", rowIndex);
                rowNode.set("cells", cells);
                rows.add(rowNode);
            }
            ObjectNode tableNode = MAPPER.createObjectNode();
            tableNode.put("table_id", "s%02d_tbl%s".formatted(sourceSlide, identity.id()));
            tableNode.put("row_count", rows.size());
            tableNode.put("column_count", maxColumns);
            tableNode.set("rows", rows);
            tableNode.set("merge_topology", MAPPER.valueToTree(mergeTopology));
            tables.add(tableNode);
        }
        return tables;
    }

    private static ArrayNode analyzeCharts(
            Map<String, byte[]> entries, Element slideRoot, SlidePartRef slideRef) throws Exception {
        ArrayNode charts = MAPPER.createArrayNode();
        Map<String, Map<String, String>> relationships = Map.of();
        byte[] rels = entries.get(TfXml.relsNameForPart(slideRef.partName()));
        if (rels != null) {
            relationships = TfXml.parseRelationships(rels);
        }
        int order = 0;
        for (Element container : TfShapes.chartFrames(slideRoot)) {
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
            String[] ref = TfShapes.chartReference(container);
            ObjectNode payload = TfChartRead.emptyChartData();
            payload.put("chart_id", "s%02d_ch%s".formatted(slideRef.index(), identity.id()));
            payload.put("chart_kind", ref[0]);
            if ("chartex".equals(ref[0])) {
                payload.put("chart_type", "chartEx");
                payload.putArray("plot_types").add("chartEx");
                payload.set(
                        "edit_capability",
                        TfEditSafety.unsupportedChartCapability(
                                "chart_edit_chartex_unsupported",
                                "template-fill chart edits do not support ChartEx"));
                charts.add(payload);
                continue;
            }
            if (!"classic".equals(ref[0])) {
                payload.set(
                        "edit_capability",
                        TfEditSafety.unsupportedChartCapability(
                                "chart_edit_plot_type_unsupported",
                                "template-fill chart edits require a classic DrawingML chart reference"));
                charts.add(payload);
                continue;
            }
            Map<String, String> rel = relationships.get(ref[1]);
            if (rel != null && TfConstants.CHART_REL_TYPE.equals(rel.get("type"))) {
                String chartPart = TfXml.normalizePart(rel.get("target"), slideRef.partName());
                try {
                    Document chartDoc = TfXml.parse(entries.get(chartPart));
                    payload.setAll(TfChartRead.readChartData(chartDoc.getDocumentElement()));
                    payload.put("chart_id", "s%02d_ch%s".formatted(slideRef.index(), identity.id()));
                    payload.put("chart_kind", ref[0]);
                    payload.set("edit_capability", TfEditSafety.chartEditCapability(chartDoc.getDocumentElement()));
                } catch (Exception ex) {
                    payload.setAll(TfChartRead.emptyChartData());
                    payload.put("chart_id", "s%02d_ch%s".formatted(slideRef.index(), identity.id()));
                    payload.put("chart_kind", ref[0]);
                    payload.set(
                            "edit_capability",
                            TfEditSafety.unsupportedChartCapability(
                                    "chart_edit_part_unavailable",
                                    "template-fill could not read the classic chart part"));
                }
            } else {
                payload.set(
                        "edit_capability",
                        TfEditSafety.unsupportedChartCapability(
                                "chart_edit_relationship_unsupported",
                                "template-fill chart edits require a classic chart relationship"));
            }
            charts.add(payload);
        }
        return charts;
    }

    private static String slotRole(
            String text, String shapeName, Map<String, Integer> geometry, int textNodeCount, int order) {
        String name = shapeName == null ? "" : shapeName.toLowerCase(Locale.ROOT);
        Integer y = geometry.get("y");
        if (order == 1 || name.contains("title") || name.contains("标题")) {
            return "title_candidate";
        }
        if (y != null && y < 160 && text.length() <= 80) {
            return "title_candidate";
        }
        if (textNodeCount >= 4 || text.length() >= 120) {
            return "body_candidate";
        }
        return "label_candidate";
    }

    private static ObjectNode textMetrics(Element container, int paragraphCount) {
        Double fontSize = fontSizePx(container);
        ObjectNode metrics = MAPPER.createObjectNode();
        if (fontSize == null) {
            metrics.putNull("font_size_px");
        } else {
            metrics.put("font_size_px", fontSize);
        }
        metrics.put("paragraph_count", paragraphCount);
        return metrics;
    }

    private static Double fontSizePx(Element container) {
        List<Double> sizes = new ArrayList<>();
        for (Element node : TfXml.descendants(container, "rPr")) {
            addSize(sizes, node.getAttribute("sz"));
        }
        for (Element node : TfXml.descendants(container, "defRPr")) {
            addSize(sizes, node.getAttribute("sz"));
        }
        if (sizes.isEmpty()) {
            return null;
        }
        return Math.round(sizes.stream().mapToDouble(Double::doubleValue).max().orElse(0) * 100.0) / 100.0;
    }

    private static void addSize(List<Double> sizes, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            sizes.add(Integer.parseInt(raw) / 100.0 * 96.0 / 72.0);
        } catch (NumberFormatException ignored) {
        }
    }

    private static String classifyPageType(int index, int total, String text, int slotCount) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (index == 1) {
            return "cover_candidate";
        }
        if (index == total || containsAny(normalized, THANKS)) {
            return "ending_candidate";
        }
        if (containsAny(normalized, TOC)) {
            return "toc_candidate";
        }
        if (containsAny(normalized, CHAPTER)) {
            return "chapter_candidate";
        }
        if (slotCount <= 2 && text.length() <= 80) {
            return "chapter_candidate";
        }
        return "content_candidate";
    }

    private static boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode canvasPx(Element presentationRoot) {
        Element size = TfXml.firstChild(presentationRoot, "sldSz");
        ObjectNode canvas = MAPPER.createObjectNode();
        if (size == null) {
            canvas.putNull("width");
            canvas.putNull("height");
            return canvas;
        }
        Integer width = TfXml.emuToPx(size.getAttribute("cx"));
        Integer height = TfXml.emuToPx(size.getAttribute("cy"));
        if (width == null) {
            canvas.putNull("width");
        } else {
            canvas.put("width", width);
        }
        if (height == null) {
            canvas.putNull("height");
        } else {
            canvas.put("height", height);
        }
        return canvas;
    }

    private static ObjectNode fillRisk(ArrayNode tables, ArrayNode charts, ArrayNode diagrams) {
        List<String> kinds = new ArrayList<>();
        if (!tables.isEmpty()) {
            kinds.add("table");
        }
        if (!charts.isEmpty()) {
            kinds.add("chart");
        }
        if (!diagrams.isEmpty()) {
            kinds.add("smartart");
        }
        if (kinds.isEmpty()) {
            return null;
        }
        List<String> guidance = new ArrayList<>();
        if (!tables.isEmpty() || !charts.isEmpty()) {
            guidance.add("cover tables/charts with explicit edits");
        }
        if (!diagrams.isEmpty()) {
            guidance.add("review preserved SmartArt source text");
        }
        ObjectNode risk = MAPPER.createObjectNode();
        risk.put("has_non_text_content", true);
        ArrayNode kindsNode = risk.putArray("kinds");
        kinds.forEach(kindsNode::add);
        risk.put(
                "reason",
                "has non-text content ("
                        + String.join("/", kinds)
                        + ") that text-fill does not replace automatically; "
                        + String.join("; ", guidance));
        return risk;
    }

    private static ObjectNode geometryNode(Map<String, Integer> geometry) {
        ObjectNode node = MAPPER.createObjectNode();
        for (String key : List.of("x", "y", "width", "height")) {
            Integer value = geometry.get(key);
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
        }
        return node;
    }

    private static ObjectNode defaultPlanContract() {
        return MAPPER.createObjectNode()
                .put("schema", TfConstants.PLAN_SCHEMA)
                .set(
                        "slides",
                        MAPPER.createArrayNode()
                                .add(MAPPER.createObjectNode()
                                        .put("source_slide", 1)
                                        .put("purpose", "封面 / 章节 / 内容 / 结尾")));
    }

    private static Map<String, byte[]> readEntries(Path extractDir) throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(extractDir)) {
            Path root = extractDir.toAbsolutePath().normalize();
            walk.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                try {
                    entries.put(name, Files.readAllBytes(path));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        return entries;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
