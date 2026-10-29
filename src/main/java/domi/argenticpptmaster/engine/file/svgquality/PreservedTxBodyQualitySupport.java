// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Phase-3 preserved txBody restore contract.
 *
 * <p>Checked only when a group carries preserved payload markers (confirmed boundary).</p>
 */
final class PreservedTxBodyQualitySupport {

    private static final String PML_NS =
            "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Set<String> ROOT_TEXT_STYLE_ATTRS = Set.of(
            "fill",
            "fill-opacity",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "letter-spacing",
            "opacity",
            "stroke",
            "stroke-opacity",
            "stroke-width",
            "text-anchor",
            "text-decoration");
    private static final Set<String> ALLOWED_PARTS =
            Set.of("geometry", "geometry-detail", "geometry-preview", "txbody");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PreservedTxBodyQualitySupport() {}

    static List<String> checkPreservedTxBody(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        Set<String> errors = new LinkedHashSet<>();
        NodeList groups = document.getElementsByTagNameNS("*", "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element group = (Element) groups.item(i);
            Element metadata = txbodyMetadata(group);
            boolean logical = "shape".equals(attr(group, "data-pptx-object"))
                    && (group.hasAttribute("data-pptx-prst")
                            || "custom".equals(attr(group, "data-pptx-geometry-kind")))
                    && metadata != null;
            if (!logical) {
                continue;
            }
            try {
                preservedNativeTextBody(group, metadata);
            } catch (PreservedTxBodyException ex) {
                errors.add(SvgQualityXml.elementLabel(group) + " cannot preserve source txBody: "
                        + ex.getMessage());
            }
        }
        return new ArrayList<>(errors);
    }

    private static void preservedNativeTextBody(Element group, Element metadata) {
        Decoded decoded = decodeUnchangedTxbody(group, metadata);
        if (decoded == null) {
            return;
        }
        List<Element> carriers = new ArrayList<>();
        NodeList children = group.getChildNodes();
        boolean hasForeignVisual = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            String part = attr(child, "data-pptx-part");
            if ("geometry".equals(part)) {
                carriers.add(child);
            }
            String tag = SvgQualityXml.localName(child).toLowerCase(Locale.ROOT);
            if (!"text".equals(tag)
                    && !"metadata".equals(tag)
                    && !ALLOWED_PARTS.contains(part)
                    && !isImportTextFlipCompensation(child)) {
                hasForeignVisual = true;
            }
        }
        if (carriers.size() != 1 || hasForeignVisual) {
            if (decoded.hasRunEffects()) {
                throw new PreservedTxBodyException(
                        "The source txBody contains run-level effects but cannot be "
                                + "restored as one native text shape; export stopped to avoid "
                                + "silently discarding those effects");
            }
        }
    }

    private static Decoded decodeUnchangedTxbody(Element group, Element metadata) {
        String expectedHash = attr(metadata, "data-pptx-text-sha256");
        if (expectedHash.isEmpty()) {
            throw new PreservedTxBodyException("txbody metadata requires a text hash");
        }
        boolean unchanged = svgTextFingerprint(group).equals(expectedHash);
        if (!"base64".equals(attr(metadata, "data-pptx-encoding"))) {
            throw new PreservedTxBodyException("txbody metadata requires base64 encoding");
        }
        String payload = metadata.getTextContent() == null ? "" : metadata.getTextContent().strip();
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new PreservedTxBodyException("Invalid txbody metadata: " + ex.getMessage());
        }
        Document txDoc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            txDoc = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(raw));
        } catch (Exception ex) {
            throw new PreservedTxBodyException("Invalid txbody metadata: " + ex.getMessage());
        }
        Element txRoot = txDoc.getDocumentElement();
        if (txRoot == null
                || !"txBody".equals(txRoot.getLocalName())
                || (txRoot.getNamespaceURI() != null && !PML_NS.equals(txRoot.getNamespaceURI()))) {
            // Also accept default-ns / prefixed as long as local name matches.
            if (txRoot == null || !"txBody".equals(local(txRoot))) {
                throw new PreservedTxBodyException("txbody metadata payload must be p:txBody");
            }
        }
        if (hasRelationshipAttributes(txRoot)) {
            throw new PreservedTxBodyException(
                    "txbody metadata must not contain part-local relationship attributes");
        }
        boolean effects = txbodyHasRunEffects(txRoot);
        if (!unchanged) {
            if (effects) {
                throw new PreservedTxBodyException(
                        "Visible text or typography was edited while the source "
                                + "txBody contains run-level effects; export stopped to avoid "
                                + "silently discarding those effects");
            }
            return null;
        }
        return new Decoded(effects);
    }

    private static Element txbodyMetadata(Element group) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child
                    && "metadata".equals(SvgQualityXml.localName(child).toLowerCase(Locale.ROOT))
                    && "txbody".equals(attr(child, "data-pptx-part"))) {
                return child;
            }
        }
        return null;
    }

    private static boolean isImportTextFlipCompensation(Element elem) {
        if (!"g".equals(SvgQualityXml.localName(elem).toLowerCase(Locale.ROOT))
                || !"true".equals(attr(elem, "data-pptx-text-flip-compensation"))) {
            return false;
        }
        NodeList nodes = elem.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element child = (Element) nodes.item(i);
            String tag = SvgQualityXml.localName(child).toLowerCase(Locale.ROOT);
            if (!Set.of("g", "text", "tspan", "a").contains(tag)) {
                return false;
            }
        }
        return true;
    }

    private static String svgTextFingerprint(Element root) {
        try {
            List<Map.Entry<String, String>> rootStyle = new ArrayList<>();
            NamedNodeMap attrs = root.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                String name = attrs.item(i).getNodeName();
                if (ROOT_TEXT_STYLE_ATTRS.contains(name)) {
                    rootStyle.add(Map.entry(name, attrs.item(i).getNodeValue()));
                }
            }
            rootStyle.sort(Map.Entry.comparingByKey());
            List<Object> texts = new ArrayList<>();
            NodeList nodes = root.getElementsByTagNameNS("*", "text");
            for (int i = 0; i < nodes.getLength(); i++) {
                texts.add(elementPayload((Element) nodes.item(i)));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("root_style", rootStyle.stream().map(e -> List.of(e.getKey(), e.getValue())).toList());
            payload.put("text", texts);
            String canonical = MAPPER.writeValueAsString(payload);
            // Jackson may insert spaces; pin uses separators=(',', ':'). Rebuild compact:
            canonical = MAPPER.writer().withDefaultPrettyPrinter().writeValueAsString(payload);
            // Prefer compact without pretty:
            canonical = MAPPER.writeValueAsString(payload).replace(" ", "");
            // Actually ObjectMapper default is compact without spaces after : in some configs.
            // Use deterministic rebuild:
            canonical = compactJson(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new PreservedTxBodyException("failed to fingerprint text: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String compactJson(Object value) throws Exception {
        // Use Jackson with no spaces: configure
        return new ObjectMapper()
                .writer()
                .writeValueAsString(value)
                .replace(": ", ":")
                .replace(", ", ",");
    }

    private static Map<String, Object> elementPayload(Element element) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tag", SvgQualityXml.localName(element));
        List<List<String>> attrs = new ArrayList<>();
        NamedNodeMap named = element.getAttributes();
        for (int i = 0; i < named.getLength(); i++) {
            attrs.add(List.of(named.item(i).getNodeName(), named.item(i).getNodeValue()));
        }
        attrs.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        payload.put("attrs", attrs);
        List<Object> children = new ArrayList<>();
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (node instanceof Element child) {
                children.add(elementPayload(child));
            } else if (node instanceof org.w3c.dom.Text text && !text.getData().isEmpty()) {
                children.add(Map.of("text", text.getData()));
            }
        }
        payload.put("children", children);
        return payload;
    }

    private static boolean hasRelationshipAttributes(Element root) {
        NodeList nodes = root.getElementsByTagName("*");
        List<Element> all = new ArrayList<>();
        all.add(root);
        for (int i = 0; i < nodes.getLength(); i++) {
            all.add((Element) nodes.item(i));
        }
        for (Element element : all) {
            NamedNodeMap attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                if (REL_NS.equals(attr.getNamespaceURI())
                        || (attr.getNodeName() != null && attr.getNodeName().contains("r:"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean txbodyHasRunEffects(Element root) {
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element props = (Element) nodes.item(i);
            String local = local(props);
            if (!Set.of("rPr", "defRPr", "endParaRPr").contains(local)) {
                continue;
            }
            NodeList children = props.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (!(child instanceof Element effectContainer)) {
                    continue;
                }
                String tag = local(effectContainer);
                if (Set.of("effectLst", "effectDag", "ln", "blipFill", "gradFill", "pattFill", "grpFill")
                        .contains(tag)) {
                    NodeList effects = effectContainer.getChildNodes();
                    for (int k = 0; k < effects.getLength(); k++) {
                        if (effects.item(k) instanceof Element) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String local(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String tag = element.getTagName();
        int idx = tag.indexOf(':');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }

    private static String attr(Element elem, String name) {
        return elem.hasAttribute(name) ? elem.getAttribute(name) : "";
    }

    private record Decoded(boolean hasRunEffects) {}

    private static final class PreservedTxBodyException extends RuntimeException {
        PreservedTxBodyException(String message) {
            super(message);
        }
    }
}
