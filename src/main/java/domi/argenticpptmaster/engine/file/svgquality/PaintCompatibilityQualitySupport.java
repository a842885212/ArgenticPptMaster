// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Paint compatibility errors from {@code svg_contracts.check_paint_compatibility}
 * ({@code _project_paint_errors} only — no canonical-spelling recommendations).
 */
final class PaintCompatibilityQualitySupport {

    private PaintCompatibilityQualitySupport() {}

    static List<String> checkPaintCompatibility(Document document) {
        List<String> errors = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            for (String property : SvgQualityPaint.PAINT_PROPERTIES) {
                if (element.hasAttribute(property)) {
                    collect(errors, label, "attribute", property, element.getAttribute(property));
                }
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            for (String property : SvgQualityPaint.PAINT_PROPERTIES) {
                String key = property.toLowerCase(Locale.ROOT);
                if (style.containsKey(key)) {
                    collect(errors, label, "inline style", key, style.get(key));
                }
            }
        }
        return errors;
    }

    private static void collect(
            List<String> errors, String label, String source, String property, String raw) {
        try {
            SvgQualityPaint.parseProjectPaint(raw, property);
        } catch (IllegalArgumentException ex) {
            errors.add(label + " " + source + " " + property + "='" + raw + "': " + ex.getMessage());
        }
    }
}
