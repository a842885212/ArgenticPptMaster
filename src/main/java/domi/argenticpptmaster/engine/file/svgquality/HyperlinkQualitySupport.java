// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Hyperlink contract fail-closed subset from pin {@code hyperlink_contract}. */
final class HyperlinkQualitySupport {

    private static final String SHAPE_HYPERLINK_ATTR = "data-pptx-shape-hyperlink";
    private static final Set<String> UNSUPPORTED_LINK_BEHAVIOR =
            Set.of("target", "download", "ping", "rel", "hreflang", "type", "referrerpolicy");
    private static final Pattern SLIDE_TARGET = Pattern.compile("^slide:(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);

    private HyperlinkQualitySupport() {}

    static List<String> checkHyperlinks(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<Element, Element> parents = SvgQualityXml.parentByElement(document);
        NodeList nodes = document.getElementsByTagName("*");
        int anchorIndex = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            if ("a".equals(tag)) {
                anchorIndex++;
                String label = element.hasAttribute("id")
                        ? element.getAttribute("id")
                        : "anchor " + anchorIndex;
                String href = null;
                if (element.hasAttribute("href")) {
                    href = element.getAttribute("href");
                } else if (element.hasAttributeNS(SvgQualityXml.XLINK_NS, "href")) {
                    href = element.getAttributeNS(SvgQualityXml.XLINK_NS, "href");
                }
                try {
                    parseTarget(href);
                } catch (IllegalArgumentException ex) {
                    errors.add(label + ": " + ex.getMessage());
                }
                for (int a = 0; a < element.getAttributes().getLength(); a++) {
                    String name = element.getAttributes().item(a).getNodeName();
                    int brace = name.lastIndexOf('}');
                    String local = (brace >= 0 ? name.substring(brace + 1) : name).toLowerCase(Locale.ROOT);
                    if (UNSUPPORTED_LINK_BEHAVIOR.contains(local)) {
                        errors.add(label + ": unsupported link behavior attribute(s): " + local);
                    }
                }
                Element parent = parents.get(element);
                while (parent != null) {
                    String ptag = SvgQualityXml.localName(parent).toLowerCase(Locale.ROOT);
                    if ("a".equals(ptag)) {
                        errors.add(label + ": nested SVG <a> elements are not supported");
                        break;
                    }
                    if (Set.of("defs", "metadata", "title", "desc", "style").contains(ptag)) {
                        errors.add(label + ": hyperlink cannot appear inside non-output <" + ptag + "> content");
                        break;
                    }
                    parent = parents.get(parent);
                }
            }
            if (element.hasAttribute(SHAPE_HYPERLINK_ATTR)) {
                String label = SvgQualityXml.elementLabel(element);
                if (!"g".equals(tag)) {
                    errors.add(label + ": " + SHAPE_HYPERLINK_ATTR + " is allowed only on <g>");
                }
                try {
                    parseTarget(element.getAttribute(SHAPE_HYPERLINK_ATTR));
                } catch (IllegalArgumentException ex) {
                    errors.add("Invalid SVG hyperlink: " + label + ": " + ex.getMessage());
                }
                Element parent = parents.get(element);
                while (parent != null) {
                    if ("a".equalsIgnoreCase(SvgQualityXml.localName(parent))) {
                        errors.add(label + ": " + SHAPE_HYPERLINK_ATTR + " cannot be nested in <a>");
                        break;
                    }
                    parent = parents.get(parent);
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static void parseTarget(String href) {
        if (href == null || href.isBlank()) {
            throw new IllegalArgumentException("hyperlink target must not be empty");
        }
        String value = href.strip();
        if (HTTP.matcher(value).matches()) {
            return;
        }
        if (SLIDE_TARGET.matcher(value).matches()) {
            return;
        }
        if (value.startsWith("#") && value.length() > 1) {
            return;
        }
        throw new IllegalArgumentException(
                "hyperlink target must be http(s) URL, slide:N, or #fragment; got '" + href + "'");
    }
}
