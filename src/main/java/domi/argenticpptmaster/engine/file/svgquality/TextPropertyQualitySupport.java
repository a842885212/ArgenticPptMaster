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
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/** Text-property contract errors only (no canonical spelling recommendations). */
final class TextPropertyQualitySupport {

    private static final Set<String> UNSUPPORTED = Set.of(
            "alignment-baseline",
            "direction",
            "dominant-baseline",
            "font-kerning",
            "font-feature-settings",
            "font-size-adjust",
            "font-stretch",
            "font-synthesis",
            "font-variant",
            "font-variation-settings",
            "font",
            "hyphens",
            "kerning",
            "line-height",
            "overflow-wrap",
            "text-align",
            "text-align-last",
            "text-indent",
            "text-rendering",
            "text-shadow",
            "text-transform",
            "unicode-bidi",
            "vertical-align",
            "white-space",
            "word-spacing",
            "word-break",
            "writing-mode");

    private static final Set<String> TEXT_DIRECT = Set.of(
            "fill",
            "fill-opacity",
            "filter",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "id",
            "letter-spacing",
            "opacity",
            "stroke",
            "stroke-opacity",
            "stroke-width",
            "style",
            "text-anchor",
            "text-decoration",
            "transform",
            "x",
            "xml:space",
            "y");

    private static final Set<String> TSPAN_DIRECT = Set.of(
            "baseline-shift",
            "dx",
            "dy",
            "fill",
            "fill-opacity",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "id",
            "letter-spacing",
            "opacity",
            "stroke",
            "stroke-opacity",
            "stroke-width",
            "style",
            "text-anchor",
            "text-decoration",
            "x",
            "xml:space",
            "y");

    private static final Set<String> TEXT_INLINE = Set.of(
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

    private TextPropertyQualitySupport() {}

    static List<String> checkTextPropertyContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            if (!"text".equals(tag) && !"tspan".equals(tag)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            Set<String> direct = "text".equals(tag) ? TEXT_DIRECT : TSPAN_DIRECT;
            NamedNodeMap attrs = element.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                String name = attrs.item(j).getNodeName();
                int brace = name.lastIndexOf('}');
                String local = brace >= 0 ? name.substring(brace + 1) : name;
                if ("style".equals(local) || local.startsWith("data-")) {
                    continue;
                }
                if (UNSUPPORTED.contains(local)) {
                    errors.add(
                            label + " uses unsupported text property '" + local
                                    + "'; native PPTX export would ignore it");
                } else if (!direct.contains(local) && !local.contains(":")) {
                    errors.add(
                            label + " uses unsupported text attribute '" + local
                                    + "'; native PPTX export would ignore it");
                }
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            for (Map.Entry<String, String> entry : style.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (UNSUPPORTED.contains(name)) {
                    errors.add(
                            label + " uses unsupported text property '" + name
                                    + "'; native PPTX export would ignore it");
                } else if (!TEXT_INLINE.contains(name)
                        && !("tspan".equals(tag) && "baseline-shift".equals(name))) {
                    // geometry-like props already handled by authoring/geometry; flag unknown text-ish only
                    if (name.startsWith("text-") || name.startsWith("font-") || name.startsWith("word-")) {
                        errors.add(
                                label + " uses unsupported inline text property '" + name
                                        + "'; native PPTX export would ignore it");
                    }
                }
            }
        }
        return new ArrayList<>(errors);
    }
}
