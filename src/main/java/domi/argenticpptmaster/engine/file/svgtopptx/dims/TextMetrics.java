// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.dims;

import java.util.ArrayList;
import java.util.List;

/**
 * Pin-compatible SVG text width / padding estimates used for DrawingML text frames.
 *
 * <p>Port of {@code drawingml/utils.py} estimate helpers and
 * {@code drawingml/elements.py} textbox padding / headroom.</p>
 */
public final class TextMetrics {

    private static final double TEXTBOX_PADDING_MIN_PX = 0.5;
    private static final double TEXTBOX_PADDING_MAX_PX = 2.0;
    private static final double TEXTBOX_PADDING_RATIO = 0.04;

    private static final double TEXT_WIDTH_HEADROOM_BASE = 1.06;
    private static final double TEXT_WIDTH_HEADROOM_CAPS = 1.12;

    private TextMetrics() {}

    public static double textboxPadding(double fontSize) {
        return Math.max(
                TEXTBOX_PADDING_MIN_PX,
                Math.min(TEXTBOX_PADDING_MAX_PX, fontSize * TEXTBOX_PADDING_RATIO));
    }

    public static boolean isCjkChar(int cp) {
        return (cp >= 0x3400 && cp <= 0x9fff)
                || (cp >= 0xf900 && cp <= 0xfaff)
                || (cp >= 0x2e80 && cp <= 0x2fff)
                || (cp >= 0x3000 && cp <= 0x303f)
                || (cp >= 0x3040 && cp <= 0x30ff)
                || (cp >= 0x3100 && cp <= 0x312f)
                || (cp >= 0x31a0 && cp <= 0x31bf)
                || (cp >= 0x31c0 && cp <= 0x31ef)
                || (cp >= 0xac00 && cp <= 0xd7af)
                || (cp >= 0xff00 && cp <= 0xffef);
    }

    public static double estimateCharacterWidth(int cp, double fontSize) {
        if (cp >= 0xff00 && cp <= 0xffef) {
            // Halfwidth Katakana / forms ≈ 0.5em when East Asian Width is H.
            if (cp >= 0xff61 && cp <= 0xff9f) {
                return fontSize * 0.5;
            }
        }
        if (isCjkChar(cp)) {
            return fontSize;
        }
        if (cp == ' ') {
            return fontSize * 0.3;
        }
        if (cp <= 0xffff) {
            char ch = (char) cp;
            if ("mMwWOQ%".indexOf(ch) >= 0) {
                return fontSize * 0.75;
            }
            if ("iIlj!|".indexOf(ch) >= 0) {
                return fontSize * 0.3;
            }
        }
        if (Character.isDigit(cp)) {
            return fontSize * 0.55;
        }
        return fontSize * 0.55;
    }

    public static List<String> splitClusters(String text) {
        List<String> clusters = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            clusters.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return clusters;
    }

    public static double estimateTextWidth(String text, double fontSize, String fontWeight) {
        List<String> clusters = splitClusters(text);
        double sum = 0;
        boolean bold = fontWeight != null
                && (fontWeight.equals("bold")
                        || fontWeight.equals("600")
                        || fontWeight.equals("700")
                        || fontWeight.equals("800")
                        || fontWeight.equals("900"));
        for (String cluster : clusters) {
            double width = 0;
            boolean cjk = false;
            for (int i = 0; i < cluster.length(); ) {
                int cp = cluster.codePointAt(i);
                width = Math.max(width, estimateCharacterWidth(cp, fontSize));
                if (isCjkChar(cp)) {
                    cjk = true;
                }
                i += Character.charCount(cp);
            }
            if (bold && !cjk) {
                width *= 1.05;
            }
            sum += width;
        }
        return sum;
    }

    /** Headroom-aware single-run width used for DrawingML auto-fit frames. */
    public static double estimateRunWidthWithHeadroom(String text, double fontSize, String fontWeight) {
        double baseWidth = estimateTextWidth(text, fontSize, fontWeight);
        double caps = uppercaseFraction(text);
        double headroom = TEXT_WIDTH_HEADROOM_BASE
                + (TEXT_WIDTH_HEADROOM_CAPS - TEXT_WIDTH_HEADROOM_BASE) * caps;
        return baseWidth * headroom;
    }

    public static double uppercaseFraction(String text) {
        int upper = 0;
        int cased = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp <= 0xffff) {
                char ch = (char) cp;
                char lower = Character.toLowerCase(ch);
                char upperCh = Character.toUpperCase(ch);
                if (lower != upperCh) {
                    cased++;
                    if (Character.isUpperCase(ch)) {
                        upper++;
                    }
                }
            }
            i += Character.charCount(cp);
        }
        return cased == 0 ? 0.0 : (double) upper / cased;
    }

    public static int drawingmlTextFrameWidthEmu(double textWidth, double fontSize) {
        return Emu.pxToEmu(textWidth + textboxPadding(fontSize) * 2);
    }
}
