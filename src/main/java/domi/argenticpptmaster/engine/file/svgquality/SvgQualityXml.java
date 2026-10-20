// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class SvgQualityXml {

    static final String SVG_NS = "http://www.w3.org/2000/svg";
    static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    private SvgQualityXml() {}

    static Optional<Document> parse(Path svgFile) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // Best-effort hardening.
        }
        try {
            var builder = factory.newDocumentBuilder();
            try (InputStream in = Files.newInputStream(svgFile)) {
                return Optional.of(builder.parse(in));
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return Optional.empty();
        }
    }

    static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    static String elementLabel(Element element) {
        String tag = localName(element);
        String elemId = element.getAttribute("id");
        if (elemId != null) {
            elemId = elemId.strip();
        }
        if (elemId != null && !elemId.isEmpty()) {
            return "<" + tag + " id=\"" + elemId + "\">";
        }
        return "<" + tag + ">";
    }

    static Map<Element, Element> parentByElement(Document document) {
        Map<Element, Element> parentByElement = new HashMap<>();
        Element root = document.getDocumentElement();
        if (root == null) {
            return parentByElement;
        }
        walkParents(root, parentByElement);
        return parentByElement;
    }

    private static void walkParents(Element parent, Map<Element, Element> parentByElement) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                parentByElement.put(child, parent);
                walkParents(child, parentByElement);
            }
        }
    }

    static Map<String, String> parseInlineStyle(String style) {
        Map<String, String> values = new HashMap<>();
        if (style == null || style.isBlank()) {
            return values;
        }
        for (String part : style.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = part.substring(0, colon).strip();
            String value = part.substring(colon + 1).strip();
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    static String presentationValue(Element element, String name, Map<Element, Element> parentByElement) {
        Element current = element;
        while (current != null) {
            Map<String, String> style = parseInlineStyle(current.getAttribute("style"));
            if (style.containsKey(name)) {
                return style.get(name);
            }
            if (current.hasAttribute(name)) {
                return current.getAttribute(name);
            }
            current = parentByElement.get(current);
        }
        return null;
    }

    static String imageHref(Element image) {
        if (image.hasAttribute("href")) {
            return image.getAttribute("href");
        }
        if (image.hasAttributeNS(XLINK_NS, "href")) {
            return image.getAttributeNS(XLINK_NS, "href");
        }
        return null;
    }
}
