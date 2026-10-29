// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Definition / paint-reference / marker contracts (fail-closed subset of pin shared validators).
 */
final class DefinitionPaintMarkerContractQualitySupport {

    private static final Set<String> DEFINITION_TAGS = Set.of(
            "clippath", "filter", "lineargradient", "marker", "pattern", "radialgradient");

    private static final Pattern URL_REF = Pattern.compile("^url\\(#([^)]+)\\)$");

    private static final Set<String> FILL_SHAPE_TAGS =
            Set.of("rect", "circle", "ellipse", "path", "polygon", "polyline");
    private static final Set<String> STROKE_SHAPE_TAGS = Set.of(
            "rect", "circle", "ellipse", "path", "polygon", "polyline", "line");

    private DefinitionPaintMarkerContractQualitySupport() {}

    static List<String> checkDefinitionPaintMarkerContracts(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        errors.addAll(checkDefinitionContract(document));
        errors.addAll(checkPaintReferenceContract(document));
        errors.addAll(checkMarkerContract(document));
        return new ArrayList<>(errors);
    }

    private static List<String> checkDefinitionContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<String, Element> definitions = new HashMap<>();
        Set<String> duplicateDefIds = new HashSet<>();
        Map<Element, Element> parents = SvgQualityXml.parentByElement(document);
        Map<String, Integer> allIdCounts = new HashMap<>();

        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String id = element.getAttribute("id");
            if (id != null && !id.isBlank()) {
                allIdCounts.merge(id.strip(), 1, Integer::sum);
            }
            if (!"defs".equals(SvgQualityXml.localName(element).toLowerCase(Locale.ROOT))) {
                continue;
            }
            NodeList children = element.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node node = children.item(j);
                if (!(node instanceof Element child)) {
                    continue;
                }
                String childId = child.getAttribute("id");
                if (childId == null || childId.isBlank()) {
                    continue;
                }
                String key = childId.strip();
                if (definitions.containsKey(key)) {
                    duplicateDefIds.add(key);
                }
                definitions.put(key, child);
            }
        }
        for (String id : duplicateDefIds) {
            errors.add(
                    "Duplicate direct <defs> id '" + id + "' makes local references ambiguous");
        }
        for (String id : definitions.keySet()) {
            if (allIdCounts.getOrDefault(id, 0) > 1) {
                errors.add(
                        "Definition id '" + id + "' is duplicated in the SVG; "
                                + "local references require one unique target");
            }
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element);
            String tagLower = tag.toLowerCase(Locale.ROOT);
            if (!DEFINITION_TAGS.contains(tagLower)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            Element parent = parents.get(element);
            if (parent == null
                    || !"defs".equals(SvgQualityXml.localName(parent).toLowerCase(Locale.ROOT))) {
                errors.add(label + " must be a direct child of <defs>");
            }
            String id = element.getAttribute("id");
            if (id == null || id.isBlank()) {
                errors.add(label + " requires a non-empty unique id");
            }
        }
        return new ArrayList<>(errors);
    }

    private static List<String> checkPaintReferenceContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<String, Element> definitions = definitionIndex(document);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            for (String property : SvgQualityPaint.REFERENCE_PAINT_PROPERTIES) {
                String raw = style.containsKey(property) ? style.get(property) : null;
                if (raw == null && element.hasAttribute(property)) {
                    raw = element.getAttribute(property);
                }
                if (raw == null) {
                    continue;
                }
                SvgQualityPaint.PaintParse parsed;
                try {
                    parsed = SvgQualityPaint.parseProjectPaint(raw, property);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (!"reference".equals(parsed.kind()) || parsed.value() == null) {
                    continue;
                }
                String tag = SvgQualityXml.localName(element);
                String tagLower = tag.toLowerCase(Locale.ROOT);
                Element target = definitions.get(parsed.value());
                if (target == null) {
                    errors.add(
                            "<" + tag + "> " + property + "=url(#" + parsed.value()
                                    + ") has no matching direct <defs> definition");
                    continue;
                }
                Set<String> allowed;
                if ("fill".equals(property) && FILL_SHAPE_TAGS.contains(tagLower)) {
                    allowed = Set.of("lineargradient", "radialgradient", "pattern");
                } else if ("stroke".equals(property) && STROKE_SHAPE_TAGS.contains(tagLower)) {
                    allowed = Set.of("lineargradient", "radialgradient");
                } else if ("fill".equals(property) && ("text".equals(tagLower) || "tspan".equals(tagLower))) {
                    allowed = Set.of("lineargradient", "radialgradient", "pattern");
                } else if ("fill".equals(property) && "g".equals(tagLower)) {
                    allowed = Set.of("lineargradient", "radialgradient", "pattern");
                } else if ("stroke".equals(property) && "g".equals(tagLower)) {
                    allowed = Set.of("lineargradient", "radialgradient");
                } else {
                    errors.add(
                            "<" + tag + "> " + property + "=url(#" + parsed.value()
                                    + ") is not supported by native PPTX conversion in this context");
                    continue;
                }
                String targetTag = SvgQualityXml.localName(target).toLowerCase(Locale.ROOT);
                if (!allowed.contains(targetTag)) {
                    errors.add(
                            "<" + tag + "> " + property + "=url(#" + parsed.value()
                                    + ") resolves to <" + SvgQualityXml.localName(target)
                                    + ">; expected a supported paint server");
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static List<String> checkMarkerContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<String, Element> definitions = definitionIndex(document);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            for (String attribute : List.of("marker-start", "marker-end")) {
                if (!element.hasAttribute(attribute)) {
                    continue;
                }
                String raw = element.getAttribute(attribute);
                if (raw == null || raw.strip().equalsIgnoreCase("none")) {
                    continue;
                }
                String label = SvgQualityXml.elementLabel(element);
                String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
                if (!"line".equals(tag) && !"path".equals(tag)) {
                    errors.add(label + " " + attribute + " is allowed only on <line> or <path>");
                }
                Matcher match = URL_REF.matcher(raw.strip());
                if (!match.matches()) {
                    errors.add(
                            label + " " + attribute
                                    + " must be an exact local url(#id) reference; got '" + raw + "'");
                    continue;
                }
                String markerId = match.group(1);
                Element marker = definitions.get(markerId);
                if (marker == null
                        || !"marker".equals(SvgQualityXml.localName(marker).toLowerCase(Locale.ROOT))) {
                    errors.add(
                            label + " " + attribute + "=url(#" + markerId
                                    + ") has no matching direct <defs><marker id=\"" + markerId
                                    + "\"> definition");
                    continue;
                }
                String orient = marker.getAttribute("orient");
                if (orient != null
                        && !orient.isBlank()
                        && !"auto".equals(orient)
                        && !"auto-start-reverse".equals(orient)) {
                    errors.add(
                            "<marker id=\"" + markerId + "\"> requires orient=\"auto\" or "
                                    + "orient=\"auto-start-reverse\"");
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static Map<String, Element> definitionIndex(Document document) {
        Map<String, Element> definitions = new HashMap<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element defs)) {
                continue;
            }
            if (!"defs".equals(SvgQualityXml.localName(defs).toLowerCase(Locale.ROOT))) {
                continue;
            }
            NodeList children = defs.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node node = children.item(j);
                if (!(node instanceof Element child)) {
                    continue;
                }
                String id = child.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    definitions.put(id.strip(), child);
                }
            }
        }
        return definitions;
    }
}
