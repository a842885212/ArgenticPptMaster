// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Effective font-size resolution for visible text (pin {@code resolve_project_font_sizes}
 * simplified to length parsing already accepted by quality grammar).
 */
final class SvgQualityFontSizes {

    private static final Set<String> DEFINITION_CONTAINERS =
            Set.of("clippath", "defs", "marker", "mask", "pattern", "symbol");

    private SvgQualityFontSizes() {}

    static Map<Element, Double> resolve(Document document) {
        Element root = document.getDocumentElement();
        Map<Element, Double> resolved = new IdentityHashMap<>();
        walk(root, 16.0, resolved);
        return resolved;
    }

    static String canonicalKey(double value) {
        return String.format(Locale.ROOT, "%.12g", value);
    }

    static Map<String, Integer> effectiveTextSizeCounts(Document document) {
        Map<Element, Double> sizes = resolve(document);
        Map<String, Integer> counts = new LinkedHashMap<>();
        visitVisible(document.getDocumentElement(), sizes, counts);
        return counts;
    }

    private static void visitVisible(Element element, Map<Element, Double> sizes, Map<String, Integer> counts) {
        String local = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
        if (DEFINITION_CONTAINERS.contains(local)) {
            return;
        }
        if ("text".equals(local)) {
            for (String key : collectTextObjectSizes(element, sizes)) {
                counts.merge(key, 1, Integer::sum);
            }
            return;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                visitVisible(child, sizes, counts);
            }
        }
    }

    private static Set<String> collectTextObjectSizes(Element text, Map<Element, Double> sizes) {
        Set<String> values = new LinkedHashSet<>();
        collectRuns(text, sizes, values);
        return values;
    }

    private static void collectRuns(Element node, Map<Element, Double> sizes, Set<String> values) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof org.w3c.dom.Text textNode) {
                // DOM text nodes include ElementTree "tail" siblings under the parent.
                if (textNode.getData() != null && !textNode.getData().isBlank()) {
                    Double size = sizes.get(node);
                    if (size != null) {
                        values.add(canonicalKey(size));
                    }
                }
            } else if (child instanceof Element element) {
                collectRuns(element, sizes, values);
            }
        }
    }

    private static void walk(Element elem, double parentSize, Map<Element, Double> resolved) {
        Map<String, String> style = SvgQualityXml.parseInlineStyle(elem.getAttribute("style"));
        double effective = parentSize;
        if (style.containsKey("font-size")) {
            effective = parseSizeOr(parentSize, style.get("font-size"));
        } else if (elem.hasAttribute("font-size")) {
            effective = parseSizeOr(parentSize, elem.getAttribute("font-size"));
        }
        resolved.put(elem, effective);
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                walk(child, effective, resolved);
            }
        }
    }

    private static double parseSizeOr(double fallback, String raw) {
        try {
            double px = SvgQualityLength.parseSvgLength(raw, Double.NaN, null, fallback);
            return Double.isFinite(px) && px >= 0 ? px : fallback;
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
