// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Unsupported visual elements aligned to {@code SvgSlideConverter.convertElement} dispatch.
 *
 * <p>Whitelist mirrors converter switch tags plus non-visual containers. Generic local
 * {@code <use>} is left to local-use validation; {@code data-icon} {@code <use>} is allowed.</p>
 */
final class UnsupportedVisualsQualitySupport {

    /**
     * Keep in sync with {@code SvgSlideConverter} convertElement cases, plus {@code a}
     * (pin converter dispatches hyperlinks; Java DrawingML {@code convert_a} may still be residual).
     */
    private static final Set<String> SUPPORTED_VISUAL = Set.of(
            "g",
            "a",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "path",
            "text",
            "image");

    /** Keep in sync with {@code SvgSlideConverter.NON_VISUAL}. */
    private static final Set<String> NON_VISUAL = Set.of("defs", "metadata", "title", "desc", "style");

    private static final Set<String> SUPPORTED_VISUAL_CHILDREN = Set.of("tspan", "a");

    private UnsupportedVisualsQualitySupport() {}

    static List<String> checkUnsupportedVisualElements(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        List<String> unsupported = new ArrayList<>();
        NodeList children = root.getChildNodes();
        int index = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                index++;
                walk(child, "/svg[" + index + "]", false, "svg", unsupported);
            }
        }
        if (unsupported.isEmpty()) {
            return List.of();
        }
        String preview = String.join("; ", unsupported.subList(0, Math.min(8, unsupported.size())));
        String suffix = unsupported.size() <= 8 ? "" : "; +" + (unsupported.size() - 8) + " more";
        return List.of(
                "Unsupported visual SVG element(s) for native PPTX export: " + preview + suffix);
    }

    private static void walk(
            Element elem,
            String path,
            boolean inDefs,
            String parentTag,
            List<String> unsupported) {
        String tag = SvgQualityXml.localName(elem).toLowerCase(Locale.ROOT);
        String current = path + "/" + tag;
        if (inDefs) {
            return;
        }
        if (NON_VISUAL.contains(tag)) {
            // Skip children of non-visual containers except we still skip walking into defs below.
            if ("defs".equals(tag)) {
                return;
            }
            return;
        }
        boolean dataIconUse = "use".equals(tag) && elem.hasAttribute("data-icon");
        boolean genericUse = "use".equals(tag) && !elem.hasAttribute("data-icon");
        boolean visualChild =
                SUPPORTED_VISUAL_CHILDREN.contains(tag)
                        && ("text".equals(parentTag) || "tspan".equals(parentTag) || "a".equals(parentTag));
        if (!SUPPORTED_VISUAL.contains(tag)
                && !NON_VISUAL.contains(tag)
                && !visualChild
                && !dataIconUse
                && !genericUse) {
            unsupported.add(current);
        }
        if ("defs".equals(tag)) {
            return;
        }
        NodeList children = elem.getChildNodes();
        int index = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                index++;
                walk(child, current + "[" + index + "]", false, tag, unsupported);
            }
        }
    }
}
