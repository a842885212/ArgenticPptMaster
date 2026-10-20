// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canvas viewBox strings for the partial Java SVG quality checker (from config.py). */
final class SvgQualityCanvasFormats {

    record Format(String viewBox) {}

    private static final Map<String, String> ALIASES = Map.of(
            "xhs", "xiaohongshu",
            "wechat_moment", "moments",
            "wechat-moment", "moments",
            "朋友圈", "moments",
            "小红书", "xiaohongshu");

    private static final Map<String, Format> FORMATS = buildFormats();

    private SvgQualityCanvasFormats() {}

    private static Map<String, Format> buildFormats() {
        Map<String, Format> map = new LinkedHashMap<>();
        map.put("ppt169", new Format("0 0 1280 720"));
        map.put("ppt43", new Format("0 0 1024 768"));
        map.put("wechat", new Format("0 0 900 383"));
        map.put("xiaohongshu", new Format("0 0 1242 1660"));
        map.put("moments", new Format("0 0 1080 1080"));
        map.put("story", new Format("0 0 1080 1920"));
        map.put("banner", new Format("0 0 1920 1080"));
        map.put("a4", new Format("0 0 1240 1754"));
        return Map.copyOf(map);
    }

    static Set<String> keys() {
        return FORMATS.keySet();
    }

    static String normalizeFormatKey(String formatKey) {
        if (formatKey == null) {
            return "";
        }
        String trimmed = formatKey.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, ALIASES.getOrDefault(trimmed, lower));
    }

    static Optional<Format> resolve(String formatKey) {
        String normalized = normalizeFormatKey(formatKey);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(FORMATS.get(normalized));
    }

    static String sortedKeysList() {
        return String.join(", ", keys().stream().sorted().toList());
    }
}
