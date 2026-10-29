// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.file.svgfinalize.GeometryMaterializer;
import domi.argenticpptmaster.engine.file.svgfinalize.LocalUseExpander;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Forbidden-element blocklist from {@code svg_contracts.check_forbidden_elements}, plus
 * read-only inline geometry and local {@code <use>} validation (no on-disk SVG mutation).
 */
final class ForbiddenElementsQualitySupport {

    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_STYLESHEET =
            Pattern.compile("<\\?xml-stylesheet\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_STYLESHEET = Pattern.compile(
            "<link[^>]*rel\\s*=\\s*[\"']stylesheet[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_IMPORT = Pattern.compile("@import\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_FONT_FACE = Pattern.compile("@font-face", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_ATTR = Pattern.compile("\\bon\\w+\\s*=", Pattern.CASE_INSENSITIVE);

    private ForbiddenElementsQualitySupport() {}

    static List<String> checkForbiddenElements(String content, Document document) {
        List<String> errors = new ArrayList<>();
        String contentLower = content.toLowerCase(Locale.ROOT);
        Set<String> localNames = collectLocalNames(document);

        if (localNames.contains("style")) {
            errors.add("Detected forbidden <style> element (use inline attributes instead)");
        }
        if (CLASS_ATTR.matcher(content).find()) {
            errors.add("Detected forbidden class attribute (use inline styles instead)");
        }
        if (localNames.contains("style") && ID_ATTR.matcher(content).find()) {
            errors.add(
                    "Detected id attribute used with <style> (CSS selectors forbidden, use inline styles instead)");
        }
        if (XML_STYLESHEET.matcher(contentLower).find()) {
            errors.add("Detected forbidden xml-stylesheet (external CSS references forbidden)");
        }
        if (LINK_STYLESHEET.matcher(contentLower).find()) {
            errors.add("Detected forbidden <link rel=\"stylesheet\"> (external CSS references forbidden)");
        }
        if (AT_IMPORT.matcher(contentLower).find()) {
            errors.add("Detected forbidden @import (external CSS references forbidden)");
        }
        Element root = document.getDocumentElement();
        if (root != null) {
            for (String error : GeometryMaterializer.validateInlineGeometry(root)) {
                errors.add("Invalid inline geometry property: " + error);
            }
        }
        boolean hasGenericUse = false;
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element element
                    && "use".equalsIgnoreCase(SvgQualityXml.localName(element))
                    && !element.hasAttribute("data-icon")) {
                hasGenericUse = true;
                break;
            }
        }
        if (hasGenericUse) {
            for (String error : LocalUseExpander.validateLocalUseReferences(document)) {
                errors.add("Invalid local <use> reference: " + error);
            }
        }
        if (localNames.contains("foreignobject")) {
            errors.add("Detected forbidden <foreignObject> element (use <tspan> for manual line breaks)");
        }
        if (localNames.contains("textpath")) {
            errors.add("Detected forbidden <textPath> element (path text is incompatible with PPT)");
        }
        if (AT_FONT_FACE.matcher(contentLower).find()) {
            errors.add("Detected forbidden @font-face (use system font stack)");
        }
        if (localNames.stream().anyMatch(name -> name.startsWith("animate"))) {
            errors.add(
                    "Detected forbidden SMIL animation element <animate*> "
                            + "(SVG animations are not exported)");
        }
        if (localNames.contains("set")) {
            errors.add("Detected forbidden SMIL animation element <set> (SVG animations are not exported)");
        }
        if (localNames.contains("script")) {
            errors.add("Detected forbidden <script> element (scripts and event handlers forbidden)");
        }
        if (EVENT_ATTR.matcher(content).find() || hasEventAttribute(document)) {
            errors.add("Detected forbidden event attributes (e.g., onclick, onload)");
        }
        if (localNames.contains("iframe")) {
            errors.add("Detected <iframe> element (should not appear in SVG)");
        }
        return errors;
    }

    private static Set<String> collectLocalNames(Document document) {
        Set<String> names = new HashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                names.add(SvgQualityXml.localName(element).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static boolean hasEventAttribute(Document document) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                String name = attrs.item(j).getLocalName();
                if (name == null || name.isEmpty()) {
                    name = attrs.item(j).getNodeName();
                    int colon = name.indexOf(':');
                    if (colon >= 0) {
                        name = name.substring(colon + 1);
                    }
                }
                if (name.toLowerCase(Locale.ROOT).startsWith("on") && name.length() > 2) {
                    return true;
                }
            }
        }
        return false;
    }
}
