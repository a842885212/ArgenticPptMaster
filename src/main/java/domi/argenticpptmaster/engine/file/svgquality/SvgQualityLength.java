// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SVG length and DrawingML font-size helpers from {@code svg_to_pptx.drawingml.utils}. */
final class SvgQualityLength {

    static final int DRAWINGML_TEXT_FONT_SIZE_MIN = 100;
    static final int DRAWINGML_TEXT_FONT_SIZE_MAX = 400_000;
    private static final int FONT_PX_TO_HUNDREDTHS_PT = 75;

    private static final Pattern LENGTH_RE = Pattern.compile(
            "^\\s*([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)\\s*([A-Za-z%]*)\\s*$");

    /** Canonical project geometry numeric spelling (no leading +, exponent, or trailing '.'). */
    private static final Pattern CANONICAL_PROJECT_GEOMETRY_LENGTH_RE =
            Pattern.compile("^-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$");

    private static final Set<String> NON_NEGATIVE_LENGTH_ATTRIBUTES =
            Set.of("width", "height", "r", "rx", "ry", "stroke-width");

    private SvgQualityLength() {}

    /** Parse one finite SVG length into its numeric part and lowercase unit. */
    static LengthParts parseSvgLengthParts(String val) {
        if (val == null) {
            throw new IllegalArgumentException("SVG length must be one finite literal, got 'null'");
        }
        Matcher match = LENGTH_RE.matcher(val);
        if (!match.matches()) {
            throw new IllegalArgumentException("SVG length must be one finite literal, got '" + val + "'");
        }
        double number = Double.parseDouble(match.group(1));
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("SVG length must be finite, got '" + val + "'");
        }
        return new LengthParts(number, match.group(2).toLowerCase(Locale.ROOT));
    }

    /**
     * Parse one project geometry value (unitless or {@code px} only; canonical numeric spelling).
     */
    static double parseProjectGeometryLength(String raw, String attribute) {
        LengthParts parts = parseSvgLengthParts(raw);
        if (!parts.unit().isEmpty() && !"px".equals(parts.unit())) {
            throw new IllegalArgumentException(
                    "uses unsupported unit '" + parts.unit()
                            + "'; project geometry accepts only unitless values or the compatible px suffix");
        }
        String numericLiteral = raw.strip();
        if ("px".equals(parts.unit())) {
            numericLiteral = numericLiteral.substring(0, numericLiteral.length() - 2).strip();
        }
        if (!CANONICAL_PROJECT_GEOMETRY_LENGTH_RE.matcher(numericLiteral).matches()) {
            throw new IllegalArgumentException(
                    "uses an unsupported numeric spelling; use an ordinary decimal "
                            + "without a leading plus sign, exponent, or trailing decimal point");
        }
        if (NON_NEGATIVE_LENGTH_ATTRIBUTES.contains(attribute) && parts.number() < 0) {
            throw new IllegalArgumentException("must be non-negative");
        }
        return parts.number();
    }

    /**
     * Parse and clamp one opacity value from the closed project grammar.
     *
     * @param allowPercentage when true, {@code %} units are accepted (stop/flood opacity)
     */
    static double parseProjectOpacity(String raw, boolean allowPercentage) {
        LengthParts parts;
        try {
            parts = parseSvgLengthParts(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("must be one finite numeric opacity");
        }
        double number = parts.number();
        if ("%".equals(parts.unit())) {
            if (!allowPercentage) {
                throw new IllegalArgumentException("must be unitless; percentages are not supported");
            }
            number /= 100.0;
        } else if (!parts.unit().isEmpty()) {
            throw new IllegalArgumentException("uses unsupported unit '" + parts.unit() + "'");
        }
        return Math.max(0.0, Math.min(1.0, number));
    }

    record LengthParts(double number, String unit) {}

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
