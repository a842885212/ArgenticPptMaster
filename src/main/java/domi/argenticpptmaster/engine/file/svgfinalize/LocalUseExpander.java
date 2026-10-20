// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Materialize same-document {@code <use href="#id">} references.
 *
 * <p>Port of {@code svg_to_pptx/use_expander.py} local-use expansion.</p>
 */
public final class LocalUseExpander {

    private static final Pattern LOCAL_HREF = Pattern.compile("^#([^#\\s]+)$");
    private static final Pattern LENGTH = Pattern.compile(
            "^\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(?:px)?\\s*$");
    private static final Pattern VIEWBOX_SPLIT = Pattern.compile("[\\s,]+");
    private static final Pattern URL_REF = Pattern.compile("url\\(#([^#\\s()'\"\\\\]+)\\)");
    private static final Pattern URL_FUNCTION = Pattern.compile("\\burl\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_FUNCTION_START = Pattern.compile("\\burl\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final int MAX_DEPTH = 64;
    private static final int MAX_INSTANCES = 10_000;

    private static final String[] NON_REUSABLE_METADATA_PREFIXES = {
        "data-pptx-authoring",
        "data-pptx-object",
        "data-pptx-prst",
        "data-pptx-frame",
        "data-pptx-av-",
        "data-pptx-layer",
        "data-pptx-replace-with",
        "data-pptx-replacement-",
        "data-pptx-import-source",
        "data-pptx-fallback-",
        "data-pptx-native",
        "data-pptx-visual-status",
        "data-pptx-route-status",
        "data-pptx-placeholder"
    };

    private static final Set<String> REFERENCE_TAGS = Set.of(
            "symbol", "g", "use",
            "rect", "circle", "ellipse", "line", "path", "polygon", "polyline",
            "text", "image");

    private LocalUseExpander() {}

    public static int expandInFile(Path svgPath) throws Exception {
        Document document = SvgFinalizeXml.parse(svgPath);
        int count = expand(document);
        if (count > 0) {
            SvgFinalizeXml.write(document, svgPath);
        }
        return count;
    }

    public static int expand(Document document) {
        return new Expander(document).expand();
    }

    private static final class Expander {
        private final Document document;
        private final Element root;
        private final Map<String, Element> targets = new LinkedHashMap<>();
        private final Set<String> duplicateIds = new HashSet<>();
        private final Set<String> usedIds = new HashSet<>();
        private int instanceIndex;
        private int instancesStarted;
        private int expanded;

        Expander(Document document) {
            this.document = document;
            this.root = document.getDocumentElement();
            for (Element elem : allElements(root)) {
                String id = attr(elem, "id");
                if (id == null || id.isEmpty()) {
                    continue;
                }
                usedIds.add(id);
                if (targets.containsKey(id)) {
                    duplicateIds.add(id);
                } else {
                    targets.put(id, elem);
                }
            }
        }

        int expand() {
            expandChildren(root, List.of());
            return expanded;
        }

        private void expandChildren(Element parent, List<String> stack) {
            List<Element> children = SvgFinalizeXml.childElements(parent);
            for (int index = 0; index < children.size(); index++) {
                Element child = children.get(index);
                if (SvgFinalizeXml.isSvgTag(child, "defs")) {
                    continue;
                }
                if (SvgFinalizeXml.isSvgTag(child, "use") && !child.hasAttribute("data-icon")) {
                    Element replacement = materializeUse(child, stack);
                    parent.replaceChild(replacement, child);
                    continue;
                }
                expandChildren(child, stack);
            }
        }

        private Element materializeUse(Element useElem, List<String> stack) {
            instancesStarted++;
            if (instancesStarted > MAX_INSTANCES) {
                throw new UseExpansionException(
                        "Local <use> expansion exceeds the 10000-instance safety limit");
            }
            String href = SvgFinalizeXml.href(useElem);
            String hrefAttr = useElem.hasAttribute("href") ? useElem.getAttribute("href") : null;
            String xlinkHref = useElem.hasAttributeNS(SvgFinalizeXml.XLINK_NS, "href")
                    ? useElem.getAttributeNS(SvgFinalizeXml.XLINK_NS, "href")
                    : null;
            if (hrefAttr != null && xlinkHref != null && !hrefAttr.equals(xlinkHref)) {
                throw new UseExpansionException("Conflicting href and xlink:href values on local <use>");
            }
            Matcher match = LOCAL_HREF.matcher(href == null ? "" : href);
            if (!match.matches()) {
                throw new UseExpansionException(
                        "<use> must reference a same-document fragment with href=\"#id\"; got '"
                                + href + "'");
            }
            String refId = match.group(1);
            if (stack.size() >= MAX_DEPTH) {
                throw new UseExpansionException(
                        "Local <use> expansion exceeds the 64-reference depth limit: "
                                + String.join(" -> ", concat(stack, refId)));
            }
            if (duplicateIds.contains(refId)) {
                throw new UseExpansionException(
                        "<use href=\"#" + refId + "\"> is ambiguous because the id is duplicated");
            }
            String useId = attr(useElem, "id");
            if (useId != null && duplicateIds.contains(useId)) {
                throw new UseExpansionException(
                        "Local <use> instance id '" + useId + "' is duplicated in this SVG");
            }
            Element target = targets.get(refId);
            if (target == null) {
                throw new UseExpansionException(
                        "<use href=\"#" + refId + "\"> has no matching element in this SVG");
            }
            if (stack.contains(refId)) {
                throw new UseExpansionException(
                        "Circular local <use> reference: " + String.join(" -> ", concat(stack, refId)));
            }
            String targetTag = SvgFinalizeXml.localName(target);
            if (!REFERENCE_TAGS.contains(targetTag)) {
                throw new UseExpansionException(
                        "<use href=\"#" + refId + "\"> references unsupported <" + targetTag + ">");
            }
            rejectStructuralMetadata(useElem, "instance");
            rejectStructuralMetadata(target, "target #" + refId);
            validateFragmentReferenceSyntax(useElem, "instance");
            validateFragmentReferenceSyntax(target, "target #" + refId);

            Set<String> targetIds = new HashSet<>();
            for (Element elem : allElements(target)) {
                String id = attr(elem, "id");
                if (id != null) {
                    targetIds.add(id);
                }
            }
            List<String> ambiguous = new ArrayList<>();
            for (String id : targetIds) {
                if (duplicateIds.contains(id)) {
                    ambiguous.add(id);
                }
            }
            ambiguous.sort(String::compareTo);
            if (!ambiguous.isEmpty()) {
                throw new UseExpansionException(
                        "<use href=\"#" + refId + "\"> references a subtree with duplicate id(s): "
                                + String.join(", ", ambiguous));
            }

            List<String> nextStack = concat(stack, refId);
            Element clone;
            if ("use".equals(targetTag)) {
                clone = materializeUse((Element) target.cloneNode(true), nextStack);
            } else {
                clone = (Element) target.cloneNode(true);
                if ("symbol".equals(targetTag)) {
                    Element group = SvgFinalizeXml.createSvgElement(document, "g");
                    copyAttributes(clone, group, Set.of("viewBox", "preserveAspectRatio", "x", "y", "width", "height"));
                    String viewport = symbolViewportTransform(target, useElem);
                    String existing = attr(group, "transform");
                    if (existing != null && !existing.isBlank()) {
                        group.setAttribute("transform", existing.strip() + " " + viewport);
                    } else {
                        group.setAttribute("transform", viewport);
                    }
                    while (clone.hasChildNodes()) {
                        group.appendChild(clone.getFirstChild());
                    }
                    clone = group;
                }
                expandChildren(clone, nextStack);
            }

            rewriteCloneIds(clone, nextInstancePrefix(clone));
            Element wrapper = buildWrapper(useElem);
            wrapper.appendChild(document.importNode(clone, true));
            expanded++;
            return wrapper;
        }

        private void rejectStructuralMetadata(Element elem, String label) {
            for (Element candidate : allElements(elem)) {
                NamedNodeMap attrs = candidate.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    String name = attrs.item(i).getNodeName();
                    for (String prefix : NON_REUSABLE_METADATA_PREFIXES) {
                        if (name.startsWith(prefix)) {
                            throw new UseExpansionException(
                                    "Local <use> " + label + " cannot carry structural " + name + " metadata");
                        }
                    }
                }
            }
        }

        private void validateFragmentReferenceSyntax(Element elem, String label) {
            for (Element candidate : allElements(elem)) {
                NamedNodeMap attrs = candidate.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    String value = attrs.item(i).getNodeValue();
                    Matcher starts = URL_FUNCTION_START.matcher(value);
                    int startCount = 0;
                    while (starts.find()) {
                        startCount++;
                    }
                    if (startCount == 0) {
                        continue;
                    }
                    Matcher functions = URL_FUNCTION.matcher(value);
                    int functionCount = 0;
                    List<String> rawFunctions = new ArrayList<>();
                    while (functions.find()) {
                        functionCount++;
                        rawFunctions.add(functions.group());
                    }
                    if (functionCount != startCount) {
                        throw new UseExpansionException(
                                "Local <use> " + label + " has malformed url(...) reference '" + value + "'");
                    }
                    for (String raw : rawFunctions) {
                        Matcher ref = URL_REF.matcher(raw);
                        if (!ref.matches()) {
                            throw new UseExpansionException(
                                    "Local <use> " + label + " requires exact url(#id) fragments; got '"
                                            + raw + "'");
                        }
                        String refId = ref.group(1);
                        if (duplicateIds.contains(refId)) {
                            throw new UseExpansionException(
                                    "Local <use> " + label + " has ambiguous url(#" + refId
                                            + "); the referenced id is duplicated");
                        }
                        if (!targets.containsKey(refId)) {
                            throw new UseExpansionException(
                                    "Local <use> " + label + " has unresolved url(#" + refId + ")");
                        }
                    }
                }
            }
        }

        private String nextInstancePrefix(Element clone) {
            Set<String> cloneIds = new HashSet<>();
            for (Element elem : allElements(clone)) {
                String id = attr(elem, "id");
                if (id != null) {
                    cloneIds.add(id);
                }
            }
            while (true) {
                instanceIndex++;
                String prefix = "use-instance-" + instanceIndex + "-";
                Set<String> generated = new HashSet<>();
                boolean collision = false;
                for (String id : cloneIds) {
                    String gen = prefix + id;
                    generated.add(gen);
                    if (usedIds.contains(gen)) {
                        collision = true;
                        break;
                    }
                }
                if (!collision) {
                    usedIds.addAll(generated);
                    return prefix;
                }
            }
        }

        private void rewriteCloneIds(Element clone, String prefix) {
            Map<String, String> idMap = new HashMap<>();
            for (Element elem : allElements(clone)) {
                String id = attr(elem, "id");
                if (id != null) {
                    idMap.put(id, prefix + id);
                }
            }
            if (idMap.isEmpty()) {
                return;
            }
            for (Element elem : allElements(clone)) {
                String id = attr(elem, "id");
                if (id != null && idMap.containsKey(id)) {
                    elem.setAttribute("id", idMap.get(id));
                }
                NamedNodeMap attrs = elem.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node attrNode = attrs.item(i);
                    String name = attrNode.getNodeName();
                    String value = attrNode.getNodeValue();
                    if (("href".equals(name) || "xlink:href".equals(name) || name.endsWith(":href"))
                            && value.startsWith("#")) {
                        String refId = value.substring(1);
                        if (idMap.containsKey(refId)) {
                            attrNode.setNodeValue("#" + idMap.get(refId));
                        }
                        continue;
                    }
                    Matcher matcher = URL_REF.matcher(value);
                    StringBuffer sb = new StringBuffer();
                    boolean changed = false;
                    while (matcher.find()) {
                        String mapped = idMap.getOrDefault(matcher.group(1), matcher.group(1));
                        matcher.appendReplacement(sb, Matcher.quoteReplacement("url(#" + mapped + ")"));
                        changed = true;
                    }
                    if (changed) {
                        matcher.appendTail(sb);
                        attrNode.setNodeValue(sb.toString());
                    }
                }
            }
        }

        private Element buildWrapper(Element useElem) {
            Element wrapper = SvgFinalizeXml.createSvgElement(document, "g");
            Set<String> skipped = Set.of(
                    "href", "xlink:href", "x", "y", "width", "height", "preserveAspectRatio", "transform");
            NamedNodeMap attrs = useElem.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                String name = attr.getNodeName();
                String local = attr.getLocalName() != null ? attr.getLocalName() : name;
                if (skipped.contains(name) || skipped.contains(local)) {
                    continue;
                }
                if (attr.getNamespaceURI() != null) {
                    wrapper.setAttributeNS(attr.getNamespaceURI(), name, attr.getNodeValue());
                } else {
                    wrapper.setAttribute(name, attr.getNodeValue());
                }
            }
            double x = numericLength(useElem, "x", 0.0);
            double y = numericLength(useElem, "y", 0.0);
            List<String> transforms = new ArrayList<>();
            String useTransform = attr(useElem, "transform");
            if (useTransform != null && !useTransform.isBlank()) {
                transforms.add(useTransform.strip());
            }
            if (x != 0 || y != 0) {
                transforms.add("translate(" + fmtNumber(x) + " " + fmtNumber(y) + ")");
            }
            if (!transforms.isEmpty()) {
                wrapper.setAttribute("transform", String.join(" ", transforms));
            }
            return wrapper;
        }
    }

    private static String symbolViewportTransform(Element symbol, Element useElem) {
        if (attr(symbol, "refX") != null || attr(symbol, "refY") != null) {
            throw new UseExpansionException(
                    "<symbol> refX/refY is not supported by local <use> expansion");
        }
        double[] viewBox = parseViewBox(symbol);
        Double width = numericLengthOrNull(useElem, "width");
        Double height = numericLengthOrNull(useElem, "height");
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new UseExpansionException(
                    "<use> referencing <symbol> requires positive numeric width and height");
        }
        String rawAspect = attr(symbol, "preserveAspectRatio");
        if (rawAspect == null || rawAspect.isBlank()) {
            rawAspect = "xMidYMid meet";
        } else {
            rawAspect = rawAspect.strip();
        }
        String[] parts = rawAspect.split("\\s+");
        List<String> partList = new ArrayList<>(List.of(parts));
        if (!partList.isEmpty() && "defer".equals(partList.get(0))) {
            partList.remove(0);
        }
        String align = partList.isEmpty() ? "xMidYMid" : partList.get(0);
        String mode = partList.size() > 1 ? partList.get(1) : "meet";
        if (partList.size() > 2 || (!"meet".equals(mode) && !"slice".equals(mode) && !"none".equals(align))) {
            if (!"none".equals(align) && !"meet".equals(mode) && !"slice".equals(mode)) {
                throw new UseExpansionException("Unsupported symbol preserveAspectRatio: '" + rawAspect + "'");
            }
        }
        if ("none".equals(align)) {
            if (partList.size() > 1) {
                throw new UseExpansionException("Unsupported symbol preserveAspectRatio: '" + rawAspect + "'");
            }
            double scaleX = width / viewBox[2];
            double scaleY = height / viewBox[3];
            double translateX = -viewBox[0] * scaleX;
            double translateY = -viewBox[1] * scaleY;
            return "matrix(" + fmtNumber(scaleX) + " 0 0 " + fmtNumber(scaleY) + " "
                    + fmtNumber(translateX) + " " + fmtNumber(translateY) + ")";
        }
        if (partList.size() > 2 || (!"meet".equals(mode) && !"slice".equals(mode))) {
            throw new UseExpansionException("Unsupported symbol preserveAspectRatio: '" + rawAspect + "'");
        }
        Map<String, double[]> alignments = Map.ofEntries(
                Map.entry("xMinYMin", new double[] {0, 0}),
                Map.entry("xMidYMin", new double[] {0.5, 0}),
                Map.entry("xMaxYMin", new double[] {1, 0}),
                Map.entry("xMinYMid", new double[] {0, 0.5}),
                Map.entry("xMidYMid", new double[] {0.5, 0.5}),
                Map.entry("xMaxYMid", new double[] {1, 0.5}),
                Map.entry("xMinYMax", new double[] {0, 1}),
                Map.entry("xMidYMax", new double[] {0.5, 1}),
                Map.entry("xMaxYMax", new double[] {1, 1}));
        if (!alignments.containsKey(align)) {
            throw new UseExpansionException("Unsupported symbol preserveAspectRatio: '" + rawAspect + "'");
        }
        if ("slice".equals(mode)) {
            throw new UseExpansionException(
                    "symbol preserveAspectRatio=\"... slice\" requires viewport clipping, "
                            + "which local <use> expansion does not approximate");
        }
        double scale = Math.min(width / viewBox[2], height / viewBox[3]);
        double[] alignXY = alignments.get(align);
        double translateX = (width - viewBox[2] * scale) * alignXY[0] - viewBox[0] * scale;
        double translateY = (height - viewBox[3] * scale) * alignXY[1] - viewBox[1] * scale;
        return "matrix(" + fmtNumber(scale) + " 0 0 " + fmtNumber(scale) + " "
                + fmtNumber(translateX) + " " + fmtNumber(translateY) + ")";
    }

    private static double[] parseViewBox(Element symbol) {
        String raw = attr(symbol, "viewBox");
        if (raw == null) {
            raw = "";
        }
        String[] parts = VIEWBOX_SPLIT.split(raw.strip());
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        if (values.size() != 4) {
            throw new UseExpansionException(
                    "<symbol> referenced by <use> must define a four-number viewBox");
        }
        try {
            double minX = Double.parseDouble(values.get(0));
            double minY = Double.parseDouble(values.get(1));
            double width = Double.parseDouble(values.get(2));
            double height = Double.parseDouble(values.get(3));
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new UseExpansionException("<symbol> viewBox must be finite, got '" + raw + "'");
            }
            if (width <= 0 || height <= 0) {
                throw new UseExpansionException(
                        "<symbol> viewBox width/height must be positive, got '" + raw + "'");
            }
            return new double[] {minX, minY, width, height};
        } catch (NumberFormatException ex) {
            throw new UseExpansionException(
                    "<symbol> viewBox contains a non-numeric value: '" + raw + "'", ex);
        }
    }

    private static double numericLength(Element elem, String name, double defaultValue) {
        Double value = numericLengthOrNull(elem, name);
        return value == null ? defaultValue : value;
    }

    private static Double numericLengthOrNull(Element elem, String name) {
        String raw = attr(elem, name);
        if (raw == null) {
            return null;
        }
        Matcher match = LENGTH.matcher(raw);
        if (!match.matches()) {
            throw new UseExpansionException(
                    "<use> " + name + " must be a finite unitless or px value, got '" + raw + "'");
        }
        double value = Double.parseDouble(match.group(1));
        if (!Double.isFinite(value)) {
            throw new UseExpansionException("<use> " + name + " must be finite, got '" + raw + "'");
        }
        return value;
    }

    private static String fmtNumber(double value) {
        if (Math.abs(value) < 1e-12) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.12g", value);
    }

    private static String attr(Element element, String name) {
        if (!element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return value;
    }

    private static void copyAttributes(Element from, Element to, Set<String> skip) {
        NamedNodeMap attrs = from.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name = attr.getNodeName();
            String local = attr.getLocalName() != null ? attr.getLocalName() : name;
            if (skip.contains(name) || skip.contains(local)) {
                continue;
            }
            if (attr.getNamespaceURI() != null) {
                to.setAttributeNS(attr.getNamespaceURI(), name, attr.getNodeValue());
            } else {
                to.setAttribute(name, attr.getNodeValue());
            }
        }
    }

    private static List<Element> allElements(Element root) {
        List<Element> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Element element, List<Element> out) {
        out.add(element);
        for (Element child : SvgFinalizeXml.childElements(element)) {
            walk(child, out);
        }
    }

    private static List<String> concat(List<String> stack, String next) {
        List<String> out = new ArrayList<>(stack);
        out.add(next);
        return out;
    }

    public static final class UseExpansionException extends RuntimeException {
        public UseExpansionException(String message) {
            super(message);
        }

        public UseExpansionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
