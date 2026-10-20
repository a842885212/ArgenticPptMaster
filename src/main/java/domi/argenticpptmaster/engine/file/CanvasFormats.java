// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Registered canvas formats and alias normalization (from config.py / project_utils.py). */
public final class CanvasFormats {

    public static final String DEFERRED_CANVAS_MESSAGE =
            "Canvas is determined during authoring and recorded in spec_lock.md "
                    + "(Default) or the first SVG (Quick).";

    public record CanvasFormat(String key, String name, String dimensions) {}

    private static final Map<String, String> ALIASES = Map.of(
            "xhs", "xiaohongshu",
            "wechat_moment", "moments",
            "wechat-moment", "moments",
            "朋友圈", "moments",
            "小红书", "xiaohongshu");

    private static final Map<String, CanvasFormat> FORMATS = buildFormats();

    private CanvasFormats() {}

    private static Map<String, CanvasFormat> buildFormats() {
        Map<String, CanvasFormat> map = new LinkedHashMap<>();
        map.put("ppt169", new CanvasFormat("ppt169", "PPT 16:9", "1280×720"));
        map.put("ppt43", new CanvasFormat("ppt43", "PPT 4:3", "1024×768"));
        map.put("wechat", new CanvasFormat("wechat", "WeChat Article Header", "900×383"));
        map.put("xiaohongshu", new CanvasFormat("xiaohongshu", "小红书", "1242×1660"));
        map.put("moments", new CanvasFormat("moments", "Moments/Instagram", "1080×1080"));
        map.put("story", new CanvasFormat("story", "Story/Vertical", "1080×1920"));
        map.put("banner", new CanvasFormat("banner", "Horizontal Banner", "1920×1080"));
        map.put("a4", new CanvasFormat("a4", "A4 Print", "1240×1754"));
        return Map.copyOf(map);
    }

    public static Set<String> keys() {
        return FORMATS.keySet();
    }

    public static Optional<CanvasFormat> get(String normalizedKey) {
        return Optional.ofNullable(FORMATS.get(normalizedKey));
    }

    public static boolean isKnown(String normalizedKey) {
        return FORMATS.containsKey(normalizedKey);
    }

    public static String normalize(String formatKey) {
        if (formatKey == null || formatKey.isBlank()) {
            return "";
        }
        String key = formatKey.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }

    public static String sortedKeysList() {
        return String.join(", ", FORMATS.keySet().stream().sorted().toList());
    }
}
