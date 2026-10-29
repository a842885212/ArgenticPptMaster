// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project paint parse helpers for quality blocking errors (no canonical-spelling recommendations).
 *
 * <p>Subset of {@code svg_to_pptx.drawingml.utils.parse_project_paint} / {@code parse_svg_color}.</p>
 */
final class SvgQualityPaint {

    static final Set<String> PAINT_PROPERTIES = Set.of(
            "fill", "stroke", "stop-color", "flood-color", "data-pptx-fg", "data-pptx-bg");

    static final Set<String> REFERENCE_PAINT_PROPERTIES = Set.of("fill", "stroke");

    private static final Pattern URL_REF = Pattern.compile("^url\\(#([^)]+)\\)$");
    private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    private static final Pattern RGB = Pattern.compile(
            "^rgba?\\((.+)\\)$", Pattern.CASE_INSENSITIVE);

    private static final Set<String> NAMED = Set.of(
            "black", "white", "red", "green", "blue", "yellow", "cyan", "magenta", "gray", "grey",
            "orange", "purple", "pink", "brown", "navy", "teal", "olive", "maroon", "silver", "lime",
            "aqua", "fuchsia", "transparent", "none");

    private SvgQualityPaint() {}

    /**
     * Parse one paint value.
     *
     * @return kind {@code color}, {@code none}, or {@code reference}
     */
    static PaintParse parseProjectPaint(String raw, String propertyName) {
        if (!PAINT_PROPERTIES.contains(propertyName)) {
            throw new IllegalArgumentException("unknown project paint property '" + propertyName + "'");
        }
        String value = raw == null ? "" : raw.strip();
        if (REFERENCE_PAINT_PROPERTIES.contains(propertyName)) {
            if ("none".equalsIgnoreCase(value)) {
                return new PaintParse("none", null, 1.0);
            }
            Matcher url = URL_REF.matcher(value);
            if (url.matches()) {
                return new PaintParse("reference", url.group(1), 1.0);
            }
        }
        ColorParse color = parseSvgColor(value);
        if (color != null) {
            return new PaintParse("color", color.hex(), color.alpha());
        }
        String accepted = REFERENCE_PAINT_PROPERTIES.contains(propertyName)
                ? "a supported color, none, or an exact local url(#id) reference"
                : "a supported color";
        throw new IllegalArgumentException("must be " + accepted);
    }

    static ColorParse parseSvgColor(String colorStr) {
        if (colorStr == null) {
            return null;
        }
        String text = colorStr.strip();
        if (text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || "transparent".equals(lower)) {
            return new ColorParse(null, 0.0);
        }
        if (NAMED.contains(lower) && !"none".equals(lower) && !"transparent".equals(lower)) {
            return new ColorParse(namedHex(lower), 1.0);
        }
        Matcher hex = HEX.matcher(text);
        if (hex.matches()) {
            String digits = hex.group(1);
            if (digits.length() == 3) {
                digits = "" + digits.charAt(0) + digits.charAt(0)
                        + digits.charAt(1) + digits.charAt(1)
                        + digits.charAt(2) + digits.charAt(2);
            }
            double alpha = 1.0;
            if (digits.length() == 8) {
                alpha = Integer.parseInt(digits.substring(6, 8), 16) / 255.0;
                digits = digits.substring(0, 6);
            }
            return new ColorParse(digits.toUpperCase(Locale.ROOT), alpha);
        }
        Matcher rgb = RGB.matcher(text);
        if (rgb.matches()) {
            try {
                return parseRgbBody(rgb.group(1));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ColorParse parseRgbBody(String body) {
        String[] slash = body.split("/", 2);
        String before = slash[0].trim();
        String[] parts = before.split("[,\\s]+");
        if (parts.length < 3) {
            throw new IllegalArgumentException("rgb channels");
        }
        int r = channel(parts[0]);
        int g = channel(parts[1]);
        int b = channel(parts[2]);
        double alpha = 1.0;
        if (slash.length == 2) {
            alpha = alphaChannel(slash[1].trim());
        } else if (parts.length >= 4) {
            alpha = alphaChannel(parts[3]);
        }
        return new ColorParse(String.format(Locale.ROOT, "%02X%02X%02X", r, g, b), alpha);
    }

    private static int channel(String raw) {
        String text = raw.strip();
        double value;
        if (text.endsWith("%")) {
            value = Double.parseDouble(text.substring(0, text.length() - 1)) * 255.0 / 100.0;
        } else {
            value = Double.parseDouble(text);
        }
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static double alphaChannel(String raw) {
        String text = raw.strip();
        double value = text.endsWith("%")
                ? Double.parseDouble(text.substring(0, text.length() - 1)) / 100.0
                : Double.parseDouble(text);
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String namedHex(String name) {
        return switch (name) {
            case "black" -> "000000";
            case "white" -> "FFFFFF";
            case "red" -> "FF0000";
            case "green" -> "008000";
            case "blue" -> "0000FF";
            case "yellow" -> "FFFF00";
            case "cyan", "aqua" -> "00FFFF";
            case "magenta", "fuchsia" -> "FF00FF";
            case "gray", "grey" -> "808080";
            case "orange" -> "FFA500";
            case "purple" -> "800080";
            case "pink" -> "FFC0CB";
            case "brown" -> "A52A2A";
            case "navy" -> "000080";
            case "teal" -> "008080";
            case "olive" -> "808000";
            case "maroon" -> "800000";
            case "silver" -> "C0C0C0";
            case "lime" -> "00FF00";
            default -> "000000";
        };
    }

    record PaintParse(String kind, String value, double alpha) {}

    record ColorParse(String hex, double alpha) {}
}
