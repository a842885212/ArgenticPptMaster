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

/**
 * Native object / formula marker errors for flat-release (fail-closed subset).
 *
 * <p>Full chart/table reconstruction is out of scope; unknown replace-with kinds and
 * incomplete formula markers are blocked.</p>
 */
final class NativeObjectMarkerQualitySupport {

    private static final String INLINE_FORMULA_ATTR = "data-pptx-inline-formula";
    private static final Set<String> ALLOWED_REPLACE = Set.of("formula", "chart", "table");

    private NativeObjectMarkerQualitySupport() {}

    static List<String> checkNativeObjectMarkers(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String markerId = firstNonBlank(element.getAttribute("id"), element.getAttribute("data-name"), "<unnamed>");
            if (element.hasAttribute(INLINE_FORMULA_ATTR)) {
                String latex = element.getAttribute(INLINE_FORMULA_ATTR);
                if (latex == null || latex.isBlank()) {
                    errors.add("Invalid inline formula marker: " + markerId + " missing latex payload");
                }
            }
            if (element.hasAttribute("data-pptx-replace-with")) {
                String kind = element.getAttribute("data-pptx-replace-with").strip().toLowerCase(Locale.ROOT);
                if (!ALLOWED_REPLACE.contains(kind)) {
                    errors.add(
                            "PPTX graphic " + markerId + " has invalid status metadata: "
                                    + "unsupported data-pptx-replace-with='"
                                    + element.getAttribute("data-pptx-replace-with") + "'");
                } else if ("formula".equals(kind)) {
                    boolean hasLatex = element.hasAttribute("data-pptx-latex")
                            || element.hasAttribute("data-pptx-formula")
                            || (element.getAttribute("data-pptx-metadata") != null
                                    && !element.getAttribute("data-pptx-metadata").isBlank());
                    // Flat-release formula markers need framing attrs used by FormulaConverter.
                    if (!element.hasAttribute("data-pptx-x")
                            || !element.hasAttribute("data-pptx-y")
                            || !element.hasAttribute("data-pptx-width")
                            || !element.hasAttribute("data-pptx-height")) {
                        errors.add(
                                "PPTX graphic " + markerId
                                        + " has invalid status metadata: formula marker requires "
                                        + "data-pptx-x/y/width/height frame attributes");
                    }
                    if (!hasLatex && !element.hasAttribute(INLINE_FORMULA_ATTR)) {
                        // still allow if metadata JSON is present elsewhere; minimal check:
                        if (!element.hasAttribute("data-pptx-metadata")) {
                            errors.add(
                                    "PPTX graphic " + markerId
                                            + " has invalid status metadata: formula marker missing latex/metadata");
                        }
                    }
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "<unnamed>";
    }
}
