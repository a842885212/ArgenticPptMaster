// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Blocking {@code data-icon} placeholder checks (from {@code _check_icon_placeholders}). */
final class IconPlaceholderQualitySupport {

    private IconPlaceholderQualitySupport() {}

    static List<String> checkIconPlaceholders(Path svgFile, Document document) {
        NodeList nodes = document.getElementsByTagName("*");
        List<Element> placeholders = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            if (!"use".equalsIgnoreCase(SvgQualityXml.localName(element))) {
                continue;
            }
            if (element.hasAttribute("data-icon")) {
                placeholders.add(element);
            }
        }
        if (placeholders.isEmpty()) {
            return List.of();
        }

        Path iconsDir = SvgQualityResourcePaths.iconDirForSvg(svgFile);
        Set<String> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        for (Element elem : placeholders) {
            String iconName = elem.getAttribute("data-icon").strip();
            if (iconName.isEmpty()) {
                errors.add("Icon placeholder has empty data-icon value");
                continue;
            }
            if (!seen.add(iconName)) {
                continue;
            }

            Path iconPath;
            try {
                iconPath = SvgQualityIconResolve.resolveIconPath(iconName, iconsDir).iconPath();
            } catch (IllegalArgumentException exc) {
                errors.add(exc.getMessage());
                continue;
            }
            if (!Files.exists(iconPath)) {
                String hint = SvgQualityIconResolve.suggestIconName(iconName, iconsDir)
                        .map(suggestion -> "; identifiers are case-sensitive; use '" + suggestion + "'")
                        .orElse("");
                errors.add(
                        "Project-local icon not found: "
                                + iconName
                                + " (expected under "
                                + iconsDir
                                + ")"
                                + hint);
            }
        }
        return errors;
    }
}
