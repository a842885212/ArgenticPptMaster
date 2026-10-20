// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Authoring-surface property contract from
 * {@code svg_contracts.check_authoring_property_contract}.
 */
final class AuthoringPropertyContractSupport {

    private static final Set<String> SUPPORTED_INLINE_STYLE_PROPERTIES = Set.of(
            "cx", "cy", "fill", "fill-opacity", "filter", "flood-color",
            "flood-opacity", "font-family", "font-size", "font-style", "font-weight",
            "height", "letter-spacing", "opacity", "r", "rx", "ry",
            "shape-rendering", "stop-color", "stop-opacity", "stroke",
            "stroke-dasharray", "stroke-linecap", "stroke-linejoin", "stroke-opacity",
            "stroke-width", "text-anchor", "text-decoration", "vector-effect",
            "width", "x", "y");

    private static final Set<String> BAKE_REQUIRED_VISUAL_PROPERTIES = Set.of(
            "backdrop-filter",
            "isolation",
            "mix-blend-mode");

    private static final Set<String> SHARED_FAIL_CLOSED_STYLE_PROPERTIES = Set.of("mask");

    private AuthoringPropertyContractSupport() {}

    static List<String> checkAuthoringPropertyContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            String style = element.getAttribute("style");
            if (style != null && !style.isBlank()) {
                for (String fragment : style.split(";")) {
                    String trimmed = fragment.strip();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int colon = trimmed.indexOf(':');
                    if (colon < 0) {
                        errors.add(
                                label + " has malformed inline style declaration '" + trimmed + "'");
                        continue;
                    }
                    String name = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                    String value = trimmed.substring(colon + 1).strip();
                    if (name.isEmpty() || value.isEmpty()) {
                        errors.add(
                                label + " has malformed inline style declaration '" + trimmed + "'");
                        continue;
                    }
                    if (BAKE_REQUIRED_VISUAL_PROPERTIES.contains(name)) {
                        errors.add(
                                label + " uses Bake-required visual property '" + name
                                        + "'; bake the effect or rebuild it with supported geometry");
                    } else if (!SUPPORTED_INLINE_STYLE_PROPERTIES.contains(name)
                            && !SHARED_FAIL_CLOSED_STYLE_PROPERTIES.contains(name)) {
                        errors.add(
                                label + " uses unsupported inline style property '" + name
                                        + "'; native PPTX export would ignore it");
                    }
                    if (value.toLowerCase(Locale.ROOT).contains("!important")) {
                        errors.add(
                                label + " inline style property '" + name + "' cannot use !important");
                    }
                }
            }

            NamedNodeMap attrs = element.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                String attrName = attrs.item(j).getNodeName();
                int brace = attrName.lastIndexOf('}');
                String localAttr = brace >= 0 ? attrName.substring(brace + 1) : attrName;
                int colon = localAttr.indexOf(':');
                if (colon >= 0) {
                    localAttr = localAttr.substring(colon + 1);
                }
                if (BAKE_REQUIRED_VISUAL_PROPERTIES.contains(localAttr)) {
                    errors.add(
                            label + " uses Bake-required visual attribute '" + localAttr
                                    + "'; bake the effect or rebuild it with supported geometry");
                }
            }
        }
        return new ArrayList<>(errors);
    }
}
