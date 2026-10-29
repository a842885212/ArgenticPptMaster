// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.TextMetrics;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Phase-3 text bounds — pin-aligned estimation for explicit single-line text
 * (option A; fixtures fix font-size / x / y to reduce flakiness).
 *
 * <p>Canvas overflow is always blocking. Module overflow blocks only above the
 * pin 5% error ratio. Unresolved geometry is skipped (pin warning residual).</p>
 */
final class TextBoundsQualitySupport {

    private static final Set<String> NON_VISUAL = Set.of("defs", "desc", "metadata", "style", "title");

    private TextBoundsQualitySupport() {}

    static List<String> checkTextBounds(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        Map<Element, Element> parents = SvgQualityXml.parentByElement(document);
        Map<Element, Double> fontSizes = SvgQualityFontSizes.resolve(document);
        SvgQualityBounds.Rect canvas = SvgQualityBounds.parseViewBoxCanvas(root.getAttribute("viewBox"));
        Set<Element> unchangedGroups = unchangedTxbodyGroups(document);
        List<String> errors = new ArrayList<>();

        Map<Element, SvgQualityBounds.Rect> estimatedByElement = new IdentityHashMap<>();
        Set<Element> pageOverflow = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        NodeList texts = document.getElementsByTagNameNS("*", "text");
        for (int i = 0; i < texts.getLength(); i++) {
            Element text = (Element) texts.item(i);
            if (hasAncestor(text, parents, unchangedGroups)) {
                continue;
            }
            if (hasNonVisualAncestor(text, root, parents)) {
                continue;
            }
            if (isHidden(text, parents)) {
                continue;
            }
            String visible = text.getTextContent() == null ? "" : text.getTextContent();
            if (visible.isBlank() || (visible.contains("{{") && visible.contains("}}"))) {
                continue;
            }

            SvgQualityBounds.Rect withHeadroom = estimatedTextBounds(text, parents, fontSizes, true);
            if (withHeadroom != null) {
                estimatedByElement.put(text, withHeadroom);
            }

            if (canvas == null || hasZeroOpacity(text, parents)) {
                continue;
            }
            SvgQualityBounds.Rect pageEstimated = estimatedTextBounds(text, parents, fontSizes, false);
            if (pageEstimated == null) {
                continue;
            }
            Element directChild = text;
            Element parent = parents.get(directChild);
            while (parent != null && parent != root) {
                directChild = parent;
                parent = parents.get(directChild);
            }
            boolean morphStaging = parent == root
                    && "g".equals(SvgQualityXml.localName(directChild).toLowerCase(Locale.ROOT))
                    && "true".equals(attr(directChild, SvgQualityBounds.MORPH_STAGING_ATTR))
                    && SvgQualityBounds.areDisjoint(pageEstimated, canvas);
            if (morphStaging) {
                continue;
            }
            SvgQualityBounds.OverflowMetrics metrics = SvgQualityBounds.overflowMetrics(pageEstimated, canvas);
            if (metrics != null) {
                errors.add(String.format(
                        Locale.ROOT,
                        "%s exceeds the root viewBox on the %s axis: content (%.1f, %.1f)-(%.1f, %.1f), "
                                + "canvas (%.1f, %.1f)-(%.1f, %.1f), overflow horizontal %.1f%%, vertical %.1f%%; "
                                + "move or reflow the text until its estimated bounds stay on-page",
                        textDiagnosticLabel(text),
                        metrics.axes(),
                        pageEstimated.left(),
                        pageEstimated.top(),
                        pageEstimated.right(),
                        pageEstimated.bottom(),
                        canvas.left(),
                        canvas.top(),
                        canvas.right(),
                        canvas.bottom(),
                        metrics.horizontalRatio() * 100.0,
                        metrics.verticalRatio() * 100.0));
                pageOverflow.add(text);
            }
        }

        NodeList rootChildren = root.getChildNodes();
        for (int i = 0; i < rootChildren.getLength(); i++) {
            Node node = rootChildren.item(i);
            if (!(node instanceof Element module)
                    || !"g".equals(SvgQualityXml.localName(module).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (isHidden(module, parents)) {
                continue;
            }
            if (!module.hasAttribute(SvgQualityBounds.BOUNDS_ATTR)) {
                continue;
            }
            SvgQualityBounds.Rect boundary;
            try {
                double[] values =
                        SvgQualityBounds.parsePositiveBounds(module.getAttribute(SvgQualityBounds.BOUNDS_ATTR));
                boundary = SvgQualityBounds.fromXywh(values[0], values[1], values[2], values[3]);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            NodeList moduleTexts = module.getElementsByTagNameNS("*", "text");
            for (int t = 0; t < moduleTexts.getLength(); t++) {
                Element text = (Element) moduleTexts.item(t);
                if (pageOverflow.contains(text)) {
                    continue;
                }
                SvgQualityBounds.Rect estimated = estimatedByElement.get(text);
                if (estimated == null) {
                    continue;
                }
                SvgQualityBounds.OverflowMetrics metrics = SvgQualityBounds.overflowMetrics(estimated, boundary);
                if (metrics != null && SvgQualityBounds.exceedsErrorRatio(metrics)) {
                    errors.add(SvgQualityBounds.formatOverflow(
                            textDiagnosticLabel(text),
                            SvgQualityXml.elementLabel(module) + " " + SvgQualityBounds.BOUNDS_ATTR,
                            estimated,
                            boundary,
                            metrics,
                            "expand the root module bounds into available non-overlapping space; otherwise reflow the text"));
                }
            }
        }
        return errors;
    }

    private static SvgQualityBounds.Rect estimatedTextBounds(
            Element textEl,
            Map<Element, Element> parents,
            Map<Element, Double> fontSizes,
            boolean includeHeadroom) {
        if (hasPositionedTspan(textEl)) {
            return null;
        }
        String text = collectDirectText(textEl);
        if (text.isEmpty()) {
            return null;
        }
        try {
            double x = SvgQualityLength.parseProjectGeometryLength(
                    textEl.hasAttribute("x") ? textEl.getAttribute("x") : "0", "x");
            double y = SvgQualityLength.parseProjectGeometryLength(
                    textEl.hasAttribute("y") ? textEl.getAttribute("y") : "0", "y");
            Double fontSize = fontSizes.get(textEl);
            if (fontSize == null || !Double.isFinite(fontSize) || fontSize <= 0) {
                return null;
            }
            String weight = SvgQualityXml.presentationValue(textEl, "font-weight", parents);
            if (weight == null) {
                weight = "normal";
            }
            double width = includeHeadroom
                    ? TextMetrics.estimateRunWidthWithHeadroom(text, fontSize, weight)
                    : TextMetrics.estimateTextWidth(text, fontSize, weight);
            if (!Double.isFinite(width) || width <= 0) {
                return null;
            }
            String rawAnchor = SvgQualityXml.presentationValue(textEl, "text-anchor", parents);
            String anchor = rawAnchor == null ? "start" : rawAnchor.strip().toLowerCase(Locale.ROOT);
            double left;
            double right;
            switch (anchor) {
                case "middle" -> {
                    left = x - width / 2;
                    right = x + width / 2;
                }
                case "end" -> {
                    left = x - width;
                    right = x;
                }
                case "start" -> {
                    left = x;
                    right = x + width;
                }
                default -> {
                    return null;
                }
            }
            double ascent = fontSize * 0.85;
            double descent = fontSize * 0.35;
            SvgQualityBounds.Rect local =
                    new SvgQualityBounds.Rect(left, y - ascent, right, y + descent);
            return SvgQualityAffine.transformedRectBounds(textEl, local, parents);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasPositionedTspan(Element textEl) {
        NodeList children = textEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (!"tspan".equals(SvgQualityXml.localName(child).toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (child.hasAttribute("x")
                    || child.hasAttribute("y")
                    || child.hasAttribute("dx")
                    || child.hasAttribute("dy")) {
                return true;
            }
        }
        return false;
    }

    private static String collectDirectText(Element textEl) {
        StringBuilder sb = new StringBuilder();
        NodeList children = textEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof org.w3c.dom.Text text) {
                sb.append(text.getData());
            } else if (node instanceof Element child
                    && "tspan".equals(SvgQualityXml.localName(child).toLowerCase(Locale.ROOT))) {
                sb.append(child.getTextContent() == null ? "" : child.getTextContent());
            }
        }
        return sb.toString();
    }

    private static Set<Element> unchangedTxbodyGroups(Document document) {
        Set<Element> groups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        NodeList all = document.getElementsByTagNameNS("*", "g");
        for (int i = 0; i < all.getLength(); i++) {
            Element group = (Element) all.item(i);
            NodeList children = group.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node node = children.item(j);
                if (node instanceof Element child
                        && "metadata".equals(SvgQualityXml.localName(child).toLowerCase(Locale.ROOT))
                        && "txbody".equals(attr(child, "data-pptx-part"))) {
                    groups.add(group);
                    break;
                }
            }
        }
        return groups;
    }

    private static boolean hasAncestor(Element elem, Map<Element, Element> parents, Set<Element> ancestors) {
        Element current = parents.get(elem);
        while (current != null) {
            if (ancestors.contains(current)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private static boolean hasNonVisualAncestor(Element elem, Element root, Map<Element, Element> parents) {
        Element current = elem;
        while (current != null && current != root) {
            if (NON_VISUAL.contains(SvgQualityXml.localName(current).toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private static boolean isHidden(Element element, Map<Element, Element> parents) {
        Element current = element;
        while (current != null) {
            String display = SvgQualityXml.presentationValue(current, "display", parents);
            if ("none".equalsIgnoreCase(display)) {
                return true;
            }
            String visibility = SvgQualityXml.presentationValue(current, "visibility", parents);
            if ("hidden".equalsIgnoreCase(visibility) || "collapse".equalsIgnoreCase(visibility)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private static boolean hasZeroOpacity(Element element, Map<Element, Element> parents) {
        Element current = element;
        while (current != null) {
            String opacity = SvgQualityXml.presentationValue(current, "opacity", parents);
            if (opacity != null) {
                try {
                    if (SvgQualityLength.parseProjectOpacity(opacity, false) <= 0) {
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // ignore
                }
            }
            current = parents.get(current);
        }
        return false;
    }

    private static String textDiagnosticLabel(Element text) {
        return SvgQualityXml.elementLabel(text);
    }

    private static String attr(Element elem, String name) {
        return elem.hasAttribute(name) ? elem.getAttribute(name) : "";
    }
}
