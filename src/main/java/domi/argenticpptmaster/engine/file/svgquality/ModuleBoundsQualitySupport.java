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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Phase-3 module bounds contract — pin <strong>error</strong> paths only.
 *
 * <p>Missing bounds without page-role / structure attrs stays pin-warning residual
 * (not blocking). Overlap / invalid bounds / morph staging / module canvas overflow
 * (&gt;5%) are blocking.</p>
 */
final class ModuleBoundsQualitySupport {

    private static final String[] ROOT_STRUCTURE_ATTRS = {
        "data-pptx-master",
        "data-pptx-master-name",
        "data-pptx-layout",
        "data-pptx-layout-name"
    };

    private static final Set<String> STRUCTURAL_ROLES = Set.of(
            "background", "chrome", "decoration", "footer", "header", "logo", "page-number", "watermark");

    private ModuleBoundsQualitySupport() {}

    static List<String> checkModuleBounds(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        Map<Element, Element> parents = SvgQualityXml.parentByElement(document);
        SvgQualityBounds.Rect canvas = SvgQualityBounds.parseViewBoxCanvas(root.getAttribute("viewBox"));
        List<String> errors = new ArrayList<>();

        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!element.hasAttribute(SvgQualityBounds.BOUNDS_ATTR)) {
                continue;
            }
            if (!"g".equals(SvgQualityXml.localName(element).toLowerCase(Locale.ROOT))) {
                errors.add(SvgQualityXml.elementLabel(element) + " " + SvgQualityBounds.BOUNDS_ATTR
                        + " is valid only on <g> layout modules");
            }
        }

        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!element.hasAttribute(SvgQualityBounds.MORPH_STAGING_ATTR)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            String rawStaging = element.getAttribute(SvgQualityBounds.MORPH_STAGING_ATTR);
            if (!"true".equals(rawStaging)) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR
                        + " must equal \"true\"; set the exact value or remove the marker");
                continue;
            }
            if (!"g".equals(SvgQualityXml.localName(element).toLowerCase(Locale.ROOT))) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR
                        + " is valid only on <g>; move it to the enclosing ordinary direct-root group");
                continue;
            }
            if (parents.get(element) != root) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR
                        + " requires a direct-root <g>; move the marked group directly under <svg> or remove the marker");
                continue;
            }
            if (attr(element, "id").isBlank()) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR
                        + " requires a stable non-empty id; add an id to the marked direct-root group");
                continue;
            }
            List<String> incompatible = new ArrayList<>();
            if (element.hasAttribute("data-pptx-layer")) {
                incompatible.add("data-pptx-layer");
            }
            if (element.hasAttribute("data-pptx-placeholder")) {
                incompatible.add("data-pptx-placeholder");
            }
            if (!incompatible.isEmpty()) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR + " cannot be combined with "
                        + String.join(", ", incompatible)
                        + "; use an ordinary Slide-local group or remove the marker");
                continue;
            }
            ResolvedBounds resolved = resolvedRootModuleBounds(element);
            if (resolved == null) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR + " requires valid "
                        + SvgQualityBounds.BOUNDS_ATTR
                        + "; add or fix positive root-coordinate x y width height bounds");
                continue;
            }
            if (canvas == null) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR
                        + " cannot verify an off-canvas endpoint without a valid root viewBox; fix the root viewBox");
                continue;
            }
            if (!SvgQualityBounds.areDisjoint(resolved.bounds(), canvas)) {
                errors.add(label + " " + SvgQualityBounds.MORPH_STAGING_ATTR + " requires wholly off-canvas "
                        + SvgQualityBounds.BOUNDS_ATTR
                        + "; move the full bounds outside the root viewBox or remove the marker from partially visible content");
            }
        }

        boolean requireBounds = root.hasAttribute("data-pptx-page-role")
                || hasAnyRootStructure(root);
        List<Element> rootGroups = directChildGroups(root);
        List<Map.Entry<Element, SvgQualityBounds.Rect>> bounded = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (Element group : rootGroups) {
            if (isHidden(group, parents)) {
                continue;
            }
            if (!group.hasAttribute(SvgQualityBounds.BOUNDS_ATTR)) {
                missing.add(SvgQualityXml.elementLabel(group));
                continue;
            }
            try {
                SvgQualityBounds.parsePositiveBounds(group.getAttribute(SvgQualityBounds.BOUNDS_ATTR));
            } catch (IllegalArgumentException ex) {
                errors.add(SvgQualityXml.elementLabel(group) + " " + SvgQualityBounds.BOUNDS_ATTR + " "
                        + ex.getMessage());
                continue;
            }
            ResolvedBounds resolved = resolvedRootModuleBounds(group);
            if (resolved == null) {
                continue;
            }
            bounded.add(Map.entry(group, resolved.bounds()));
            if (canvas == null) {
                continue;
            }
            if (isOffCanvasMorphGroup(group, canvas)) {
                continue;
            }
            SvgQualityBounds.OverflowMetrics metrics =
                    SvgQualityBounds.overflowMetrics(resolved.bounds(), canvas);
            if (metrics != null && SvgQualityBounds.exceedsErrorRatio(metrics)) {
                errors.add(SvgQualityBounds.formatOverflow(
                        SvgQualityXml.elementLabel(group) + " " + resolved.attribute(),
                        "canvas viewBox",
                        resolved.bounds(),
                        canvas,
                        metrics,
                        "keep the root module subcanvas inside the SVG viewBox"));
            }
        }

        boolean structuredPage = hasAllRootStructure(root);
        for (int i = 0; i < bounded.size(); i++) {
            Element firstGroup = bounded.get(i).getKey();
            SvgQualityBounds.Rect firstBounds = bounded.get(i).getValue();
            if (overlapExempt(firstGroup, structuredPage, canvas)) {
                continue;
            }
            for (int j = i + 1; j < bounded.size(); j++) {
                Element secondGroup = bounded.get(j).getKey();
                SvgQualityBounds.Rect secondBounds = bounded.get(j).getValue();
                if (overlapExempt(secondGroup, structuredPage, canvas)) {
                    continue;
                }
                double[] overlap = SvgQualityBounds.overlapDimensions(firstBounds, secondBounds);
                if (overlap[0] <= SvgQualityBounds.OVERFLOW_TOLERANCE
                        || overlap[1] <= SvgQualityBounds.OVERFLOW_TOLERANCE) {
                    continue;
                }
                errors.add(String.format(
                        Locale.ROOT,
                        "%s %s overlaps %s %s by %.1fpx x %.1fpx; keep ordinary direct-root module zones "
                                + "disjoint beyond the %.0fpx tolerance",
                        SvgQualityXml.elementLabel(firstGroup),
                        SvgQualityBounds.BOUNDS_ATTR,
                        SvgQualityXml.elementLabel(secondGroup),
                        SvgQualityBounds.BOUNDS_ATTR,
                        overlap[0],
                        overlap[1],
                        SvgQualityBounds.OVERFLOW_TOLERANCE));
            }
        }

        if (requireBounds && !missing.isEmpty()) {
            String sample = String.join("; ", missing.subList(0, Math.min(3, missing.size())));
            String suffix = missing.size() <= 3 ? "" : " +" + (missing.size() - 3) + " more";
            errors.add("Detected " + missing.size() + " visible root-level <g> module(s) without explicit "
                    + SvgQualityBounds.BOUNDS_ATTR + " (" + sample + suffix
                    + "); every final-page/template root <g> other than a compact authored-preset atom declares "
                    + "its root-coordinate layout subcanvas even when it also carries native coordinates");
        }

        return errors;
    }

    private static ResolvedBounds resolvedRootModuleBounds(Element group) {
        if (!group.hasAttribute(SvgQualityBounds.BOUNDS_ATTR)) {
            return null;
        }
        try {
            double[] values = SvgQualityBounds.parsePositiveBounds(group.getAttribute(SvgQualityBounds.BOUNDS_ATTR));
            return new ResolvedBounds(
                    SvgQualityBounds.BOUNDS_ATTR,
                    SvgQualityBounds.fromXywh(values[0], values[1], values[2], values[3]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isOffCanvasMorphGroup(Element group, SvgQualityBounds.Rect canvas) {
        if (!"true".equals(attr(group, SvgQualityBounds.MORPH_STAGING_ATTR))) {
            return false;
        }
        ResolvedBounds resolved = resolvedRootModuleBounds(group);
        return resolved != null && SvgQualityBounds.areDisjoint(resolved.bounds(), canvas);
    }

    private static boolean overlapExempt(
            Element group, boolean structuredPage, SvgQualityBounds.Rect canvas) {
        String role = attr(group, "data-pptx-role").strip().toLowerCase(Locale.ROOT);
        if (STRUCTURAL_ROLES.contains(role)) {
            return true;
        }
        if (structuredPage && group.hasAttribute("data-pptx-placeholder")) {
            return true;
        }
        return canvas != null && isOffCanvasMorphGroup(group, canvas);
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

    private static List<Element> directChildGroups(Element root) {
        List<Element> groups = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child
                    && "g".equals(SvgQualityXml.localName(child).toLowerCase(Locale.ROOT))) {
                groups.add(child);
            }
        }
        return groups;
    }

    private static boolean hasAnyRootStructure(Element root) {
        for (String attr : ROOT_STRUCTURE_ATTRS) {
            if (root.hasAttribute(attr) && !root.getAttribute(attr).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllRootStructure(Element root) {
        for (String attrName : ROOT_STRUCTURE_ATTRS) {
            if (!root.hasAttribute(attrName) || root.getAttribute(attrName).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String attr(Element elem, String name) {
        return elem.hasAttribute(name) ? elem.getAttribute(name) : "";
    }

    private record ResolvedBounds(String attribute, SvgQualityBounds.Rect bounds) {}
}
