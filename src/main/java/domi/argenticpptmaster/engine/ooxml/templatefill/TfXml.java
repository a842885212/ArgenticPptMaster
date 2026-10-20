// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Shared DOM helpers for template-fill OOXML edits. */
public final class TfXml {

    private TfXml() {}

    public static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    public static Document parse(String xml) throws Exception {
        return parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] serialize(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toByteArray();
    }

    public static String serializeString(Document document) throws Exception {
        return new String(serialize(document), StandardCharsets.UTF_8);
    }

    public static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    public static List<Element> childrenByLocalName(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                out.add(element);
            }
        }
        return out;
    }

    public static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    public static Element firstDescendant(Element root, String localName) {
        List<Element> found = descendants(root, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    public static String attrNs(Element element, String namespace, String localName) {
        if (element.hasAttributeNS(namespace, localName)) {
            return element.getAttributeNS(namespace, localName);
        }
        return element.getAttribute(localName);
    }

    public static String localName(String tag) {
        int idx = tag.lastIndexOf('}');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }

    public static String namespaceUri(String tag) {
        if (!tag.startsWith("{")) {
            return "";
        }
        int end = tag.indexOf('}');
        return end > 0 ? tag.substring(1, end) : "";
    }

    public static String normalizePart(String target, String basePart) {
        if (target == null || target.isBlank()) {
            return "";
        }
        if (target.startsWith("/")) {
            return target.replaceFirst("^/+", "");
        }
        Path base = Path.of(basePart).getParent();
        if (base == null) {
            base = Path.of("");
        }
        return base.resolve(target).normalize().toString().replace('\\', '/');
    }

    public static String relsNameForPart(String partName) {
        Path path = Path.of(partName);
        Path parent = path.getParent();
        String base = path.getFileName().toString();
        if (parent == null) {
            return "_rels/" + base + ".rels";
        }
        return parent.resolve("_rels").resolve(base + ".rels").toString().replace('\\', '/');
    }

    public static String relativeTarget(String fromPart, String toPart) {
        Path fromDir = Path.of(fromPart).getParent();
        if (fromDir == null) {
            fromDir = Path.of("");
        }
        return fromDir.relativize(Path.of(toPart)).toString().replace('\\', '/');
    }

    public static Map<String, Map<String, String>> parseRelationships(byte[] relsBytes) throws Exception {
        Map<String, Map<String, String>> relationships = new LinkedHashMap<>();
        if (relsBytes == null || relsBytes.length == 0) {
            return relationships;
        }
        Document root = parse(relsBytes);
        for (Element rel : descendants(root.getDocumentElement(), "Relationship")) {
            String id = rel.getAttribute("Id");
            String target = rel.getAttribute("Target");
            String type = rel.getAttribute("Type");
            if (!id.isBlank() && !target.isBlank() && !type.isBlank()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("target", target);
                item.put("type", type);
                if (rel.hasAttribute("TargetMode")) {
                    item.put("targetMode", rel.getAttribute("TargetMode"));
                }
                relationships.put(id, item);
            }
        }
        return relationships;
    }

    public static Document emptyRelationships() throws Exception {
        return parse("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="%s"></Relationships>
                """.formatted(OoxmlConstants.PACKAGE_REL_NS));
    }

    public static int maxNumericRid(Element relationshipsRoot) {
        int max = 0;
        for (Element rel : descendants(relationshipsRoot, "Relationship")) {
            String id = rel.getAttribute("Id");
            if (id.startsWith("rId")) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(3)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return max;
    }

    public static Integer emuToPx(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Long.parseLong(value) / 914400.0 * 96.0);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
