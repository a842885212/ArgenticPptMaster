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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Gradient/filter fail-closed subset from pin shared validators. */
final class GradientFilterQualitySupport {

    private static final Set<String> ALLOWED_FILTER_PRIMITIVES = Set.of(
            "fedropshadow",
            "fegaussianblur",
            "feoffset",
            "feflood",
            "fecomposite",
            "femerge",
            "femergenode",
            "fecomponenttransfer",
            "fefunca");

    private GradientFilterQualitySupport() {}

    static List<String> checkGradientAndFilterContracts(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            String label = SvgQualityXml.elementLabel(element);
            if ("lineargradient".equals(tag) || "radialgradient".equals(tag)) {
                String units = element.hasAttribute("gradientUnits")
                        ? element.getAttribute("gradientUnits")
                        : "objectBoundingBox";
                if (!"objectBoundingBox".equals(units) && !"userSpaceOnUse".equals(units)) {
                    errors.add(label + " has unsupported gradientUnits='" + units + "'");
                }
                int stops = 0;
                NodeList children = element.getChildNodes();
                for (int c = 0; c < children.getLength(); c++) {
                    Node node = children.item(c);
                    if (node instanceof Element child
                            && "stop".equalsIgnoreCase(SvgQualityXml.localName(child))) {
                        stops++;
                    }
                }
                if (stops < 2) {
                    errors.add(label + " requires at least two <stop> children");
                }
            }
            if ("filter".equals(tag)) {
                NodeList children = element.getChildNodes();
                boolean hasPrimitive = false;
                for (int c = 0; c < children.getLength(); c++) {
                    Node node = children.item(c);
                    if (!(node instanceof Element child)) {
                        continue;
                    }
                    String childTag = SvgQualityXml.localName(child).toLowerCase(Locale.ROOT);
                    if (childTag.startsWith("fe")) {
                        hasPrimitive = true;
                        if (!ALLOWED_FILTER_PRIMITIVES.contains(childTag)) {
                            errors.add(
                                    label + " contains unsupported filter primitive <"
                                            + SvgQualityXml.localName(child) + ">");
                        }
                    }
                }
                if (!hasPrimitive) {
                    errors.add(label + " requires at least one supported filter primitive");
                }
            }
        }
        return new ArrayList<>(errors);
    }
}
