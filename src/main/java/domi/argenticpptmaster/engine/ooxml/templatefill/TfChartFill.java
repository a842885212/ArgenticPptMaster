// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Edit native PowerPoint chart data on cloned slides. */
public final class TfChartFill {

    private TfChartFill() {}

    public static Map<String, Map<String, String>> chartKeyMaps(Element slideRoot, int sourceSlide) {
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        int order = 0;
        for (Element container : TfShapes.chartFrames(slideRoot)) {
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
            String[] ref = TfShapes.chartReference(container);
            Map<String, String> info = new LinkedHashMap<>();
            info.put("shape_id", identity.id());
            info.put("shape_name", identity.name());
            info.put("rel_id", ref[1]);
            info.put("chart_kind", ref[0]);
            maps.put("chart_id:s%02d_ch%s".formatted(sourceSlide, identity.id()), info);
            maps.put("shape_id:" + identity.id(), info);
            if (identity.name() != null && !identity.name().isBlank()) {
                maps.put("shape_name:" + identity.name(), info);
            }
        }
        return maps;
    }

    public static int[] applyChartEdits(
            Element slideRoot,
            Element relsRoot,
            Map<String, byte[]> entries,
            Element contentRoot,
            int sourceSlide,
            String newSlidePart,
            JsonNode chartEdits,
            int nextChartNumber,
            int nextEmbeddingNumber) throws Exception {
        if (chartEdits == null || !chartEdits.isArray()) {
            throw new IllegalArgumentException("Slide " + sourceSlide + " chart_edits must be a list");
        }
        Map<String, Map<String, String>> maps = chartKeyMaps(slideRoot, sourceSlide);
        Map<String, String> clonedByRelId = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (JsonNode chartEdit : chartEdits) {
            List<String> selectors = TfSelectors.chartSelectors(chartEdit);
            Map<String, String> chartInfo = null;
            for (String key : selectors) {
                if (maps.containsKey(key)) {
                    chartInfo = maps.get(key);
                    break;
                }
            }
            if (chartInfo == null) {
                if (chartEdit.path("optional").asBoolean(false)) {
                    continue;
                }
                errors.add(selectors.isEmpty() ? "<missing selector>" : String.join(", ", selectors));
                continue;
            }
            String chartKind = chartInfo.getOrDefault("chart_kind", "");
            if (!"classic".equals(chartKind)) {
                String code = "chartex".equals(chartKind)
                        ? "chart_edit_chartex_unsupported"
                        : "chart_edit_plot_type_unsupported";
                throw new IllegalStateException(
                        "template-fill chart edits require a supported classic chart [" + code + "]");
            }
            String relId = chartInfo.getOrDefault("rel_id", "");
            Element rel = TfPackage.findRelationship(relsRoot, relId);
            if (rel == null) {
                errors.add((selectors.isEmpty() ? "<chart>" : selectors.get(0)) + " relationship=" + relId);
                continue;
            }
            if (!clonedByRelId.containsKey(relId)) {
                nextChartNumber++;
                String sourceChartPart = chartPartFromRelationship(newSlidePart, rel);
                String newChartPart = "ppt/charts/chart" + nextChartNumber + ".xml";
                nextEmbeddingNumber = cloneAndUpdateChartPart(
                        entries,
                        contentRoot,
                        sourceChartPart,
                        newChartPart,
                        chartEdit,
                        nextEmbeddingNumber);
                rel.setAttribute("Target", TfXml.relativeTarget(newSlidePart, newChartPart));
                clonedByRelId.put(relId, newChartPart);
            } else {
                Document chartDoc = TfXml.parse(entries.get(clonedByRelId.get(relId)));
                applyChartEditToChartXml(chartDoc.getDocumentElement(), chartEdit);
                entries.put(clonedByRelId.get(relId), TfXml.serialize(chartDoc));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Missing chart edit target(s) on slide " + sourceSlide + ": " + String.join("; ", errors));
        }
        return new int[] {nextChartNumber, nextEmbeddingNumber};
    }

    private static String chartPartFromRelationship(String slidePart, Element rel) {
        String target = rel.getAttribute("Target");
        if (!TfConstants.CHART_REL_TYPE.equals(rel.getAttribute("Type")) || target.isBlank()) {
            throw new IllegalStateException("Matched chart shape does not point to a chart relationship");
        }
        return TfXml.normalizePart(target, slidePart);
    }

    private static int cloneAndUpdateChartPart(
            Map<String, byte[]> entries,
            Element contentRoot,
            String sourceChartPart,
            String newChartPart,
            JsonNode chartEdit,
            int nextEmbeddingNumber) throws Exception {
        if (!entries.containsKey(sourceChartPart)) {
            throw new IllegalStateException("Missing chart part: " + sourceChartPart);
        }
        Document chartDoc = TfXml.parse(entries.get(sourceChartPart));
        applyChartEditToChartXml(chartDoc.getDocumentElement(), chartEdit);
        entries.put(newChartPart, TfXml.serialize(chartDoc));
        TfPackage.addContentTypeOverride(contentRoot, newChartPart, TfConstants.CHART_CONTENT_TYPE);

        String sourceChartRels = TfXml.relsNameForPart(sourceChartPart);
        if (!entries.containsKey(sourceChartRels)) {
            return nextEmbeddingNumber;
        }
        String newChartRels = TfXml.relsNameForPart(newChartPart);
        Document chartRelsDoc = TfXml.parse(entries.get(sourceChartRels));
        Element workbookRel = findChartWorkbookRel(chartRelsDoc.getDocumentElement());
        if (workbookRel != null) {
            String workbookTarget = workbookRel.getAttribute("Target");
            String workbookPart = TfXml.normalizePart(workbookTarget, sourceChartPart);
            if (entries.containsKey(workbookPart)) {
                nextEmbeddingNumber++;
                String newWorkbookPart = "ppt/embeddings/templateFillChart" + nextEmbeddingNumber + ".xlsx";
                entries.put(newWorkbookPart, rewriteChartWorkbook(entries.get(workbookPart), chartEdit));
                workbookRel.setAttribute("Target", TfXml.relativeTarget(newChartPart, newWorkbookPart));
                TfPackage.addContentTypeOverride(contentRoot, newWorkbookPart, TfConstants.XLSX_CONTENT_TYPE);
            }
        }
        entries.put(newChartRels, TfXml.serialize(chartRelsDoc));
        return nextEmbeddingNumber;
    }

    private static Element findChartWorkbookRel(Element chartRelsRoot) {
        for (Element rel : TfXml.descendants(chartRelsRoot, "Relationship")) {
            String target = rel.getAttribute("Target");
            if (TfConstants.PACKAGE_REL_TYPE.equals(rel.getAttribute("Type"))
                    || target.toLowerCase().endsWith(".xlsx")) {
                return rel;
            }
        }
        return null;
    }

    static void applyChartEditToChartXml(Element chartRoot, JsonNode chartEdit) throws Exception {
        TfEditSafety.requireSupportedChartEdit(chartRoot);
        List<String> categories = new ArrayList<>();
        for (JsonNode item : chartEdit.path("categories")) {
            categories.add(item.asText());
        }
        JsonNode seriesPayload = chartEdit.path("series");
        if (categories.isEmpty() || !seriesPayload.isArray() || seriesPayload.isEmpty()) {
            throw new IllegalStateException("Chart edit requires non-empty categories and series");
        }
        Element chartType = chartTypeWithSeries(chartRoot);
        List<Element> seriesNodes = new ArrayList<>(TfXml.childrenByLocalName(chartType, "ser"));
        if (seriesNodes.isEmpty()) {
            throw new IllegalStateException("Chart XML has no editable series");
        }
        Element templateSeries = seriesNodes.get(seriesNodes.size() - 1);
        while (seriesNodes.size() < seriesPayload.size()) {
            Node clone = templateSeries.cloneNode(true);
            chartType.appendChild(clone);
            seriesNodes.add((Element) clone);
        }
        while (seriesNodes.size() > seriesPayload.size()) {
            Element extra = seriesNodes.remove(seriesNodes.size() - 1);
            chartType.removeChild(extra);
        }
        for (int index = 0; index < seriesPayload.size(); index++) {
            JsonNode payload = seriesPayload.get(index);
            JsonNode valuesNode = payload.path("values");
            if (valuesNode.size() != categories.size()) {
                throw new IllegalStateException("Chart series values must match categories length");
            }
            Element series = seriesNodes.get(index);
            setValAttr(ensureChild(series, "idx"), index);
            setValAttr(ensureChild(series, "order"), index);
            setSeriesName(series, payload.path("name").asText("系列" + (index + 1)), index + 2);
            setCategoryCache(series, categories);
            List<Object> values = new ArrayList<>();
            for (JsonNode value : valuesNode) {
                if (value.isNumber()) {
                    values.add(value.numberValue());
                } else {
                    values.add(value.asText());
                }
            }
            setValueCache(series, values, index + 2);
        }
    }

    private static Element chartTypeWithSeries(Element chartRoot) {
        Element plotArea = TfXml.firstDescendant(chartRoot, "plotArea");
        if (plotArea == null) {
            throw new IllegalStateException("Chart XML has no plotArea");
        }
        List<Element> chartTypes = new ArrayList<>();
        for (Node node = plotArea.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) {
                continue;
            }
            String local = child.getLocalName();
            if (local != null && local.endsWith("Chart") && !TfXml.childrenByLocalName(child, "ser").isEmpty()) {
                chartTypes.add(child);
            }
        }
        if (chartTypes.size() > 1) {
            throw new IllegalStateException(
                    "template-fill chart edits do not support multi-plot / combination charts; "
                            + "use beautify/main pipeline to redraw the chart, or leave the native chart untouched");
        }
        if (!chartTypes.isEmpty()) {
            return chartTypes.get(0);
        }
        throw new IllegalStateException("Chart XML has no editable series");
    }

    private static Element ensureChild(Element parent, String localName) {
        Element child = TfXml.firstChild(parent, localName);
        if (child != null) {
            return child;
        }
        child = parent.getOwnerDocument().createElementNS(TfConstants.CHART_NS, "c:" + localName);
        parent.appendChild(child);
        return child;
    }

    private static void setValAttr(Element element, Object value) {
        element.setAttribute("val", String.valueOf(value));
    }

    private static String excelCol(int index) {
        StringBuilder result = new StringBuilder();
        int i = index;
        while (i > 0) {
            i--;
            result.insert(0, (char) ('A' + (i % 26)));
            i /= 26;
        }
        return result.length() == 0 ? "A" : result.toString();
    }

    private static void writeStringCache(Element cache, List<String> values) {
        while (cache.hasChildNodes()) {
            cache.removeChild(cache.getFirstChild());
        }
        Document doc = cache.getOwnerDocument();
        Element ptCount = doc.createElementNS(TfConstants.CHART_NS, "c:ptCount");
        setValAttr(ptCount, values.size());
        cache.appendChild(ptCount);
        for (int index = 0; index < values.size(); index++) {
            Element pt = doc.createElementNS(TfConstants.CHART_NS, "c:pt");
            pt.setAttribute("idx", String.valueOf(index));
            Element v = doc.createElementNS(TfConstants.CHART_NS, "c:v");
            v.setTextContent(values.get(index));
            pt.appendChild(v);
            cache.appendChild(pt);
        }
    }

    private static void writeNumberCache(Element cache, List<Object> values) {
        while (cache.hasChildNodes()) {
            cache.removeChild(cache.getFirstChild());
        }
        Document doc = cache.getOwnerDocument();
        Element fmt = doc.createElementNS(TfConstants.CHART_NS, "c:formatCode");
        fmt.setTextContent("General");
        cache.appendChild(fmt);
        Element ptCount = doc.createElementNS(TfConstants.CHART_NS, "c:ptCount");
        setValAttr(ptCount, values.size());
        cache.appendChild(ptCount);
        for (int index = 0; index < values.size(); index++) {
            Element pt = doc.createElementNS(TfConstants.CHART_NS, "c:pt");
            pt.setAttribute("idx", String.valueOf(index));
            Element v = doc.createElementNS(TfConstants.CHART_NS, "c:v");
            v.setTextContent(String.valueOf(values.get(index)));
            pt.appendChild(v);
            cache.appendChild(pt);
        }
    }

    private static void setSeriesName(Element series, String name, int columnIndex) {
        Element tx = ensureChild(series, "tx");
        while (tx.hasChildNodes()) {
            tx.removeChild(tx.getFirstChild());
        }
        Document doc = series.getOwnerDocument();
        Element strRef = doc.createElementNS(TfConstants.CHART_NS, "c:strRef");
        Element formula = doc.createElementNS(TfConstants.CHART_NS, "c:f");
        formula.setTextContent("Sheet1!$" + excelCol(columnIndex) + "$1");
        Element cache = doc.createElementNS(TfConstants.CHART_NS, "c:strCache");
        writeStringCache(cache, List.of(name));
        strRef.appendChild(formula);
        strRef.appendChild(cache);
        tx.appendChild(strRef);
    }

    private static void setCategoryCache(Element series, List<String> categories) {
        Element cat = ensureChild(series, "cat");
        while (cat.hasChildNodes()) {
            cat.removeChild(cat.getFirstChild());
        }
        Document doc = series.getOwnerDocument();
        Element strRef = doc.createElementNS(TfConstants.CHART_NS, "c:strRef");
        Element formula = doc.createElementNS(TfConstants.CHART_NS, "c:f");
        formula.setTextContent("Sheet1!$A$2:$A$" + (categories.size() + 1));
        Element cache = doc.createElementNS(TfConstants.CHART_NS, "c:strCache");
        writeStringCache(cache, categories);
        strRef.appendChild(formula);
        strRef.appendChild(cache);
        cat.appendChild(strRef);
    }

    private static void setValueCache(Element series, List<Object> values, int columnIndex) {
        Element val = ensureChild(series, "val");
        while (val.hasChildNodes()) {
            val.removeChild(val.getFirstChild());
        }
        Document doc = series.getOwnerDocument();
        Element numRef = doc.createElementNS(TfConstants.CHART_NS, "c:numRef");
        Element formula = doc.createElementNS(TfConstants.CHART_NS, "c:f");
        formula.setTextContent(
                "Sheet1!$" + excelCol(columnIndex) + "$2:$" + excelCol(columnIndex) + "$" + (values.size() + 1));
        Element cache = doc.createElementNS(TfConstants.CHART_NS, "c:numCache");
        writeNumberCache(cache, values);
        numRef.appendChild(formula);
        numRef.appendChild(cache);
        val.appendChild(numRef);
    }

    private static byte[] rewriteChartWorkbook(byte[] xlsxBytes, JsonNode chartEdit) throws Exception {
        Map<String, byte[]> xlsxEntries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    xlsxEntries.put(entry.getName(), zin.readAllBytes());
                }
            }
        }
        String sheetPart = firstWorkbookSheet(xlsxEntries);
        if (sheetPart == null || !xlsxEntries.containsKey(sheetPart)) {
            return xlsxBytes;
        }
        Document sheetDoc = TfXml.parse(xlsxEntries.get(sheetPart));
        Element sheetRoot = sheetDoc.getDocumentElement();
        Element sheetData = TfXml.firstDescendant(sheetRoot, "sheetData");
        if (sheetData == null) {
            sheetData = sheetDoc.createElementNS(TfConstants.SPREADSHEET_NS, "sheetData");
            sheetRoot.appendChild(sheetData);
        }
        while (sheetData.hasChildNodes()) {
            sheetData.removeChild(sheetData.getFirstChild());
        }
        List<String> categories = new ArrayList<>();
        for (JsonNode item : chartEdit.path("categories")) {
            categories.add(item.asText());
        }
        JsonNode seriesPayload = chartEdit.path("series");
        List<List<Object>> rows = new ArrayList<>();
        List<Object> header = new ArrayList<>();
        header.add("Category");
        for (int idx = 0; idx < seriesPayload.size(); idx++) {
            header.add(seriesPayload.get(idx).path("name").asText("系列" + (idx + 1)));
        }
        rows.add(header);
        for (int rowIndex = 0; rowIndex < categories.size(); rowIndex++) {
            List<Object> row = new ArrayList<>();
            row.add(categories.get(rowIndex));
            for (JsonNode series : seriesPayload) {
                JsonNode values = series.path("values");
                JsonNode value = values.get(rowIndex);
                if (value != null && value.isNumber()) {
                    row.add(value.numberValue());
                } else {
                    row.add(value == null ? "" : value.asText());
                }
            }
            rows.add(row);
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Element row = sheetDoc.createElementNS(TfConstants.SPREADSHEET_NS, "row");
            row.setAttribute("r", String.valueOf(rowIndex + 1));
            List<Object> values = rows.get(rowIndex);
            for (int colIndex = 0; colIndex < values.size(); colIndex++) {
                row.appendChild(spreadsheetCell(sheetDoc, values.get(colIndex), rowIndex + 1, colIndex + 1));
            }
            sheetData.appendChild(row);
        }
        xlsxEntries.put(sheetPart, TfXml.serialize(sheetDoc));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : xlsxEntries.entrySet()) {
                zout.putNextEntry(new ZipEntry(entry.getKey()));
                zout.write(entry.getValue());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String firstWorkbookSheet(Map<String, byte[]> xlsxEntries) throws Exception {
        byte[] workbook = xlsxEntries.get("xl/workbook.xml");
        if (workbook == null) {
            return null;
        }
        Document root = TfXml.parse(workbook);
        Element sheets = TfXml.firstDescendant(root.getDocumentElement(), "sheets");
        if (sheets == null || !sheets.hasChildNodes()) {
            return null;
        }
        Element first = null;
        for (Node node = sheets.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el && "sheet".equals(el.getLocalName())) {
                first = el;
                break;
            }
        }
        if (first == null) {
            return null;
        }
        String relId = TfXml.attrNs(first, OoxmlConstants.OFFICE_REL_NS, "id");
        if (relId == null || relId.isBlank()) {
            return null;
        }
        Map<String, Map<String, String>> rels =
                TfXml.parseRelationships(xlsxEntries.get(TfXml.relsNameForPart("xl/workbook.xml")));
        Map<String, String> rel = rels.get(relId);
        return rel == null ? null : TfXml.normalizePart(rel.get("target"), "xl/workbook.xml");
    }

    private static Element spreadsheetCell(Document doc, Object value, int row, int col) {
        Element cell = doc.createElementNS(TfConstants.SPREADSHEET_NS, "c");
        cell.setAttribute("r", excelCol(col) + row);
        if (value instanceof Number && !(value instanceof Boolean)) {
            Element v = doc.createElementNS(TfConstants.SPREADSHEET_NS, "v");
            v.setTextContent(String.valueOf(value));
            cell.appendChild(v);
            return cell;
        }
        cell.setAttribute("t", "inlineStr");
        Element inline = doc.createElementNS(TfConstants.SPREADSHEET_NS, "is");
        Element text = doc.createElementNS(TfConstants.SPREADSHEET_NS, "t");
        text.setTextContent(String.valueOf(value));
        inline.appendChild(text);
        cell.appendChild(inline);
        return cell;
    }
}
