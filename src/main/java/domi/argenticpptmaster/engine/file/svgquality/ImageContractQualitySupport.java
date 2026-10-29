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
import org.w3c.dom.NodeList;

/** Image aspect-ratio / nested-crop contract errors (errors only). */
final class ImageContractQualitySupport {

    private static final Set<String> ASPECT_ANCHORS = Set.of(
            "none",
            "xMinYMin",
            "xMidYMin",
            "xMaxYMin",
            "xMinYMid",
            "xMidYMid",
            "xMaxYMid",
            "xMinYMax",
            "xMidYMax",
            "xMaxYMax");
    private static final Set<String> ASPECT_MODES = Set.of("meet", "slice");

    private ImageContractQualitySupport() {}

    static List<String> checkImageContract(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        Element root = document.getDocumentElement();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            String label = SvgQualityXml.elementLabel(element);
            if ("image".equals(tag) && element.hasAttribute("preserveAspectRatio")) {
                try {
                    parseAspectRatio(element.getAttribute("preserveAspectRatio"));
                } catch (IllegalArgumentException ex) {
                    errors.add(label + " preserveAspectRatio='" + element.getAttribute("preserveAspectRatio")
                            + "': " + ex.getMessage());
                }
            }
            if ("svg".equals(tag) && element != root) {
                boolean crop = "1".equals(element.getAttribute("data-pptx-crop"));
                if (!crop && (element.hasAttribute("viewBox") || element.hasAttribute("width"))) {
                    // Nested SVG outside imported crop transport is unsupported for flat release.
                    errors.add(
                            label + " nested <svg> is reserved for imported picture-crop transport "
                                    + "(set data-pptx-crop=\"1\")");
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static void parseAspectRatio(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("must not be empty; omit the attribute for the default");
        }
        String[] parts = text.split("\\s+");
        String align = parts[0];
        if (!ASPECT_ANCHORS.contains(align)) {
            throw new IllegalArgumentException("alignment must be \"none\" or a valid x*Y* token");
        }
        if (parts.length > 2) {
            throw new IllegalArgumentException("accepts at most one alignment and one mode token");
        }
        if ("none".equals(align)) {
            if (parts.length == 2) {
                throw new IllegalArgumentException("alignment none cannot take a mode token");
            }
            return;
        }
        String mode = parts.length == 2 ? parts[1] : "meet";
        if (!ASPECT_MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be one of: meet, slice");
        }
    }
}
