// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Materialize supported inline SVG geometry style properties into XML attributes.
 *
 * <p>Port of {@code svg_to_pptx/geometry_properties.py}.</p>
 */
public final class GeometryMaterializer {

    private static final Map<String, Set<String>> INLINE_GEOMETRY_PROPERTIES = Map.of(
            "rect", Set.of("x", "y", "width", "height", "rx", "ry"),
            "circle", Set.of("cx", "cy", "r"),
            "ellipse", Set.of("cx", "cy", "rx", "ry"),
            "image", Set.of("x", "y", "width", "height"),
            "svg", Set.of("x", "y", "width", "height"),
            "use", Set.of("x", "y", "width", "height"));

    private static final Set<String> GEOMETRY_LIKE = Set.of(
            "x", "y", "width", "height", "rx", "ry", "cx", "cy", "r",
            "x1", "y1", "x2", "y2", "dx", "dy", "points", "d");

    private static final Set<String> NON_NEGATIVE = Set.of("width", "height", "rx", "ry", "r");

    private static final Pattern PX_LENGTH = Pattern.compile(
            "^\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(px)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private GeometryMaterializer() {}

    public static int materializeInFile(Path svgPath) throws Exception {
        Document document = SvgFinalizeXml.parse(svgPath);
        int count = materialize(document.getDocumentElement());
        if (count > 0) {
            SvgFinalizeXml.write(document, svgPath);
        }
        return count;
    }

    public static int materialize(Element root) {
        int materialized = 0;
        List<Element> elements = new ArrayList<>();
        collectElements(root, elements);
        for (Element elem : elements) {
            String style = elem.getAttribute("style");
            if (style == null || style.isBlank()) {
                continue;
            }
            String tag = SvgFinalizeXml.localName(elem);
            Set<String> supported = INLINE_GEOMETRY_PROPERTIES.getOrDefault(tag, Set.of());
            List<String> retained = new ArrayList<>();
            int elementMaterialized = 0;
            for (String rawDeclaration : style.split(";")) {
                String declaration = rawDeclaration.strip();
                if (declaration.isEmpty()) {
                    continue;
                }
                int colon = declaration.indexOf(':');
                if (colon <= 0) {
                    retained.add(declaration);
                    continue;
                }
                String name = declaration.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT);
                String value = declaration.substring(colon + 1).strip();
                if (!GEOMETRY_LIKE.contains(name)) {
                    retained.add(declaration);
                    continue;
                }
                if (!supported.contains(name)) {
                    throw new GeometryStyleException(
                            "<" + tag + "> does not support inline geometry property '" + name
                                    + "'; use the element's XML geometry attribute instead");
                }
                elem.setAttribute(name, normalizePxLength(value, tag, name));
                materialized++;
                elementMaterialized++;
            }
            if (elementMaterialized == 0) {
                continue;
            }
            if (retained.isEmpty()) {
                elem.removeAttribute("style");
            } else {
                elem.setAttribute("style", String.join("; ", retained));
            }
        }
        return materialized;
    }

    private static void collectElements(Element root, List<Element> out) {
        out.add(root);
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                collectElements(child, out);
            }
        }
    }

    private static String normalizePxLength(String raw, String tag, String prop) {
        Matcher match = PX_LENGTH.matcher(raw);
        if (!match.matches()) {
            throw new GeometryStyleException(
                    "<" + tag + "> inline geometry " + prop + " requires a finite px literal; got '"
                            + raw + "'");
        }
        double value = Double.parseDouble(match.group(1));
        if (!Double.isFinite(value)) {
            throw new GeometryStyleException(
                    "<" + tag + "> inline geometry " + prop + " must be finite; got '" + raw + "'");
        }
        if (match.group(2) == null && value != 0) {
            throw new GeometryStyleException(
                    "<" + tag + "> inline geometry " + prop + " requires px for non-zero values; got '"
                            + raw + "'");
        }
        if (NON_NEGATIVE.contains(prop) && value < 0) {
            throw new GeometryStyleException(
                    "<" + tag + "> inline geometry " + prop + " cannot be negative; got '" + raw + "'");
        }
        return formatNumber(value);
    }

    private static String formatNumber(double value) {
        return SvgFinalizeXml.formatG(value);
    }

    public static final class GeometryStyleException extends RuntimeException {
        public GeometryStyleException(String message) {
            super(message);
        }
    }
}
