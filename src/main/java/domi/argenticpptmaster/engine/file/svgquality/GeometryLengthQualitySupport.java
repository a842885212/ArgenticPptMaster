// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Geometry length errors from {@code svg_contracts.check_geometry_length_values} (errors only). */
final class GeometryLengthQualitySupport {

    private static final Map<String, Set<String>> GEOMETRY_LENGTH_ATTRIBUTES = Map.of(
            "svg", Set.of("x", "y", "width", "height"),
            "rect", Set.of("x", "y", "width", "height", "rx", "ry"),
            "circle", Set.of("cx", "cy", "r"),
            "ellipse", Set.of("cx", "cy", "rx", "ry"),
            "line", Set.of("x1", "y1", "x2", "y2"),
            "text", Set.of("x", "y"),
            "tspan", Set.of("x", "y", "dx", "dy"),
            "image", Set.of("x", "y", "width", "height"),
            "use", Set.of("x", "y", "width", "height"));

    private GeometryLengthQualitySupport() {}

    static List<String> checkGeometryLengthValues(Document document) {
        List<String> errors = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            String label = SvgQualityXml.elementLabel(element);
            Set<String> attrs = GEOMETRY_LENGTH_ATTRIBUTES.getOrDefault(tag, Set.of());
            for (String attribute : attrs) {
                if (element.hasAttribute(attribute)) {
                    collect(errors, label, "attribute", attribute, element.getAttribute(attribute));
                }
            }
            if (element.hasAttribute("stroke-width")) {
                collect(errors, label, "attribute", "stroke-width", element.getAttribute("stroke-width"));
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            if (style.containsKey("stroke-width")) {
                collect(errors, label, "inline style", "stroke-width", style.get("stroke-width"));
            }
        }
        return errors;
    }

    private static void collect(
            List<String> errors, String label, String source, String attribute, String raw) {
        try {
            SvgQualityLength.parseProjectGeometryLength(raw, attribute);
        } catch (IllegalArgumentException ex) {
            errors.add(label + " " + source + " " + attribute + "='" + raw + "': " + ex.getMessage());
        }
    }
}
