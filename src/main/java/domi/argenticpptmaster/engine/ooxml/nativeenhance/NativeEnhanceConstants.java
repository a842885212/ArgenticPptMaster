// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Constants for native PPTX enhancement (notes / audio / timings / transitions). */
public final class NativeEnhanceConstants {

    public static final String PROJECT_SCHEMA = "native_pptx_enhancement_project.v1";
    public static final String LEGACY_PROJECT_SCHEMA = "native_narration_pptx_project.v1";
    public static final String PLAN_SCHEMA = "native_pptx_enhancement_plan.v1";
    public static final String VALIDATION_SCHEMA = "native_pptx_enhancement_validation.v1";
    public static final String SLIDE_INDEX_SCHEMA = "native_pptx_enhancement_slide_index.v1";

    public static final Set<String> WRITABLE_MODULES = Set.of("notes", "audio", "timings", "transitions");

    public static final String MEDIA_REL_TYPE =
            "http://schemas.microsoft.com/office/2007/relationships/media";
    public static final String AUDIO_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/audio";
    public static final String IMAGE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";

    public static final String P14_NS = "http://schemas.microsoft.com/office/powerpoint/2010/main";
    public static final String MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    public static final Map<String, String> AUDIO_CONTENT_TYPES = Map.of(
            ".m4a", "audio/mp4",
            ".mp3", "audio/mpeg",
            ".wav", "audio/wav");

    public static final Set<String> NARRATION_EXTENSIONS = AUDIO_CONTENT_TYPES.keySet();

    public static final double DEFAULT_NARRATION_START_FLOOR = 0.8;
    public static final double DEFAULT_NARRATION_PADDING = 0.4;
    public static final double DEFAULT_TRANSITION_DURATION = 0.5;

    public static final long AUDIO_MARKER_SIZE_EMU = 457_200L;
    public static final long AUDIO_MARKER_OFF_CANVAS_EMU = -AUDIO_MARKER_SIZE_EMU;
    public static final long MAX_OOXML_UNSIGNED_INT = 4_294_967_295L;

    public static final byte[] AUDIO_MARKER_PNG_BYTES = Base64.getDecoder()
            .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAABsUlEQVR4nO2aQZaDIAyG"
                            + "cd4cQRdzgHqxeiy9mB5gFvUO7Qon0gAhJEDf+G/a92rJ9ycEqNaYS3XVSQ94uz+esWu"
                            + "2ZRCLKzIQBdqnXDPsL+dA+8Qx88UJpAHPHTfJsRY4Jmo1yBUoCZ8Sj2SgNHxK3KiBWv"
                            + "DU+EEDteGtQhxeA63AW/l4WMuolNa5zx4DNaCd/XXuWfAY15sBTXguOJTL9501GlG50"
                            + "Ovc/xpjzDjtP+5nqj0gkXEL7763OhmQmj4S4FZY1iGnaAUkwQMxTlWouoz65CYBq4LV"
                            + "0cTU6VMgw8frOO3e6273x3Nbhq7JCmCCVYDTqDkDMOuUajdnIFWXgdq6DEgLNm5oGbV"
                            + "qzoBPcOmES+qxkW3L0FE2s1BWJDa5cdqjm5gxf7ddmqyAC4+dQq1EDYzTTpq3mTFO56"
                            + "KTAam7xpJGsOxDTtUpJGEEZhw7lRb5SWlNcJs8dJx+q4DkwwcEJLsiLh86hTRNGMM3g"
                            + "nFVXUYlGt1rQLsKqfLxBCvQiokQR3QK1TYRi0/qgVomKHHJTVzaBDUeC0rzBnBqoljL"
                            + "qFY1OOP+3yf1PpX+r8TH6wW14c3/7xdFRAAAAABJRU5ErkJggg==");

    /** Canonical effect → (element local name, default attrs). Aliases resolved first. */
    public static final Map<String, String> TRANSITION_ALIASES = Map.of("strips", "wipe");

    public static final Map<String, TransitionEffectSpec> TRANSITION_EFFECTS;

    static {
        Map<String, TransitionEffectSpec> effects = new LinkedHashMap<>();
        effects.put("fade", new TransitionEffectSpec("fade", Map.of()));
        effects.put("push", new TransitionEffectSpec("push", Map.of("dir", "r")));
        effects.put("wipe", new TransitionEffectSpec("wipe", Map.of("dir", "r")));
        effects.put("split", new TransitionEffectSpec("split", Map.of("orient", "horz", "dir", "out")));
        effects.put("cover", new TransitionEffectSpec("cover", Map.of("dir", "r")));
        effects.put("random", new TransitionEffectSpec("random", Map.of()));
        TRANSITION_EFFECTS = Map.copyOf(effects);
    }

    private NativeEnhanceConstants() {}

    public record TransitionEffectSpec(String element, Map<String, String> attrs) {}
}
