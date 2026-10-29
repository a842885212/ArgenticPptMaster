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

/** Opacity value errors from {@code svg_contracts.check_opacity_values} (errors only). */
final class OpacityQualitySupport {

    private static final Set<String> OPACITY_PROPERTIES = Set.of(
            "opacity", "fill-opacity", "stroke-opacity", "stop-opacity", "flood-opacity");

    private static final Set<String> PERCENTAGE_OPACITY_PROPERTIES = Set.of("stop-opacity", "flood-opacity");

    private OpacityQualitySupport() {}

    static List<String> checkOpacityValues(Document document) {
        List<String> errors = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            for (String property : OPACITY_PROPERTIES) {
                if (element.hasAttribute(property)) {
                    collect(errors, label, "attribute", property, element.getAttribute(property));
                }
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            for (String property : OPACITY_PROPERTIES) {
                if (style.containsKey(property)) {
                    collect(errors, label, "inline style", property, style.get(property));
                }
            }
        }
        return errors;
    }

    private static void collect(
            List<String> errors, String label, String source, String property, String raw) {
        try {
            SvgQualityLength.parseProjectOpacity(
                    raw, PERCENTAGE_OPACITY_PROPERTIES.contains(property.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            errors.add(label + " " + source + " " + property + "='" + raw + "': " + ex.getMessage());
        }
    }
}
