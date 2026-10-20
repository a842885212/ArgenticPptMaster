// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Flatten positioned {@code <tspan>} lines into independent {@code <text>} elements.
 *
 * <p>Port of {@code svg_finalize/flatten_tspan.py} default split mode
 * ({@code merge_paragraphs=false}), as invoked by {@code finalize_svg.py}.</p>
 */
public final class FlattenTspan {

    private static final Set<String> TEXT_STYLE_ATTRS = Set.of(
            "font-family", "font-size", "font-weight", "font-style", "font-variant", "font-stretch",
            "letter-spacing", "word-spacing", "kerning", "text-anchor", "text-decoration",
            "dominant-baseline", "writing-mode", "direction",
            "fill", "fill-opacity", "stroke", "stroke-width", "stroke-opacity", "opacity", "paint-order",
            "transform", "clip-path", "filter");

    private static final String INLINE_FORMULA_ATTR = "data-pptx-inline-formula";

    private FlattenTspan() {}

    public static boolean processFile(Path svgPath) throws Exception {
        Document document = SvgFinalizeXml.parse(svgPath);
        boolean changed = flatten(document);
        if (changed) {
            SvgFinalizeXml.write(document, svgPath);
        }
        return changed;
    }

    public static boolean flatten(Document document) {
        Element root = document.getDocumentElement();
        List<String> positionalErrors = nestedPositionalTspanErrors(root);
        if (!positionalErrors.isEmpty()) {
            String preview = String.join("; ", positionalErrors.subList(0, Math.min(3, positionalErrors.size())));
            String suffix = positionalErrors.size() > 3
                    ? "; +" + (positionalErrors.size() - 3) + " more"
                    : "";
            throw new IllegalArgumentException("Unsupported nested positional <tspan>: " + preview + suffix);
        }
        return flattenPass(document);
    }

    private static boolean flattenPass(Document document) {
        Element root = document.getDocumentElement();
        Map<Element, Element> parents = SvgFinalizeXml.parentMap(document);
        List<Element> candidates = new ArrayList<>();
        for (Element el : SvgFinalizeXml.descendants(root, "text")) {
            for (Element child : SvgFinalizeXml.childElements(el)) {
                if (SvgFinalizeXml.isSvgTag(child, "tspan")) {
                    candidates.add(el);
                    break;
                }
            }
        }
        boolean changed = false;
        for (Element textEl : candidates) {
            Element parent = parents.get(textEl);
            if (parent == null) {
                continue;
            }
            boolean needsFlatten = false;
            for (Element child : SvgFinalizeXml.childElements(textEl)) {
                if (SvgFinalizeXml.isSvgTag(child, "tspan") && isNewLineTspan(child)) {
                    needsFlatten = true;
                    break;
                }
            }
            if (!needsFlatten) {
                continue;
            }

            Double parsedX = SvgFinalizeXml.parseFirstNumber(attr(textEl, "x"));
            Double parsedY = SvgFinalizeXml.parseFirstNumber(attr(textEl, "y"));
            double curX = parsedX != null ? parsedX : 0.0;
            double curY = parsedY != null ? parsedY : 0.0;

            List<Element> newTexts = new ArrayList<>();
            List<Element> currentLineTspans = new ArrayList<>();
            String currentLineLeadText = leadingText(textEl);

            for (Element child : SvgFinalizeXml.childElements(textEl)) {
                if (!SvgFinalizeXml.isSvgTag(child, "tspan")) {
                    continue;
                }
                String content = SvgFinalizeXml.collectTextContent(child);
                if (isNewLineTspan(child)) {
                    if (!currentLineTspans.isEmpty() || hasNonXmlWhitespace(currentLineLeadText)) {
                        newTexts.add(createTextElementFromLine(
                                document, textEl, currentLineLeadText, currentLineTspans, curX, curY));
                        currentLineTspans = new ArrayList<>();
                        currentLineLeadText = null;
                    }
                    double[] pos = computeLinePositions(child, curX, curY);
                    curX = pos[0];
                    curY = pos[1];
                }
                String tail = elementTail(child);
                if (!content.isEmpty() || (tail != null && !tail.isEmpty())) {
                    currentLineTspans.add(child);
                }
            }
            if (!currentLineTspans.isEmpty() || hasNonXmlWhitespace(currentLineLeadText)) {
                newTexts.add(createTextElementFromLine(
                        document, textEl, currentLineLeadText, currentLineTspans, curX, curY));
            }

            if (newTexts.isEmpty()) {
                continue;
            }
            for (Element ne : newTexts) {
                parent.insertBefore(ne, textEl);
            }
            parent.removeChild(textEl);
            changed = true;
        }
        return changed;
    }

    private static List<String> nestedPositionalTspanErrors(Element root) {
        List<String> errors = new ArrayList<>();
        for (Element textEl : SvgFinalizeXml.descendants(root, "text")) {
            String textLabel = attr(textEl, "id") != null
                    ? "<text id='" + attr(textEl, "id") + "'>"
                    : "<text>";
            for (Element directChild : SvgFinalizeXml.childElements(textEl)) {
                if (!SvgFinalizeXml.isSvgTag(directChild, "tspan")) {
                    continue;
                }
                for (Element descendant : SvgFinalizeXml.descendants(directChild, "tspan")) {
                    if (descendant == directChild) {
                        continue;
                    }
                    String attribute = positionalTspanAttribute(descendant);
                    if (attribute == null) {
                        continue;
                    }
                    errors.add(textLabel + " contains a nested <tspan> with " + attribute
                            + "; move x/y/non-zero dy to a direct child of <text>");
                }
            }
        }
        return errors;
    }

    private static String positionalTspanAttribute(Element tspan) {
        if (attr(tspan, "x") != null) {
            return "x";
        }
        if (attr(tspan, "y") != null) {
            return "y";
        }
        Double dy = SvgFinalizeXml.parseFirstNumber(attr(tspan, "dy"));
        if (dy != null && Math.abs(dy) > 1e-6) {
            return "dy";
        }
        return null;
    }

    private static boolean isNewLineTspan(Element tspan) {
        String dyAttr = attr(tspan, "dy");
        String yAttr = attr(tspan, "y");
        String xAttr = attr(tspan, "x");
        Double dyVal = dyAttr != null ? SvgFinalizeXml.parseFirstNumber(dyAttr) : null;
        return yAttr != null || (dyVal != null && dyVal != 0) || xAttr != null;
    }

    private static double[] computeLinePositions(Element tspanEl, double curX, double curY) {
        String tX = attr(tspanEl, "x");
        String tY = attr(tspanEl, "y");
        String tDx = attr(tspanEl, "dx");
        String tDy = attr(tspanEl, "dy");
        Double nx = tX != null ? SvgFinalizeXml.parseFirstNumber(tX) : curX;
        if (tDx != null) {
            Double dxParsed = SvgFinalizeXml.parseFirstNumber(tDx);
            double dx = dxParsed != null ? dxParsed : 0.0;
            nx = (nx != null ? nx : 0.0) + dx;
        }
        Double ny = tY != null ? SvgFinalizeXml.parseFirstNumber(tY) : curY;
        if (tDy != null) {
            Double dyParsed = SvgFinalizeXml.parseFirstNumber(tDy);
            double dy = dyParsed != null ? dyParsed : 0.0;
            ny = (ny != null ? ny : 0.0) + dy;
        }
        return new double[] {nx != null ? nx : 0.0, ny != null ? ny : 0.0};
    }

    private static Element createTextElementFromLine(
            Document document,
            Element textEl,
            String leadText,
            List<Element> tspans,
            double x,
            double y) {
        Element ne = SvgFinalizeXml.createSvgElement(document, "text");
        copyTextAttrs(textEl, ne, Set.of("x", "y"));
        ne.setAttribute("x", SvgFinalizeXml.formatNumber(x));
        ne.setAttribute("y", SvgFinalizeXml.formatNumber(y));
        String pTf = attr(textEl, "transform");
        if (pTf != null) {
            ne.setAttribute("transform", pTf);
        }

        Element first = tspans.size() == 1 ? tspans.get(0) : null;
        // Python: `not lead_text` — any non-null string including whitespace skips compact mode.
        if (leadText == null
                && first != null
                && !hasTspanChildren(first)
                && (elementTail(first) == null || elementTail(first).isEmpty())
                && attr(first, INLINE_FORMULA_ATTR) == null
                && !declaresBaselineShift(first)) {
            String content = SvgFinalizeXml.collectTextContent(first);
            String xmlSpace = first.getAttributeNS(SvgFinalizeXml.XML_NS, "space");
            if (xmlSpace != null && !xmlSpace.isEmpty()) {
                ne.setAttributeNS(SvgFinalizeXml.XML_NS, "xml:space", xmlSpace);
            }
            String merged = mergeStyles(attr(textEl, "style"), attr(first, "style"));
            if (merged != null && !merged.isEmpty()) {
                ne.setAttribute("style", merged);
            }
            for (String attrName : TEXT_STYLE_ATTRS) {
                String cv = attr(first, attrName);
                if (cv != null) {
                    ne.setAttribute(attrName, cv);
                }
            }
            String cTf = attr(first, "transform");
            if (pTf != null && cTf != null) {
                ne.setAttribute("transform", pTf + " " + cTf);
            } else if (cTf != null) {
                ne.setAttribute("transform", cTf);
            }
            ne.setTextContent(content);
        } else {
            if (leadText != null) {
                ne.appendChild(document.createTextNode(leadText));
            }
            for (Element tspan : tspans) {
                Element copied = copyInlineTspan(document, tspan, true);
                ne.appendChild(copied);
                String tail = elementTail(tspan);
                if (tail != null) {
                    // ElementTree keeps tail on the element; DOM models it as next sibling.
                    // Attach as trailing text after the copied tspan.
                    copied.setAttributeNS(null, "data-tmp-ignore", "0");
                    copied.removeAttribute("data-tmp-ignore");
                    ne.appendChild(document.createTextNode(tail));
                }
            }
        }
        return ne;
    }

    private static Element copyInlineTspan(Document document, Element src, boolean stripLineAttrs) {
        String local = SvgFinalizeXml.localName(src);
        if (!"a".equals(local) && !"tspan".equals(local)) {
            throw new IllegalArgumentException("Unsupported inline text child <" + local + ">");
        }
        Element neu = SvgFinalizeXml.createSvgElement(document, local);
        boolean consumedDx = "tspan".equals(local)
                && stripLineAttrs
                && positionalTspanAttribute(src) != null;
        var attrs = src.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attrNode = attrs.item(i);
            String name = attrNode.getLocalName() != null ? attrNode.getLocalName() : attrNode.getNodeName();
            if (stripLineAttrs && ("x".equals(name) || "y".equals(name) || "dy".equals(name))) {
                continue;
            }
            if ("dx".equals(name) && consumedDx) {
                continue;
            }
            if (attrNode.getNamespaceURI() != null) {
                neu.setAttributeNS(attrNode.getNamespaceURI(), attrNode.getNodeName(), attrNode.getNodeValue());
            } else {
                neu.setAttribute(name, attrNode.getNodeValue());
            }
        }
        for (Node child = src.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                neu.appendChild(document.createTextNode(child.getNodeValue()));
            } else if (child instanceof Element childEl) {
                String childLocal = SvgFinalizeXml.localName(childEl);
                if ("a".equals(childLocal) || "tspan".equals(childLocal)) {
                    neu.appendChild(copyInlineTspan(document, childEl, false));
                }
            }
        }
        return neu;
    }

    private static void copyTextAttrs(Element src, Element dst, Set<String> exclude) {
        Set<String> skip = exclude == null ? Set.of() : exclude;
        if (src.hasAttribute("style") && !skip.contains("style")) {
            dst.setAttribute("style", src.getAttribute("style"));
        }
        for (String key : TEXT_STYLE_ATTRS) {
            if (skip.contains(key)) {
                continue;
            }
            if (src.hasAttribute(key)) {
                dst.setAttribute(key, src.getAttribute(key));
            }
        }
        if (src.hasAttributeNS(SvgFinalizeXml.XML_NS, "space")) {
            dst.setAttributeNS(
                    SvgFinalizeXml.XML_NS, "xml:space", src.getAttributeNS(SvgFinalizeXml.XML_NS, "space"));
        }
    }

    private static boolean hasTspanChildren(Element elem) {
        for (Element child : SvgFinalizeXml.childElements(elem)) {
            String local = SvgFinalizeXml.localName(child);
            if ("a".equals(local) || "tspan".equals(local)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresBaselineShift(Element elem) {
        if (attr(elem, "baseline-shift") != null) {
            return true;
        }
        return SvgFinalizeXml.parseStyle(attr(elem, "style")).containsKey("baseline-shift");
    }

    private static String mergeStyles(String parentStyle, String childStyle) {
        Map<String, String> merged = new LinkedHashMap<>(SvgFinalizeXml.parseStyle(parentStyle));
        merged.putAll(SvgFinalizeXml.parseStyle(childStyle));
        return SvgFinalizeXml.styleToString(merged);
    }

    private static boolean hasNonXmlWhitespace(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return true;
            }
        }
        return false;
    }

    private static String leadingText(Element element) {
        Node first = element.getFirstChild();
        if (first != null && first.getNodeType() == Node.TEXT_NODE) {
            return first.getNodeValue();
        }
        return null;
    }

    private static String elementTail(Element element) {
        Node next = element.getNextSibling();
        if (next != null && next.getNodeType() == Node.TEXT_NODE) {
            return next.getNodeValue();
        }
        return null;
    }

    private static String attr(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        return element.getAttribute(name);
    }
}
