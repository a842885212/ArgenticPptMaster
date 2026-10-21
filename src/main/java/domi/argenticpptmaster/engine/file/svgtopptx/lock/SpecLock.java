// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code spec_lock.md} fields required for flat-release SVG→PPTX export.
 */
public final class SpecLock {

    private static final Pattern LOCK_ROW = Pattern.compile("^\\-\\s+([A-Za-z0-9_]+)\\s*:\\s*(.+?)\\s*$");

    public record ThemeColorSpec(Map<String, String> slots, Map<String, String> roles, Map<String, String> roleSlots) {
        public String schemeFor(String color, String usage) {
            String normalized = normalizeHex(color);
            if (normalized == null) {
                return null;
            }
            for (String role : usageRoleOrder(usage)) {
                if (normalized.equals(roles.get(role))) {
                    String slot = roleSlots.get(role);
                    if (slot != null && normalized.equals(slots.get(slot))) {
                        return slot;
                    }
                }
            }
            return null;
        }
    }

    public record ThemeFontFace(String latin, String ea, String cs) {
        public boolean matches(String latinFace, String eaFace) {
            return latin.equals(latinFace) && ea.equals(eaFace);
        }
    }

    public record ThemeFontSpec(ThemeFontFace major, ThemeFontFace minor, String majorFamily, String minorFamily) {}

    public record Parsed(
            String viewBox,
            String format,
            String primaryLanguage,
            String pptxStructureMode,
            ThemeColorSpec colors,
            ThemeFontSpec fonts,
            Integer titlePx,
            Integer bodyPx) {}

    private SpecLock() {}

    public static Parsed load(Path project) throws IOException {
        Path lock = project.resolve("spec_lock.md");
        if (!Files.isRegularFile(lock)) {
            throw new IllegalStateException("spec_lock.md is required for release SVG export");
        }
        Map<String, Map<String, String>> sections = parseSections(Files.readString(lock));
        Map<String, String> canvas = sections.getOrDefault("canvas", Map.of());
        Map<String, String> communication = sections.getOrDefault("communication", Map.of());
        Map<String, String> structure = sections.getOrDefault("pptx_structure", Map.of());
        Map<String, String> typography = sections.getOrDefault("typography", Map.of());
        Map<String, String> colors = sections.getOrDefault("colors", Map.of());

        String viewBox = canvas.get("viewBox");
        if (viewBox == null || viewBox.isBlank()) {
            throw new IllegalStateException("spec_lock.md must contain canvas.viewBox for release export");
        }
        String mode = structure.getOrDefault("mode", "flat");
        if (!"flat".equals(mode)) {
            throw new IllegalStateException(
                    "unsupported spec_lock.md pptx_structure.mode " + mode
                            + " (Step 6 Java authority covers flat only)");
        }
        ThemeColorSpec colorSpec = buildColorSpec(colors);
        if (colorSpec == null) {
            throw new IllegalStateException(
                    "flat export requires a current-project theme contract in spec_lock.md; missing: colors");
        }
        ThemeFontSpec fontSpec = buildFontSpec(typography);
        if (fontSpec == null) {
            throw new IllegalStateException(
                    "flat export requires typography font_family/title_family/body_family in spec_lock.md");
        }
        Integer titlePx = parseIntOrNull(typography.get("title"));
        Integer bodyPx = parseIntOrNull(typography.get("body"));
        return new Parsed(
                viewBox.trim(),
                canvas.get("format"),
                communication.get("primary_language"),
                mode,
                colorSpec,
                fontSpec,
                titlePx,
                bodyPx);
    }

    private static ThemeFontSpec buildFontSpec(Map<String, String> rows) {
        String defaultFamily = rows.get("font_family");
        String majorFamily = firstNonBlank(rows.get("title_family"), defaultFamily);
        String minorFamily = firstNonBlank(rows.get("body_family"), defaultFamily);
        if (majorFamily == null || minorFamily == null) {
            return null;
        }
        return new ThemeFontSpec(fontFace(majorFamily), fontFace(minorFamily), majorFamily, minorFamily);
    }

    private static ThemeFontFace fontFace(String family) {
        String face = family.split(",")[0].trim().replace("\"", "").replace("'", "");
        // Microsoft YaHei and other EA families fill both latin and ea slots.
        return new ThemeFontFace(face, face, face);
    }

    private static ThemeColorSpec buildColorSpec(Map<String, String> rolesRaw) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rolesRaw.entrySet()) {
            String hex = normalizeHex(entry.getValue());
            if (hex != null) {
                roles.put(entry.getKey(), hex);
            }
        }
        if (roles.isEmpty()) {
            return null;
        }
        Map<String, String> roleSlots = Map.ofEntries(
                Map.entry("bg", "lt1"),
                Map.entry("background", "lt1"),
                Map.entry("master_bg", "lt1"),
                Map.entry("secondary_bg", "lt2"),
                Map.entry("bg_secondary", "lt2"),
                Map.entry("text", "dk1"),
                Map.entry("body_text", "dk1"),
                Map.entry("text_secondary", "dk2"),
                Map.entry("primary", "accent1"),
                Map.entry("accent", "accent2"),
                Map.entry("secondary_accent", "accent3"),
                Map.entry("border", "accent4"));
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("dk1", firstColor(roles, "000000", "text", "body_text", "primary"));
        slots.put("lt1", firstColor(roles, "FFFFFF", "bg", "background", "master_bg"));
        slots.put("dk2", firstColor(roles, slots.get("dk1"), "text_secondary", "text"));
        slots.put("lt2", firstColor(roles, slots.get("lt1"), "secondary_bg", "bg_secondary", "bg", "background"));
        slots.put("accent1", firstColor(roles, "4F81BD", "primary", "accent"));
        slots.put("accent2", firstColor(roles, "C0504D", "accent", "secondary_accent", "primary"));
        slots.put("accent3", firstColor(roles, slots.get("accent2"), "secondary_accent", "accent"));
        slots.put("accent4", firstColor(roles, slots.get("accent1"), "border", "primary"));
        slots.put("accent5", "4BACC6");
        slots.put("accent6", "F79646");
        slots.put("hlink", firstColor(roles, slots.get("accent2"), "accent", "primary"));
        slots.put("folHlink", firstColor(roles, slots.get("accent2"), "secondary_accent", "accent"));
        return new ThemeColorSpec(Map.copyOf(slots), Map.copyOf(roles), Map.copyOf(roleSlots));
    }

    private static String firstColor(Map<String, String> roles, String defaultValue, String... keys) {
        for (String key : keys) {
            if (roles.containsKey(key)) {
                return roles.get(key);
            }
        }
        return defaultValue;
    }

    private static String[] usageRoleOrder(String usage) {
        return switch (usage) {
            case "background" -> new String[] {
                "bg", "background", "master_bg", "secondary_bg", "bg_secondary", "primary", "accent", "secondary_accent"
            };
            case "fill" -> new String[] {
                "primary", "accent", "secondary_accent", "bg", "background", "master_bg", "secondary_bg",
                "bg_secondary", "text", "body_text", "text_secondary", "border"
            };
            case "text" -> new String[] {
                "text", "body_text", "text_secondary", "primary", "accent", "secondary_accent"
            };
            case "stroke" -> new String[] {
                "border", "primary", "accent", "secondary_accent", "text", "body_text", "text_secondary"
            };
            default -> new String[] {"primary", "accent", "text", "bg"};
        };
    }

    public static String normalizeHex(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() >= 2
                && value.charAt(0) == value.charAt(value.length() - 1)
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if ("transparent".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 3) {
            value = "" + value.charAt(0) + value.charAt(0)
                    + value.charAt(1) + value.charAt(1)
                    + value.charAt(2) + value.charAt(2);
        }
        if (value.length() == 8) {
            value = value.substring(0, 6);
        }
        if (!value.matches("(?i)[0-9a-f]{6}")) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Map<String, String>> parseSections(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                sections.putIfAbsent(current, new LinkedHashMap<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher matcher = LOCK_ROW.matcher(line);
            if (matcher.matches()) {
                sections.get(current).put(matcher.group(1), matcher.group(2));
            }
        }
        return sections;
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
