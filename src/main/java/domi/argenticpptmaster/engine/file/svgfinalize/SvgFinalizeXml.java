// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Shared DOM helpers for {@code finalize_svg} ports. */
public final class SvgFinalizeXml {

    public static final String SVG_NS = "http://www.w3.org/2000/svg";
    public static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private static final Pattern FIRST_NUMBER =
            Pattern.compile("^\\s*,?\\s*([+-]?(?:\\d+\\.?\\d*|\\d*\\.\\d+))");

    private SvgFinalizeXml() {}

    public static Document parse(Path svgFile) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // Best-effort hardening.
        }
        try (InputStream in = Files.newInputStream(svgFile)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    public static void write(Document document, Path svgFile) throws Exception {
        document.getDocumentElement().normalize();
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        String xml = writer.toString();
        // Prefer default SVG namespace like pin ElementTree output.
        xml = xml.replace("xmlns:ns0=\"" + SVG_NS + "\"", "xmlns=\"" + SVG_NS + "\"");
        xml = xml.replaceAll("<ns0:", "<");
        xml = xml.replaceAll("</ns0:", "</");
        xml = xml.replaceAll("\\sns0:", " ");
        Files.writeString(svgFile, xml, StandardCharsets.UTF_8);
    }

    public static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    public static boolean isSvgTag(Element element, String name) {
        return name.equals(localName(element));
    }

    public static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    public static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    public static Map<Element, Element> parentMap(Document document) {
        Map<Element, Element> parents = new LinkedHashMap<>();
        Element root = document.getDocumentElement();
        if (root != null) {
            walkParents(root, parents);
        }
        return parents;
    }

    private static void walkParents(Element parent, Map<Element, Element> parents) {
        for (Element child : childElements(parent)) {
            parents.put(child, parent);
            walkParents(child, parents);
        }
    }

    public static Map<String, String> parseStyle(String style) {
        Map<String, String> out = new LinkedHashMap<>();
        if (style == null || style.isBlank()) {
            return out;
        }
        for (String chunk : style.split(";")) {
            if (chunk.isBlank()) {
                continue;
            }
            int colon = chunk.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            out.put(chunk.substring(0, colon).strip(), chunk.substring(colon + 1).strip());
        }
        return out;
    }

    public static String styleToString(Map<String, String> style) {
        if (style == null || style.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : style.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    public static Double parseFirstNumber(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = FIRST_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String formatNumber(Double n) {
        if (n == null) {
            return null;
        }
        if (Math.abs(n - Math.round(n)) < 1e-6) {
            return Long.toString(Math.round(n));
        }
        String s = String.format(java.util.Locale.ROOT, "%.6f", n);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String formatG(double value) {
        if (Math.abs(value) < 1e-12) {
            value = 0.0;
        }
        // Match Python f'{value:g}' / '{value:.12g}' for common icon scales.
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(value));
        bd = bd.stripTrailingZeros();
        String plain = bd.toPlainString();
        if (plain.equals("-0")) {
            return "0";
        }
        return plain;
    }

    public static String href(Element element) {
        if (element.hasAttribute("href")) {
            return element.getAttribute("href");
        }
        if (element.hasAttributeNS(XLINK_NS, "href")) {
            return element.getAttributeNS(XLINK_NS, "href");
        }
        return null;
    }

    public static void setHref(Element element, String value) {
        if (element.hasAttributeNS(XLINK_NS, "href")) {
            element.setAttributeNS(XLINK_NS, "xlink:href", value);
        } else {
            element.setAttribute("href", value);
        }
    }

    public static String collectTextContent(Element element) {
        StringBuilder sb = new StringBuilder();
        collectText(element, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(child.getNodeValue());
            } else if (child instanceof Element) {
                collectText(child, sb);
            }
        }
    }

    public static Element createSvgElement(Document document, String localName) {
        return document.createElementNS(SVG_NS, localName);
    }
}
