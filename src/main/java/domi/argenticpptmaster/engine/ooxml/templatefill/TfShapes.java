// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/** Shape/table/chart discovery helpers aligned with pin {@code ooxml.py}. */
public final class TfShapes {

    public record ShapeIdentity(String id, String name) {}

    private TfShapes() {}

    public static ShapeIdentity shapeIdentity(Element container, int order) {
        Element cNvPr = TfXml.firstDescendant(container, "cNvPr");
        if (cNvPr != null) {
            String id = cNvPr.getAttribute("id");
            String name = cNvPr.getAttribute("name");
            if (id == null || id.isBlank()) {
                id = String.valueOf(order);
            }
            return new ShapeIdentity(id, name == null ? "" : name);
        }
        return new ShapeIdentity(String.valueOf(order), "");
    }

    public static List<Element> textContainers(Element slideRoot) {
        List<Element> containers = new ArrayList<>();
        for (String tag : List.of("sp", "graphicFrame")) {
            for (Element element : TfXml.descendants(slideRoot, tag)) {
                if (!TfXml.descendants(element, "txBody").isEmpty()
                        || !TfXml.descendants(element, "t").isEmpty()) {
                    containers.add(element);
                }
            }
        }
        return containers;
    }

    public static List<Element> tableContainers(Element slideRoot) {
        List<Element> frames = new ArrayList<>();
        for (Element frame : TfXml.descendants(slideRoot, "graphicFrame")) {
            if (TfXml.firstDescendant(frame, "tbl") != null) {
                frames.add(frame);
            }
        }
        return frames;
    }

    public static List<String> paragraphTexts(Element container) {
        List<String> paragraphs = new ArrayList<>();
        for (Element paragraph : TfXml.descendants(container, "p")) {
            if (!OoxmlConstants.DRAWING_NS.equals(paragraph.getNamespaceURI())
                    && !"http://schemas.openxmlformats.org/drawingml/2006/main"
                            .equals(paragraph.getNamespaceURI())) {
                // Keep DrawingML paragraphs; also accept unprefixed accidental matches.
                if (!"p".equals(paragraph.getLocalName())) {
                    continue;
                }
            }
            StringBuilder text = new StringBuilder();
            for (Element t : TfXml.descendants(paragraph, "t")) {
                text.append(t.getTextContent() == null ? "" : t.getTextContent());
            }
            String stripped = text.toString().strip();
            if (!stripped.isEmpty()) {
                paragraphs.add(stripped);
            }
        }
        if (!paragraphs.isEmpty()) {
            return paragraphs;
        }
        StringBuilder all = new StringBuilder();
        for (Element t : TfXml.descendants(container, "t")) {
            all.append(t.getTextContent() == null ? "" : t.getTextContent());
        }
        String stripped = all.toString().strip();
        return stripped.isEmpty() ? List.of() : List.of(stripped);
    }

    public static Map<String, Integer> containerGeometry(Element container) {
        Element xfrm = null;
        Element spPr = TfXml.firstChild(container, "spPr");
        if (spPr != null) {
            xfrm = TfXml.firstChild(spPr, "xfrm");
        }
        if (xfrm == null) {
            xfrm = TfXml.firstChild(container, "xfrm");
        }
        if (xfrm == null) {
            xfrm = TfXml.firstDescendant(container, "xfrm");
        }
        Map<String, Integer> geometry = new LinkedHashMap<>();
        if (xfrm == null) {
            geometry.put("x", null);
            geometry.put("y", null);
            geometry.put("width", null);
            geometry.put("height", null);
            return geometry;
        }
        Element off = TfXml.firstChild(xfrm, "off");
        Element ext = TfXml.firstChild(xfrm, "ext");
        geometry.put("x", off == null ? null : TfXml.emuToPx(off.getAttribute("x")));
        geometry.put("y", off == null ? null : TfXml.emuToPx(off.getAttribute("y")));
        geometry.put("width", ext == null ? null : TfXml.emuToPx(ext.getAttribute("cx")));
        geometry.put("height", ext == null ? null : TfXml.emuToPx(ext.getAttribute("cy")));
        return geometry;
    }

    /** Return (classic|chartex|unknown|"", relationship id) for a chart graphic frame. */
    public static String[] chartReference(Element frame) {
        Element graphicData = TfXml.firstDescendant(frame, "graphicData");
        if (graphicData == null) {
            return new String[] {"", ""};
        }
        for (Element node : TfXml.descendants(graphicData, "chart")) {
            String ns = node.getNamespaceURI() == null ? "" : node.getNamespaceURI();
            String relId = TfXml.attrNs(node, OoxmlConstants.OFFICE_REL_NS, "id");
            if (relId == null || relId.isBlank()) {
                relId = node.getAttribute("r:id");
            }
            if (TfConstants.CHART_NS.equals(ns)) {
                return new String[] {"classic", relId == null ? "" : relId};
            }
            if (ns.toLowerCase().contains("chartex")) {
                return new String[] {"chartex", relId == null ? "" : relId};
            }
            return new String[] {"unknown", relId == null ? "" : relId};
        }
        String uri = graphicData.getAttribute("uri").toLowerCase();
        if (uri.contains("chartex")) {
            return new String[] {"chartex", ""};
        }
        if (uri.contains("chart")) {
            return new String[] {"unknown", ""};
        }
        return new String[] {"", ""};
    }

    public static List<Element> chartFrames(Element slideRoot) {
        List<Element> frames = new ArrayList<>();
        for (Element frame : TfXml.descendants(slideRoot, "graphicFrame")) {
            if (!chartReference(frame)[0].isEmpty()) {
                frames.add(frame);
            }
        }
        return frames;
    }
}
