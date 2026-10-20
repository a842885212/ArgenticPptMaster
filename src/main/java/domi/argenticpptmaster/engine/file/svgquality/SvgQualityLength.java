// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SVG length and DrawingML font-size helpers from {@code svg_to_pptx.drawingml.utils}. */
final class SvgQualityLength {

    static final int DRAWINGML_TEXT_FONT_SIZE_MIN = 100;
    static final int DRAWINGML_TEXT_FONT_SIZE_MAX = 400_000;
    private static final int FONT_PX_TO_HUNDREDTHS_PT = 75;

    private static final Pattern LENGTH_RE = Pattern.compile(
            "^\\s*([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)\\s*([A-Za-z%]*)\\s*$");

    private SvgQualityLength() {}

    static double parseSvgLength(String val, double defaultValue, Double percentBase, double fontSize) {
        if (val == null) {
            return defaultValue;
        }
        Matcher match = LENGTH_RE.matcher(val);
        if (!match.matches()) {
            throw new IllegalArgumentException("SVG length must be one finite literal, got '" + val + "'");
        }
        double number = Double.parseDouble(match.group(1));
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("SVG length must be finite, got '" + val + "'");
        }
        String unit = match.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "%" -> {
                if (percentBase == null) {
                    throw new IllegalArgumentException(
                            "SVG percentage length requires a reference length, got '" + val + "'");
                }
                yield percentBase * number / 100.0;
            }
            case "", "px" -> number;
            case "pt" -> number * 96.0 / 72.0;
            case "pc", "pica" -> number * 16.0;
            case "in" -> number * 96.0;
            case "cm" -> number * 96.0 / 2.54;
            case "mm" -> number * 96.0 / 25.4;
            case "q" -> number * 96.0 / 101.6;
            case "em", "rem" -> {
                if (!Double.isFinite(fontSize)) {
                    throw new IllegalArgumentException(
                            "SVG relative length requires a finite font size, got '" + val + "'");
                }
                yield number * fontSize;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported SVG length unit '" + unit + "' in '" + val + "'");
        };
    }

    static int fontPxToHpt(double fontSizePx) {
        if (!Double.isFinite(fontSizePx)) {
            throw new IllegalArgumentException("SVG font-size must be finite, got " + fontSizePx);
        }
        double scaled = fontSizePx * FONT_PX_TO_HUNDREDTHS_PT;
        if (!Double.isFinite(scaled)) {
            throw new IllegalArgumentException("SVG font-size must be finite, got " + fontSizePx);
        }
        int size = (int) Math.round(scaled / 10.0) * 10;
        if (size < DRAWINGML_TEXT_FONT_SIZE_MIN || size > DRAWINGML_TEXT_FONT_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "SVG font-size " + fontSizePx + "px converts to DrawingML sz=" + size
                            + "; expected " + DRAWINGML_TEXT_FONT_SIZE_MIN + ".."
                            + DRAWINGML_TEXT_FONT_SIZE_MAX + " (1..4000pt)");
        }
        return size;
    }
}
