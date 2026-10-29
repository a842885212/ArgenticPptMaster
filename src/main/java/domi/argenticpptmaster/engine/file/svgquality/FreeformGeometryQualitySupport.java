// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Freeform path/points grammar errors (illegal d / unsupported syntax first). */
final class FreeformGeometryQualitySupport {

    private FreeformGeometryQualitySupport() {}

    static List<String> checkFreeformGeometryValues(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            String label = SvgQualityXml.elementLabel(element);
            try {
                if ("path".equals(tag)) {
                    if (!element.hasAttribute("d")) {
                        throw new IllegalArgumentException("<path> requires d");
                    }
                    SvgQualityPath.parseSvgPath(element.getAttribute("d"));
                } else if ("polygon".equals(tag) || "polyline".equals(tag)) {
                    if (!element.hasAttribute("points")) {
                        throw new IllegalArgumentException("<" + tag + "> requires points");
                    }
                    int min = "polygon".equals(tag) ? 3 : 2;
                    SvgQualityPath.parseSvgPoints(element.getAttribute("points"), min);
                }
            } catch (IllegalArgumentException ex) {
                String attr = "path".equals(tag) ? "d" : "points";
                errors.add(label + " " + attr + ": " + ex.getMessage());
            }
        }
        return new ArrayList<>(errors);
    }
}
