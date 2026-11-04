package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Native chart transcription aligned with {@code ppt_to_md.py} {@code chart_to_markdown}：
 * categories × series 数值转录为 Markdown 表格，chart 类型字符串对齐 python-pptx
 * {@code PlotTypeInspector} 的判定规则（"{@code NAME (int)}"）。
 *
 * <p>未知 chart 类型输出无类型头的 {@code > [Chart] name}；解析异常由调用方兜底为同款占位文本，
 * 不自行扩展 Python 版不支持的类型。</p>
 */
final class ChartTranscriber {

    private ChartTranscriber() {}

    /** python-pptx {@code XL_CHART_TYPE} 名称 → MSO 枚举值（str(enum) = "NAME (value)"）。 */
    private static final Map<String, Integer> CHART_TYPE_VALUES = Map.ofEntries(
            Map.entry("THREE_D_AREA", -4098),
            Map.entry("THREE_D_AREA_STACKED", 78),
            Map.entry("THREE_D_AREA_STACKED_100", 79),
            Map.entry("THREE_D_BAR_CLUSTERED", 60),
            Map.entry("THREE_D_BAR_STACKED", 61),
            Map.entry("THREE_D_BAR_STACKED_100", 62),
            Map.entry("THREE_D_COLUMN", -4100),
            Map.entry("THREE_D_COLUMN_CLUSTERED", 54),
            Map.entry("THREE_D_COLUMN_STACKED", 55),
            Map.entry("THREE_D_COLUMN_STACKED_100", 56),
            Map.entry("THREE_D_LINE", -4101),
            Map.entry("THREE_D_PIE", -4102),
            Map.entry("THREE_D_PIE_EXPLODED", 70),
            Map.entry("AREA", 1),
            Map.entry("AREA_STACKED", 76),
            Map.entry("AREA_STACKED_100", 77),
            Map.entry("BAR_CLUSTERED", 57),
            Map.entry("BAR_STACKED", 58),
            Map.entry("BAR_STACKED_100", 59),
            Map.entry("BUBBLE", 15),
            Map.entry("BUBBLE_THREE_D_EFFECT", 87),
            Map.entry("COLUMN_CLUSTERED", 51),
            Map.entry("COLUMN_STACKED", 52),
            Map.entry("COLUMN_STACKED_100", 53),
            Map.entry("DOUGHNUT", -4120),
            Map.entry("DOUGHNUT_EXPLODED", 80),
            Map.entry("LINE", 4),
            Map.entry("LINE_MARKERS", 65),
            Map.entry("LINE_MARKERS_STACKED", 66),
            Map.entry("LINE_MARKERS_STACKED_100", 67),
            Map.entry("LINE_STACKED", 63),
            Map.entry("LINE_STACKED_100", 64),
            Map.entry("PIE", 5),
            Map.entry("PIE_EXPLODED", 69),
            Map.entry("RADAR", -4151),
            Map.entry("RADAR_FILLED", 82),
            Map.entry("RADAR_MARKERS", 81),
            Map.entry("XY_SCATTER", -4169),
            Map.entry("XY_SCATTER_LINES", 74),
            Map.entry("XY_SCATTER_LINES_NO_MARKERS", 75),
            Map.entry("XY_SCATTER_SMOOTH", 72),
            Map.entry("XY_SCATTER_SMOOTH_NO_MARKERS", 73));

    /** 渲染 chart part 为 Markdown；{@code name} 为 graphicFrame 的 shape 名。 */
    static String chartToMarkdown(Document chartDoc, String name) {
        Element chart = TfXml.firstDescendant(chartDoc.getDocumentElement(), "chart");
        Element plotArea = chart == null ? null : TfXml.firstDescendant(chart, "plotArea");
        if (plotArea == null) {
            return header(name, "");
        }
        Element plot = firstPlot(plotArea);
        if (plot == null) {
            return header(name, "");
        }

        String chartType = chartTypeString(plot);
        List<String> categories = categories(plot);
        List<SeriesData> data = new ArrayList<>();
        int index = 0;
        for (Element ser : TfXml.childrenByLocalName(plot, "ser")) {
            index++;
            data.add(new SeriesData(escapeCell(seriesName(ser, index)), seriesValues(ser)));
        }

        int rowCount = !categories.isEmpty()
                ? categories.size()
                : data.stream().mapToInt(entry -> entry.values().size()).max().orElse(0);
        if (data.isEmpty() || rowCount == 0) {
            return header(name, chartType);
        }

        StringBuilder out = new StringBuilder(header(name, chartType)).append("\n\n");
        List<String> headerCells = new ArrayList<>();
        headerCells.add(categories.isEmpty() ? "#" : "Category");
        for (SeriesData entry : data) {
            headerCells.add(entry.name());
        }
        out.append("| ").append(String.join(" | ", headerCells)).append(" |\n");
        out.append("| ");
        for (int i = 0; i < headerCells.size(); i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append("---");
        }
        out.append(" |\n");
        for (int row = 0; row < rowCount; row++) {
            List<String> cells = new ArrayList<>();
            if (!categories.isEmpty()) {
                cells.add(row < categories.size() ? categories.get(row) : "");
            } else {
                cells.add(String.valueOf(row + 1));
            }
            for (SeriesData entry : data) {
                cells.add(row < entry.values().size() ? formatValue(entry.values().get(row)) : "");
            }
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        return out.toString().strip();
    }

    private record SeriesData(String name, List<String> values) {}

    private static String header(String name, String chartType) {
        return "> [Chart] " + name + (chartType.isEmpty() ? "" : " — " + chartType);
    }

    /** 对齐 python-pptx {@code PlotTypeInspector.chart_type}（仅覆盖其实现的类型，未知 → ""）。 */
    private static String chartTypeString(Element plot) {
        String kind = plot.getLocalName();
        String grouping = childVal(plot, "grouping");
        if (grouping == null || grouping.isBlank()) {
            grouping = "standard";
        }
        String name = switch (kind) {
            case "barChart" -> {
                String barDir = childVal(plot, "barDir");
                boolean clustered = grouping.equals("clustered") || grouping.equals("standard");
                if ("bar".equals(barDir)) {
                    yield clustered ? "BAR_CLUSTERED" : stackedName(grouping, "BAR_STACKED", "BAR_STACKED_100");
                }
                yield clustered ? "COLUMN_CLUSTERED" : stackedName(grouping, "COLUMN_STACKED", "COLUMN_STACKED_100");
            }
            case "lineChart" -> {
                boolean markers = lineChartHasMarkers(plot);
                if (markers) {
                    yield switch (grouping) {
                        case "stacked" -> "LINE_MARKERS_STACKED";
                        case "percentStacked" -> "LINE_MARKERS_STACKED_100";
                        default -> "LINE_MARKERS";
                    };
                }
                yield switch (grouping) {
                    case "stacked" -> "LINE_STACKED";
                    case "percentStacked" -> "LINE_STACKED_100";
                    default -> "LINE";
                };
            }
            case "pieChart" -> hasChild(plot, "explosion") ? "PIE_EXPLODED" : "PIE";
            case "doughnutChart" -> hasChild(plot, "explosion") ? "DOUGHNUT_EXPLODED" : "DOUGHNUT";
            case "radarChart" -> radarType(plot);
            case "areaChart" -> switch (grouping) {
                case "stacked" -> "AREA_STACKED";
                case "percentStacked" -> "AREA_STACKED_100";
                default -> "AREA";
            };
            case "area3DChart" -> switch (grouping) {
                case "stacked" -> "THREE_D_AREA_STACKED";
                case "percentStacked" -> "THREE_D_AREA_STACKED_100";
                default -> "THREE_D_AREA";
            };
            case "bubbleChart" -> bubbleType(plot);
            case "scatterChart" -> scatterType(plot);
            default -> "";
        };
        if (name.isEmpty()) {
            return "";
        }
        Integer value = CHART_TYPE_VALUES.get(name);
        return value == null ? "" : name + " (" + value + ")";
    }

    private static String stackedName(String grouping, String stacked, String percentStacked) {
        if (grouping.equals("stacked")) {
            return stacked;
        }
        if (grouping.equals("percentStacked")) {
            return percentStacked;
        }
        return "";
    }

    /** python-pptx：任一 ser 的 marker.symbol == "none" 即无 markers。 */
    private static boolean lineChartHasMarkers(Element plot) {
        for (Element ser : TfXml.childrenByLocalName(plot, "ser")) {
            Element marker = TfXml.firstChild(ser, "marker");
            Element symbol = marker == null ? null : TfXml.firstChild(marker, "symbol");
            if (symbol != null && "none".equals(symbol.getAttribute("val"))) {
                return false;
            }
        }
        return true;
    }

    private static String radarType(Element plot) {
        String style = childVal(plot, "radarStyle");
        if (style == null) {
            return "RADAR";
        }
        if (style.equals("filled")) {
            return "RADAR_FILLED";
        }
        for (Element ser : TfXml.childrenByLocalName(plot, "ser")) {
            Element marker = TfXml.firstChild(ser, "marker");
            Element symbol = marker == null ? null : TfXml.firstChild(marker, "symbol");
            if (symbol != null && "none".equals(symbol.getAttribute("val"))) {
                return "RADAR";
            }
        }
        return "RADAR_MARKERS";
    }

    private static String bubbleType(Element plot) {
        Element bubble3D = TfXml.firstDescendant(plot, "bubble3D");
        if (bubble3D != null && "1".equals(bubble3D.getAttribute("val"))) {
            return "BUBBLE_THREE_D_EFFECT";
        }
        return "BUBBLE";
    }

    private static String scatterType(Element plot) {
        String style = childVal(plot, "scatterStyle");
        boolean noMarkers = false;
        for (Element ser : TfXml.childrenByLocalName(plot, "ser")) {
            Element marker = TfXml.firstChild(ser, "marker");
            Element symbol = marker == null ? null : TfXml.firstChild(marker, "symbol");
            if (symbol != null && "none".equals(symbol.getAttribute("val"))) {
                noMarkers = true;
                break;
            }
        }
        boolean noLine = false;
        for (Element ser : TfXml.childrenByLocalName(plot, "ser")) {
            Element spPr = TfXml.firstChild(ser, "spPr");
            Element ln = spPr == null ? null : TfXml.firstChild(spPr, "ln");
            if (ln != null && TfXml.firstChild(ln, "noFill") != null) {
                noLine = true;
                break;
            }
        }
        if ("lineMarker".equals(style)) {
            if (noLine) {
                return "XY_SCATTER";
            }
            return noMarkers ? "XY_SCATTER_LINES_NO_MARKERS" : "XY_SCATTER_LINES";
        }
        if ("smoothMarker".equals(style)) {
            return noMarkers ? "XY_SCATTER_SMOOTH_NO_MARKERS" : "XY_SCATTER_SMOOTH";
        }
        return "XY_SCATTER";
    }

    private static Element firstPlot(Element plotArea) {
        org.w3c.dom.NodeList children = plotArea.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            switch (child.getLocalName()) {
                case "barChart", "lineChart", "pieChart", "doughnutChart", "radarChart",
                        "areaChart", "area3DChart", "bubbleChart", "scatterChart" -> {
                    return child;
                }
                default -> {
                    // layout / axis 等非 plot 元素跳过
                }
            }
        }
        return null;
    }

    /** categories：第一个 ser 的 c:cat（strRef/numRef 的 cache pt 按 idx 排序）；无则空列表。 */
    private static List<String> categories(Element plot) {
        Element firstSer = TfXml.firstChild(plot, "ser");
        Element cat = firstSer == null ? null : TfXml.firstChild(firstSer, "cat");
        if (cat == null) {
            return List.of();
        }
        List<String[]> points = cachedPoints(cat);
        List<String> out = new ArrayList<>();
        for (String[] point : points) {
            out.add(escapeCell(point[1]));
        }
        return out;
    }

    private static String seriesName(Element ser, int index) {
        Element tx = TfXml.firstChild(ser, "tx");
        if (tx == null) {
            return "Series " + index;
        }
        Element v = TfXml.firstDescendant(tx, "v");
        if (v == null || v.getTextContent() == null || v.getTextContent().isEmpty()) {
            return "Series " + index;
        }
        return v.getTextContent();
    }

    private static List<String> seriesValues(Element ser) {
        Element val = TfXml.firstChild(ser, "val");
        if (val == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String[] point : cachedPoints(val)) {
            out.add(point[1]);
        }
        return out;
    }

    /** 读取 strCache/numCache（或引用所在 cache）的 c:pt，按 idx 数值排序（对齐 python-pptx pt 顺序语义）。 */
    private static List<String[]> cachedPoints(Element parent) {
        Element cache = null;
        for (String local : List.of("strCache", "numCache")) {
            cache = TfXml.firstDescendant(parent, local);
            if (cache != null) {
                break;
            }
        }
        if (cache == null) {
            return List.of();
        }
        List<String[]> points = new ArrayList<>();
        for (Element pt : TfXml.childrenByLocalName(cache, "pt")) {
            String idx = pt.getAttribute("idx");
            Element v = TfXml.firstChild(pt, "v");
            points.add(new String[] {idx, v == null || v.getTextContent() == null ? "" : v.getTextContent()});
        }
        points.sort((a, b) -> Integer.compare(parseIdx(a[0]), parseIdx(b[0])));
        return points;
    }

    private static int parseIdx(String idx) {
        try {
            return Integer.parseInt(idx);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    /** 对齐 {@code _format_chart_value}：整数值去小数，其余按原样（XML 文本经 double 往返）。 */
    private static String formatValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (value == Math.rint(value) && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private static String escapeCell(String value) {
        return ReadbackTextFrames.normalizeText(value).replace("|", "\\|");
    }

    private static String childVal(Element parent, String localName) {
        Element child = TfXml.firstChild(parent, localName);
        return child == null ? null : child.getAttribute("val");
    }

    private static boolean hasChild(Element parent, String localName) {
        return TfXml.firstDescendant(parent, localName) != null;
    }

}
