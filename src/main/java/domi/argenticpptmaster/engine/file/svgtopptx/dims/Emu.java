// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.dims;

/** SVG px ↔ DrawingML EMU / hundredths-of-a-point helpers. */
public final class Emu {

    /** 1 SVG px = 9525 EMU (96 DPI). */
    public static final int EMU_PER_PX = 9525;

    /** 1px = 0.75pt → 75 hundredths-of-a-point. */
    public static final int FONT_PX_TO_HUNDREDTHS_PT = 75;

    public static final int DRAWINGML_TEXT_FONT_SIZE_MIN = 100;
    public static final int DRAWINGML_TEXT_FONT_SIZE_MAX = 400_000;

    private Emu() {}

    public static int pxToEmu(double px) {
        return (int) Math.round(px * EMU_PER_PX);
    }

    public static int fontPxToHpt(double fontSizePx) {
        double scaled = fontSizePx * FONT_PX_TO_HUNDREDTHS_PT;
        if (!Double.isFinite(scaled)) {
            throw new IllegalArgumentException("SVG font-size must be finite: " + fontSizePx);
        }
        int size = (int) Math.round(scaled / 10.0) * 10;
        if (size < DRAWINGML_TEXT_FONT_SIZE_MIN || size > DRAWINGML_TEXT_FONT_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "SVG font-size " + fontSizePx + "px converts to DrawingML sz=" + size);
        }
        return size;
    }
}
