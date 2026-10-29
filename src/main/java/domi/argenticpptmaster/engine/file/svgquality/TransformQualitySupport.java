// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Transform errors from {@code svg_contracts.check_transform_values} (errors only). */
final class TransformQualitySupport {

    private static final Set<String> RESTRICTED_ANCESTORS =
            Set.of("defs", "clippath", "filter", "marker", "mask", "pattern", "symbol");

    private TransformQualitySupport() {}

    static List<String> checkTransformValues(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<Element, Element> parents = SvgQualityXml.parentByElement(document);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            if (!element.hasAttribute("transform")) {
                continue;
            }
            String raw = element.getAttribute("transform");
            String label = SvgQualityXml.elementLabel(element);
            String restricted = restrictedAncestor(element, parents);
            if (restricted != null) {
                errors.add(label + " cannot use transform inside <" + restricted + ">");
                continue;
            }
            try {
                SvgQualityPath.parseTransformOperations(raw);
            } catch (IllegalArgumentException ex) {
                errors.add("Invalid SVG transform: " + label + " transform='" + raw + "': " + ex.getMessage());
            }
        }
        return new ArrayList<>(errors);
    }

    private static String restrictedAncestor(Element element, Map<Element, Element> parents) {
        Element current = parents.get(element);
        while (current != null) {
            String tag = SvgQualityXml.localName(current).toLowerCase(Locale.ROOT);
            if (RESTRICTED_ANCESTORS.contains(tag)) {
                return tag.equals("clippath") ? "clipPath" : tag;
            }
            current = parents.get(current);
        }
        return null;
    }
}
