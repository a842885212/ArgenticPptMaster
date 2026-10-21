// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.beautify;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Minimal theme palette for beautify identity: master's {@code p:clrMap} + theme
 * {@code a:clrScheme} (srgb / sys lastClr only). Does not port full pptx_to_svg color
 * resolution.
 */
public final class BeautifyThemePalette {

    private static final Pattern SRGB_HEX = Pattern.compile("[0-9A-Fa-f]{6}");
    private static final Map<String, String> SCHEME_ALIASES = Map.of(
            "bg1", "lt1",
            "bg2", "lt2",
            "tx1", "dk1",
            "tx2", "dk2");
    private static final Set<String> COLOR_MAP_SOURCES = Set.of(
            "bg1",
            "tx1",
            "bg2",
            "tx2",
            "accent1",
            "accent2",
            "accent3",
            "accent4",
            "accent5",
            "accent6",
            "hlink",
            "folHlink");
    private static final Set<String> THEME_SCHEME_SLOTS = Set.of(
            "dk1",
            "lt1",
            "dk2",
            "lt2",
            "accent1",
            "accent2",
            "accent3",
            "accent4",
            "accent5",
            "accent6",
            "hlink",
            "folHlink");
    private static final Set<String> COLOR_TAGS = Set.of("srgbClr", "sysClr", "schemeClr", "prstClr", "hslClr", "scrgbClr");

    private final Map<String, String> scheme = new LinkedHashMap<>();
    private final Map<String, String> clrMap = new LinkedHashMap<>();

    public BeautifyThemePalette(Element masterRoot, Element themeRoot) {
        if (themeRoot != null) {
            loadSchemeCompatible(themeRoot);
        }
        if (masterRoot != null) {
            loadClrMapCompatible(masterRoot);
        }
    }

    /** Resolve presentation-level scheme name (e.g. bg1, accent1) to RRGGBB without '#'. */
    public String resolveScheme(String name) {
        String mapped = clrMap.getOrDefault(name, name);
        mapped = SCHEME_ALIASES.getOrDefault(mapped, mapped);
        return scheme.get(mapped);
    }

    private void loadSchemeCompatible(Element themeRoot) {
        Element clrScheme = firstDescendantLocal(themeRoot, "clrScheme");
        if (clrScheme == null) {
            return;
        }
        for (Node child = clrScheme.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element slot)) {
                continue;
            }
            String name = slot.getLocalName();
            if (name == null || !THEME_SCHEME_SLOTS.contains(name)) {
                continue;
            }
            Element color = firstColorChild(slot);
            String hex = resolveBaseHex(color);
            if (hex != null) {
                scheme.put(name, hex);
            }
        }
    }

    private void loadClrMapCompatible(Element masterRoot) {
        Element clrMapElem = null;
        for (Node child = masterRoot.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && "clrMap".equals(element.getLocalName())) {
                clrMapElem = element;
                break;
            }
        }
        if (clrMapElem == null) {
            return;
        }
        for (String source : COLOR_MAP_SOURCES) {
            String target = clrMapElem.getAttribute(source);
            if (THEME_SCHEME_SLOTS.contains(target)) {
                clrMap.put(source, target);
            }
        }
    }

    private static Element firstColorChild(Element slot) {
        for (Node child = slot.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                String local = element.getLocalName();
                if (local != null && COLOR_TAGS.contains(local)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String resolveBaseHex(Element colorElem) {
        if (colorElem == null || colorElem.getLocalName() == null) {
            return null;
        }
        return switch (colorElem.getLocalName()) {
            case "srgbClr" -> {
                String val = colorElem.getAttribute("val");
                yield SRGB_HEX.matcher(val).matches() ? val.toUpperCase() : null;
            }
            case "sysClr" -> {
                String last = colorElem.getAttribute("lastClr");
                yield SRGB_HEX.matcher(last).matches() ? last.toUpperCase() : null;
            }
            default -> null;
        };
    }

    private static Element firstDescendantLocal(Element root, String localName) {
        var nodes = root.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }
}
