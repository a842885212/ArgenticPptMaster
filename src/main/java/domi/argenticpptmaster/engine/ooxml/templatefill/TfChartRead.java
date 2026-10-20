// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/** Read native PowerPoint chart display caches for slide-library analysis. */
public final class TfChartRead {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TfChartRead() {}

    public static ObjectNode emptyChartData() {
        ObjectNode node = MAPPER.createObjectNode();
        node.putNull("chart_type");
        node.put("category_count", 0);
        node.put("series_count", 0);
        node.putArray("categories");
        node.putArray("series");
        return node;
    }

    public static ObjectNode readChartData(Element chartRoot) {
        List<Element> chartTypes = chartTypesWithSeries(chartRoot);
        if (chartTypes.isEmpty()) {
            return emptyChartData();
        }
        Element firstSeries = TfXml.firstChild(chartTypes.get(0), "ser");
        List<Object> categories =
                firstSeries == null ? List.of() : cacheValues(TfXml.firstChild(firstSeries, "cat"), false);
        ArrayNode seriesPayload = MAPPER.createArrayNode();
        List<String> plotTypes = new ArrayList<>();
        for (Element chartType : chartTypes) {
            String plotName = chartType.getLocalName();
            plotTypes.add(plotName);
            for (Element series : TfXml.childrenByLocalName(chartType, "ser")) {
                int index = seriesPayload.size() + 1;
                List<Object> seriesCategories = cacheValues(TfXml.firstChild(series, "cat"), false);
                if (categories.isEmpty() && !seriesCategories.isEmpty()) {
                    categories = seriesCategories;
                }
                ObjectNode seriesNode = MAPPER.createObjectNode();
                seriesNode.put("name", seriesName(series, "系列" + index));
                ArrayNode values = seriesNode.putArray("values");
                for (Object value : cacheValues(TfXml.firstChild(series, "val"), true)) {
                    if (value instanceof Integer i) {
                        values.add(i);
                    } else if (value instanceof Number n) {
                        values.add(n.doubleValue());
                    } else {
                        values.add(String.valueOf(value));
                    }
                }
                seriesNode.put("chart_type", plotName);
                seriesPayload.add(seriesNode);
            }
        }
        ObjectNode result = MAPPER.createObjectNode();
        boolean single = plotTypes.stream().distinct().count() == 1;
        result.put("chart_type", single ? plotTypes.get(0) : "comboChart");
        ArrayNode plots = result.putArray("plot_types");
        plotTypes.forEach(plots::add);
        result.put("category_count", categories.size());
        result.put("series_count", seriesPayload.size());
        ArrayNode cats = result.putArray("categories");
        for (Object category : categories) {
            cats.add(String.valueOf(category));
        }
        result.set("series", seriesPayload);
        return result;
    }

    private static List<Element> chartTypesWithSeries(Element chartRoot) {
        Element plotArea = TfXml.firstDescendant(chartRoot, "plotArea");
        if (plotArea == null) {
            return List.of();
        }
        List<Element> chartTypes = new ArrayList<>();
        for (org.w3c.dom.Node node = plotArea.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) {
                continue;
            }
            String local = child.getLocalName();
            if (local != null && local.endsWith("Chart") && !TfXml.childrenByLocalName(child, "ser").isEmpty()) {
                chartTypes.add(child);
            }
        }
        return chartTypes;
    }

    private static List<Object> cacheValues(Element parent, boolean numeric) {
        if (parent == null) {
            return List.of();
        }
        Element cache = TfXml.firstDescendant(parent, "strCache");
        if (cache == null) {
            cache = TfXml.firstDescendant(parent, "numCache");
        }
        if (cache == null) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        for (Element point : TfXml.childrenByLocalName(cache, "pt")) {
            Element v = TfXml.firstChild(point, "v");
            String value = v == null || v.getTextContent() == null ? "" : v.getTextContent();
            values.add(numeric ? coerceNumber(value) : value);
        }
        return values;
    }

    private static Object coerceNumber(String value) {
        try {
            double number = Double.parseDouble(value);
            if (number == Math.rint(number)) {
                return (int) number;
            }
            return number;
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private static String seriesName(Element series, String fallback) {
        Element tx = TfXml.firstChild(series, "tx");
        if (tx == null) {
            return fallback;
        }
        List<Object> values = cacheValues(tx, false);
        if (!values.isEmpty()) {
            return String.valueOf(values.get(0));
        }
        Element direct = TfXml.firstChild(tx, "v");
        if (direct != null && direct.getTextContent() != null && !direct.getTextContent().isBlank()) {
            return direct.getTextContent();
        }
        return fallback;
    }
}
