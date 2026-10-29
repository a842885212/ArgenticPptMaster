// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Legacy pptx authoring attribute renames from {@code SvgQualityChecker._check_legacy_pptx_attributes}.
 */
final class LegacyPptxAttributeQualitySupport {

    private static final Map<String, String> LEGACY_RENAMES = new LinkedHashMap<>();

    static {
        LEGACY_RENAMES.put("data-pptx-module-bounds", "data-pptx-bounds");
        LEGACY_RENAMES.put("data-pptx-placeholder-bounds", "data-pptx-bounds");
        LEGACY_RENAMES.put("data-pptx-placeholder-carrier", "data-pptx-carrier");
        LEGACY_RENAMES.put("data-pptx-placeholder-binding", "data-pptx-binding");
        LEGACY_RENAMES.put("data-pptx-placeholder-idx", "data-pptx-idx");
    }

    private LegacyPptxAttributeQualitySupport() {}

    static List<String> checkLegacyPptxAttributes(Document document) {
        List<String> errors = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                String name = attrs.item(j).getNodeName();
                int brace = name.lastIndexOf('}');
                String local = brace >= 0 ? name.substring(brace + 1) : name;
                int colon = local.indexOf(':');
                if (colon >= 0) {
                    local = local.substring(colon + 1);
                }
                String canonical = LEGACY_RENAMES.get(local);
                if (canonical != null) {
                    errors.add(
                            SvgQualityXml.elementLabel(element) + " uses legacy " + local
                                    + "; rename it to " + canonical);
                }
            }
        }
        return errors;
    }
}
