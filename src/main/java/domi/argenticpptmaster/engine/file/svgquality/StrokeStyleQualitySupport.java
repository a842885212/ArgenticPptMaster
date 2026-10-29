// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Stroke style errors from {@code svg_contracts.check_stroke_style_values} (errors only). */
final class StrokeStyleQualitySupport {

    private static final String[] STROKE_STYLE_ATTRIBUTES = {
        "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "vector-effect"
    };

    private static final Map<String, Set<String>> STROKE_ENUMS = Map.of(
            "stroke-linecap", Set.of("butt", "round", "square"),
            "stroke-linejoin", Set.of("bevel", "miter", "round"),
            "vector-effect", Set.of("none", "non-scaling-stroke"));

    private static final Pattern DASH_NUMBER =
            Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");
    private static final Pattern DASHARRAY = Pattern.compile(
            "\\s*" + DASH_NUMBER.pattern()
                    + "(?:(?:\\s+|\\s*,\\s*)" + DASH_NUMBER.pattern() + ")+\\s*");

    private StrokeStyleQualitySupport() {}

    static List<String> checkStrokeStyleValues(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            for (String attribute : STROKE_STYLE_ATTRIBUTES) {
                if (element.hasAttribute(attribute)) {
                    collect(errors, label, "attribute", attribute, element.getAttribute(attribute));
                }
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            for (String attribute : STROKE_STYLE_ATTRIBUTES) {
                if (style.containsKey(attribute)) {
                    collect(errors, label, "inline style", attribute, style.get(attribute));
                }
            }
        }
        return new ArrayList<>(errors);
    }

    private static void collect(
            Set<String> errors, String label, String source, String attribute, String raw) {
        try {
            if ("stroke-dasharray".equals(attribute)) {
                parseDasharray(raw);
            } else if ("stroke-dashoffset".equals(attribute)) {
                SvgQualityLength.parseProjectGeometryLength(raw, attribute);
            } else {
                parseEnum(attribute, raw);
            }
        } catch (IllegalArgumentException ex) {
            errors.add(label + " " + source + " " + attribute + "='" + raw + "': " + ex.getMessage());
        }
    }

    private static void parseDasharray(String raw) {
        String text = raw.strip();
        if ("none".equals(text)) {
            return;
        }
        if (!DASHARRAY.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "must be \"none\" or at least two finite unitless numbers separated "
                            + "by spaces or single commas");
        }
        Matcher matcher = DASH_NUMBER.matcher(text);
        List<Double> values = new ArrayList<>();
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group());
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("must contain only finite numbers");
            }
            values.add(value);
        }
        if (values.get(0) <= 0) {
            throw new IllegalArgumentException("dash length must be positive");
        }
        if (values.get(1) < 0) {
            throw new IllegalArgumentException("dash gap must be non-negative");
        }
        for (int i = 2; i < values.size(); i++) {
            if (values.get(i) <= 0) {
                throw new IllegalArgumentException("additional dash and gap values must be positive");
            }
        }
    }

    private static void parseEnum(String attribute, String raw) {
        Set<String> allowed = STROKE_ENUMS.get(attribute);
        if (allowed == null) {
            throw new IllegalArgumentException(
                    "has no registered project enumeration for '" + attribute + "'");
        }
        String value = raw.strip();
        if (!allowed.contains(value)) {
            List<String> choices = new ArrayList<>(allowed);
            choices.sort(String::compareTo);
            throw new IllegalArgumentException("must be one of: " + String.join(", ", choices));
        }
    }
}
