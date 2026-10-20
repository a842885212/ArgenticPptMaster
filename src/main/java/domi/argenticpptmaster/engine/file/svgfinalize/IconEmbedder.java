// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Expand project-local {@code <use data-icon="library/name">} placeholders.
 *
 * <p>Port of {@code svg_finalize/embed_icons.py} for the service SVG subset.
 * Icons are resolved only under {@code project/icons/} with namespaced paths.</p>
 */
public final class IconEmbedder {

    private static final Map<String, Double> ICON_BASE_SIZES = Map.of(
            "chunk-filled", 16.0,
            "tabler-filled", 24.0,
            "tabler-outline", 24.0,
            "phosphor-duotone", 256.0,
            "simple-icons", 24.0,
            "imported", 24.0);

    private static final double DEFAULT_ICON_BASE_SIZE = 24.0;

    private static final Pattern ICON_IDENTIFIER = Pattern.compile(
            "(?<library>[a-z0-9][a-z0-9-]*)/(?<name>[A-Za-z0-9][A-Za-z0-9._-]*)");

    private static final Pattern USE_PATTERN = Pattern.compile(
            "<use\\b(?=[^>]*\\bdata-icon\\s*=)[^>]*/>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "\\b([A-Za-z_:][-A-Za-z0-9_:.]*|data-icon)\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.DOTALL);

    private static final Pattern VIEWBOX_SQUARE =
            Pattern.compile("viewBox=[\"']0 0 ([\\d.]+)");

    private static final Pattern VIEWBOX_FULL =
            Pattern.compile("viewBox=[\"']([^\"']+)[\"']");

    private static final Pattern SHAPE_PATTERN = Pattern.compile(
            "<(path|circle|rect|line|polyline|polygon|ellipse)(\\s[^>]*)?(?:/>|></\\1>)",
            Pattern.DOTALL);

    private IconEmbedder() {}

    public static Path iconDirForProject(Path projectDir) {
        return projectDir.resolve("icons");
    }

    public static int processSvgFile(Path svgPath, Path iconsDir) throws IOException {
        if (!Files.isRegularFile(svgPath)) {
            throw new IOException("SVG file not found: " + svgPath);
        }
        String content = Files.readString(svgPath, StandardCharsets.UTF_8);
        Matcher matcher = USE_PATTERN.matcher(content);
        List<int[]> spans = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        while (matcher.find()) {
            String useStr = matcher.group();
            Map<String, Object> attrs = parseUseElement(useStr);
            Object iconObj = attrs.get("icon");
            if (iconObj == null || iconObj.toString().isBlank()) {
                throw new IllegalArgumentException(
                        svgPath.getFileName() + ": icon placeholder has an empty data-icon value");
            }
            String iconName = iconObj.toString();
            Path iconPath = resolveIconPath(iconName, iconsDir);
            if (!Files.isRegularFile(iconPath)) {
                Optional<String> suggestion = suggestIconName(iconName, iconsDir);
                String hint = suggestion.map(s -> "; identifiers are case-sensitive; use '" + s + "'")
                        .orElse("");
                throw new IOException(
                        svgPath.getFileName() + ": project-local icon not found: " + iconName + hint);
            }
            ExtractedIcon extracted = extractPathsFromIcon(iconPath);
            if (extracted.elements().isEmpty()) {
                throw new IllegalArgumentException(
                        svgPath.getFileName() + ": icon has no embeddable shapes: " + iconName);
            }
            String replacement = generateIconGroup(attrs, extracted);
            spans.add(new int[] {matcher.start(), matcher.end()});
            replacements.add(replacement);
        }
        if (spans.isEmpty()) {
            return 0;
        }
        StringBuilder newContent = new StringBuilder(content);
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            newContent.replace(span[0], span[1], replacements.get(i));
        }
        Files.writeString(svgPath, newContent.toString(), StandardCharsets.UTF_8);
        return spans.size();
    }

    static Map<String, Object> parseUseElement(String useMatch) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(useMatch);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = matcher.group(3);
            switch (name) {
                case "data-icon" -> attrs.put("icon", value);
                case "x", "y", "width", "height" -> attrs.put(name, parseProjectGeometryLength(value, name));
                case "fill" -> attrs.put("fill", value);
                case "stroke" -> attrs.put("stroke", value);
                case "transform" -> attrs.put("transform", value);
                case "stroke-width" -> attrs.put("stroke-width", value);
                default -> {
                    // ignore
                }
            }
        }
        return attrs;
    }

    static double parseProjectGeometryLength(String raw, String attribute) {
        String trimmed = raw.strip();
        String unit = "";
        String numeric = trimmed;
        if (trimmed.toLowerCase(Locale.ROOT).endsWith("px")) {
            unit = "px";
            numeric = trimmed.substring(0, trimmed.length() - 2).strip();
        }
        if (!unit.isEmpty() && !"px".equals(unit)) {
            throw new IllegalArgumentException(
                    attribute + " uses unsupported unit; project geometry accepts unitless or px");
        }
        if (!numeric.matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?")) {
            // Allow ordinary decimals without leading plus/exponent/trailing dot.
            if (!numeric.matches("-?(?:\\d+\\.\\d+|\\d+)")) {
                throw new IllegalArgumentException(
                        attribute + " uses an unsupported numeric spelling");
            }
        }
        double number = Double.parseDouble(numeric);
        if (("width".equals(attribute) || "height".equals(attribute)) && number < 0) {
            throw new IllegalArgumentException(attribute + " must be non-negative");
        }
        return number;
    }

    static Path resolveIconPath(String iconName, Path iconsDir) {
        Matcher match = ICON_IDENTIFIER.matcher(iconName);
        if (!match.matches()) {
            throw new IllegalArgumentException(
                    "data-icon must be a complete project-local library/name identifier: '"
                            + iconName + "'");
        }
        Path iconPath = iconsDir.resolve(match.group("library")).resolve(match.group("name") + ".svg");
        Path resolvedRoot = iconsDir.toAbsolutePath().normalize();
        Path resolvedIcon = iconPath.toAbsolutePath().normalize();
        if (!resolvedIcon.startsWith(resolvedRoot)) {
            throw new IllegalArgumentException(
                    "data-icon escapes the project-local icon root: '" + iconName + "'");
        }
        return iconPath;
    }

    static Optional<String> suggestIconName(String iconName, Path iconsDir) {
        if (!Files.isDirectory(iconsDir)) {
            return Optional.empty();
        }
        Matcher match = ICON_IDENTIFIER.matcher(iconName);
        if (!match.matches()) {
            return Optional.empty();
        }
        Path searchDir = iconsDir.resolve(match.group("library"));
        if (!Files.isDirectory(searchDir)) {
            return Optional.empty();
        }
        String expected = (match.group("name") + ".svg").toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(searchDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path)
                        && name.toLowerCase(Locale.ROOT).equals(expected)) {
                    matches.add(path);
                }
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.empty();
        }
        String relative = iconsDir.relativize(matches.get(0)).toString().replace('\\', '/');
        if (relative.endsWith(".svg")) {
            relative = relative.substring(0, relative.length() - 4);
        }
        return Optional.of(relative);
    }

    record ExtractedIcon(List<String> elements, String style, double minX, double minY, double width, double height) {}

    static ExtractedIcon extractPathsFromIcon(Path iconPath) throws IOException {
        if (!Files.isRegularFile(iconPath)) {
            return new ExtractedIcon(List.of(), "fill", 0, 0, 16, 16);
        }
        String content = Files.readString(iconPath, StandardCharsets.UTF_8);
        if (content.contains("data-icon-style=\"preserve-color\"")) {
            double[] geometry = viewBoxGeometry(content)
                    .orElse(new double[] {0, 0, DEFAULT_ICON_BASE_SIZE, DEFAULT_ICON_BASE_SIZE});
            List<String> body = extractSvgBody(content);
            return new ExtractedIcon(body, "preserve", geometry[0], geometry[1], geometry[2], geometry[3]);
        }
        String style = content.contains("stroke=\"currentColor\"") && content.contains("fill=\"none\"")
                ? "stroke"
                : "fill";
        double baseSize = viewBoxSquare(content).orElse(16.0);
        List<String> elements = extractShapeElements(content);
        return new ExtractedIcon(elements, style, 0, 0, baseSize, baseSize);
    }

    private static Optional<Double> viewBoxSquare(String content) {
        Matcher matcher = VIEWBOX_SQUARE.matcher(content);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Double.parseDouble(matcher.group(1)));
    }

    private static Optional<double[]> viewBoxGeometry(String content) {
        Matcher matcher = VIEWBOX_FULL.matcher(content);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String[] parts = matcher.group(1).strip().split("[\\s,]+");
        if (parts.length < 4) {
            return Optional.empty();
        }
        try {
            double minX = Double.parseDouble(parts[0]);
            double minY = Double.parseDouble(parts[1]);
            double width = Double.parseDouble(parts[2]);
            double height = Double.parseDouble(parts[3]);
            if (width <= 0 || height <= 0) {
                return Optional.empty();
            }
            return Optional.of(new double[] {minX, minY, width, height});
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static List<String> extractSvgBody(String content) {
        Pattern body = Pattern.compile("<svg\\b[^>]*>(.*)</svg>\\s*$", Pattern.DOTALL);
        Matcher matcher = body.matcher(content);
        if (!matcher.find()) {
            return List.of();
        }
        String inner = matcher.group(1).strip();
        return inner.isEmpty() ? List.of() : List.of(inner);
    }

    private static List<String> extractShapeElements(String content) {
        List<String> elements = new ArrayList<>();
        Matcher matcher = SHAPE_PATTERN.matcher(content);
        while (matcher.find()) {
            String tag = matcher.group(1);
            String attrs = matcher.group(2) == null ? "" : matcher.group(2);
            String cleaned = attrs
                    .replaceAll("\\s*fill=\"(?:currentColor|#[0-9a-fA-F]{3,6}|none)\"", "")
                    .replaceAll("\\s*stroke=\"(?:currentColor|#[0-9a-fA-F]{3,6}|none)\"", "")
                    .replaceAll("\\s*stroke-width=\"[^\"]*\"", "");
            elements.add("<" + tag + cleaned + "/>");
        }
        return elements;
    }

    static String resolveIconColor(Map<String, Object> attrs, String style) {
        if ("preserve".equals(style)) {
            return "preserve";
        }
        String fill = String.valueOf(attrs.getOrDefault("fill", "")).strip();
        String stroke = String.valueOf(attrs.getOrDefault("stroke", "")).strip();
        if ("stroke".equals(style)) {
            if (!fill.isEmpty() && !"none".equals(fill)) {
                return fill;
            }
            if (!stroke.isEmpty() && !"none".equals(stroke)) {
                return stroke;
            }
            return "#000000";
        }
        if (!fill.isEmpty()) {
            return fill;
        }
        if (!stroke.isEmpty() && !"none".equals(stroke)) {
            return stroke;
        }
        return "#000000";
    }

    static String generateIconGroup(Map<String, Object> attrs, ExtractedIcon extracted) {
        double minX = extracted.minX();
        double minY = extracted.minY();
        double baseWidth = extracted.width();
        double baseHeight = extracted.height();
        double x = numberAttr(attrs, "x", 0);
        double y = numberAttr(attrs, "y", 0);
        double width = numberAttr(attrs, "width", baseWidth);
        double height = numberAttr(attrs, "height", baseHeight);
        String style = extracted.style();
        String color = resolveIconColor(attrs, style);
        String iconName = String.valueOf(attrs.getOrDefault("icon", "unknown"));
        double scaleX = width / baseWidth;
        double scaleY = height / baseHeight;
        String transform;
        if (attrs.get("transform") != null) {
            transform = String.valueOf(attrs.get("transform"));
        } else if (Math.abs(scaleX - 1) < 1e-6 && Math.abs(scaleY - 1) < 1e-6) {
            transform = "translate(" + SvgFinalizeXml.formatG(x) + ", " + SvgFinalizeXml.formatG(y) + ")";
        } else if (Math.abs(scaleX - scaleY) < 1e-6) {
            transform = "translate(" + SvgFinalizeXml.formatG(x) + ", " + SvgFinalizeXml.formatG(y)
                    + ") scale(" + SvgFinalizeXml.formatG(scaleX) + ")";
        } else {
            transform = "translate(" + SvgFinalizeXml.formatG(x) + ", " + SvgFinalizeXml.formatG(y)
                    + ") scale(" + SvgFinalizeXml.formatG(scaleX) + ", " + SvgFinalizeXml.formatG(scaleY) + ")";
        }
        String elementsStr = String.join("\n    ", extracted.elements());
        if ("preserve".equals(style)) {
            if (minX != 0 || minY != 0) {
                String inner = "translate(" + SvgFinalizeXml.formatG(-minX) + ", "
                        + SvgFinalizeXml.formatG(-minY) + ")";
                elementsStr = "<g transform=\"" + inner + "\">\n    " + elementsStr + "\n    </g>";
            }
            return "<!-- icon: " + iconName + " -->\n  <g transform=\"" + transform + "\">\n    "
                    + elementsStr + "\n  </g>";
        }
        String colorAttrs;
        if ("stroke".equals(style)) {
            String strokeWidth = String.valueOf(attrs.getOrDefault("stroke-width", "2"));
            colorAttrs = "fill=\"none\" stroke=\"" + color + "\" stroke-width=\"" + strokeWidth + "\"";
        } else {
            colorAttrs = "fill=\"" + color + "\"";
        }
        return "<!-- icon: " + iconName + " -->\n  <g transform=\"" + transform + "\" " + colorAttrs
                + ">\n    " + elementsStr + "\n  </g>";
    }

    private static double numberAttr(Map<String, Object> attrs, String name, double defaultValue) {
        Object value = attrs.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }

    /** Library base size lookup used by tests / diagnostics. */
    static double libraryBaseSize(String library) {
        return ICON_BASE_SIZES.getOrDefault(library, DEFAULT_ICON_BASE_SIZE);
    }
}
