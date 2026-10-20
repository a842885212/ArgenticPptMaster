// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Font-size grammar checks from {@code svg_contracts.check_font_size_values}. */
final class FontSizeQualitySupport {

    private static final Pattern CANONICAL_RE = Pattern.compile("^(?:\\d+(?:\\.\\d+)?|\\.\\d+)$");
    private static final Pattern ATTR_RE =
            Pattern.compile("\\bfont-size\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE_RE =
            Pattern.compile("\\bfont-size\\s*:\\s*([^;\"']+)", Pattern.CASE_INSENSITIVE);

    private FontSizeQualitySupport() {}

    static List<String> checkFontSizeValues(String content) {
        Set<String> values = new LinkedHashSet<>();
        Matcher attr = ATTR_RE.matcher(content);
        while (attr.find()) {
            String value = attr.group(2).strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        Matcher style = STYLE_RE.matcher(content);
        while (style.find()) {
            String value = style.group(1).strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return List.of();
        }

        Set<String> unsupported = new LinkedHashSet<>();
        Set<String> drawingmlOutOfRange = new LinkedHashSet<>();
        for (String raw : values) {
            double parsedPx;
            try {
                parsedPx = SvgQualityLength.parseSvgLength(raw, Double.NaN, null, 16.0);
            } catch (IllegalArgumentException ex) {
                unsupported.add(raw);
                continue;
            }
            if (!Double.isFinite(parsedPx) || parsedPx < 0) {
                unsupported.add(raw);
                continue;
            }
            try {
                SvgQualityLength.fontPxToHpt(parsedPx);
            } catch (IllegalArgumentException ex) {
                drawingmlOutOfRange.add(raw);
            }
        }

        List<String> errors = new ArrayList<>();
        if (!unsupported.isEmpty()) {
            errors.add(
                    "Unsupported font-size value(s): " + formatShown(unsupported)
                            + ". Use a finite non-negative SVG length supported by svg_to_pptx.");
        }
        if (!drawingmlOutOfRange.isEmpty()) {
            errors.add(
                    "font-size value(s) " + formatShown(drawingmlOutOfRange)
                            + " are outside the DrawingML range sz="
                            + SvgQualityLength.DRAWINGML_TEXT_FONT_SIZE_MIN + ".."
                            + SvgQualityLength.DRAWINGML_TEXT_FONT_SIZE_MAX
                            + " (1..4000pt); PowerPoint would repair the exported file. "
                            + "Do not use tiny transparent text as a placeholder carrier: "
                            + "leave a text carrier blank or use the composite object proxy contract.");
        }
        return errors;
    }

    private static String formatShown(Set<String> values) {
        List<String> sorted = values.stream().sorted().collect(Collectors.toCollection(ArrayList::new));
        String shown = sorted.stream().limit(5).collect(Collectors.joining(", "));
        int more = sorted.size() - 5;
        return more > 0 ? shown + " (+" + more + " more)" : shown;
    }
}
