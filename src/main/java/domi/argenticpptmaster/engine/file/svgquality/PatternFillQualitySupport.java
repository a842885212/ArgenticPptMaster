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

/** Pattern-fill contract errors from pin {@code _check_pattern_fills}. */
final class PatternFillQualitySupport {

    private static final String TEXT_IMAGE_FILL_ATTR = "data-pptx-text-image-fill";
    private static final Pattern URL_REF = Pattern.compile("^url\\(#([^)]+)\\)$");

    private static final Set<String> OOXML_PATTERN_PRESETS = Set.of(
            "pct5", "pct10", "pct20", "pct25", "pct30", "pct40", "pct50", "pct60",
            "pct70", "pct75", "pct80", "pct90",
            "horz", "vert", "ltHorz", "ltVert", "dkHorz", "dkVert",
            "narHorz", "narVert", "dashHorz", "dashVert",
            "cross", "dnDiag", "upDiag", "ltDnDiag", "ltUpDiag", "dkDnDiag",
            "dkUpDiag", "wdDnDiag", "wdUpDiag",
            "dashDnDiag", "dashUpDiag", "diagCross",
            "smCheck", "lgCheck", "smGrid", "lgGrid", "dotGrid", "smConfetti",
            "lgConfetti", "horzBrick", "diagBrick", "solidDmnd", "openDmnd",
            "dotDmnd", "plaid", "sphere", "weave", "wave", "trellis", "zigZag",
            "divot", "shingle");

    private PatternFillQualitySupport() {}

    static List<String> checkPatternFills(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Map<String, Element> definitions = definitionIndex(document);
        Set<String> referenced = new HashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            String fill = style.containsKey("fill") ? style.get("fill") : null;
            if (fill == null && element.hasAttribute("fill")) {
                fill = element.getAttribute("fill");
            }
            if (fill == null) {
                continue;
            }
            Matcher match = URL_REF.matcher(fill.strip());
            if (!match.matches()) {
                continue;
            }
            Element definition = definitions.get(match.group(1));
            if (definition != null
                    && "pattern".equalsIgnoreCase(SvgQualityXml.localName(definition))) {
                referenced.add(match.group(1));
            }
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element pattern)) {
                continue;
            }
            if (!"pattern".equalsIgnoreCase(SvgQualityXml.localName(pattern))) {
                continue;
            }
            String patId = pattern.hasAttribute("id") ? pattern.getAttribute("id") : "<unnamed>";
            if (pattern.hasAttribute(TEXT_IMAGE_FILL_ATTR)) {
                continue;
            }
            String prst = pattern.hasAttribute("data-pptx-pattern")
                    ? pattern.getAttribute("data-pptx-pattern")
                    : null;
            if (referenced.contains(patId) && pattern.hasAttribute("patternTransform")) {
                errors.add(
                        "<pattern id=\"" + patId + "\"> cannot use patternTransform; "
                                + "the native preset mapping does not preserve custom tile transforms");
            }
            if (prst != null && !prst.isBlank() && !OOXML_PATTERN_PRESETS.contains(prst)) {
                errors.add(
                        "<pattern id=\"" + patId + "\"> uses data-pptx-pattern=\"" + prst
                                + "\" which is not in OOXML ST_PresetPatternVal — exported PPTX "
                                + "will fail schema validation ('needs to be repaired'). "
                                + "Use one of: smGrid / lgGrid / dotGrid (grids), "
                                + "ltUpDiag / dkUpDiag / cross / diagCross / weave / plaid / "
                                + "horzBrick (others)");
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
            if (!"defs".equalsIgnoreCase(SvgQualityXml.localName(defs))) {
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
