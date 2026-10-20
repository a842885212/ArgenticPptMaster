// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Visible {@code <image>} instances (from ppt-master {@code _visible_image_elements}). */
final class SvgQualityVisibleImages {

    private static final Set<String> NON_VISUAL_SVG_TAGS =
            Set.of("defs", "desc", "metadata", "style", "title");

    private SvgQualityVisibleImages() {}

    static List<Element> visibleImageElements(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        Map<Element, Element> parentByElement = SvgQualityXml.parentByElement(document);
        NodeList images = document.getElementsByTagNameNS(SvgQualityXml.SVG_NS, "image");
        List<Element> visible = new ArrayList<>(images.getLength());
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            if (isHiddenElement(image, parentByElement)) {
                continue;
            }
            if (hasNonVisualAncestor(image, root, parentByElement)) {
                continue;
            }
            if (hasZeroOpacity(image, parentByElement)) {
                continue;
            }
            visible.add(image);
        }
        return visible;
    }

    private static boolean isHiddenElement(Element element, Map<Element, Element> parentByElement) {
        Element current = element;
        while (current != null) {
            Map<String, String> style = SvgQualityXml.parseInlineStyle(current.getAttribute("style"));
            String display = style.getOrDefault("display", current.getAttribute("display"));
            if (!display.isBlank() && "none".equalsIgnoreCase(display.strip())) {
                return true;
            }
            current = parentByElement.get(current);
        }
        String visibility = SvgQualityXml.presentationValue(element, "visibility", parentByElement);
        if (visibility != null) {
            String normalized = visibility.strip().toLowerCase();
            if ("hidden".equals(normalized) || "collapse".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasZeroOpacity(Element element, Map<Element, Element> parentByElement) {
        Element current = element;
        while (current != null) {
            Map<String, String> style = SvgQualityXml.parseInlineStyle(current.getAttribute("style"));
            String raw = style.containsKey("opacity") ? style.get("opacity") : current.getAttribute("opacity");
            if (raw != null && !raw.isBlank()) {
                String value = raw.strip();
                try {
                    double opacity = value.endsWith("%")
                            ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100.0
                            : Double.parseDouble(value);
                    if (opacity <= 0.0) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore unparsable opacity.
                }
            }
            current = parentByElement.get(current);
        }
        return false;
    }

    private static boolean hasNonVisualAncestor(
            Element element, Element moduleRoot, Map<Element, Element> parentByElement) {
        Element current = element;
        while (current != null && current != moduleRoot) {
            if (NON_VISUAL_SVG_TAGS.contains(SvgQualityXml.localName(current))) {
                return true;
            }
            current = parentByElement.get(current);
        }
        return false;
    }
}
