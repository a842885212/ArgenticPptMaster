// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.drawingml;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import org.w3c.dom.Element;

/** Fill / stroke DrawingML fragments for SVG geometry. */
public final class FillStroke {

    private FillStroke() {}

    public static String solidFillXml(String color, SpecLock.ThemeColorSpec spec, String usage) {
        String hex = SpecLock.normalizeHex(color);
        if (hex == null) {
            return "<a:noFill/>";
        }
        String scheme = spec == null ? null : spec.schemeFor(hex, usage);
        if (scheme != null) {
            return "<a:solidFill><a:schemeClr val=\"" + scheme + "\"></a:schemeClr></a:solidFill>";
        }
        return "<a:solidFill><a:srgbClr val=\"" + hex + "\"></a:srgbClr></a:solidFill>";
    }

    public static String colorNode(String color, SpecLock.ThemeColorSpec spec, String usage) {
        String hex = SpecLock.normalizeHex(color);
        if (hex == null) {
            return "<a:srgbClr val=\"000000\"></a:srgbClr>";
        }
        String scheme = spec == null ? null : spec.schemeFor(hex, usage);
        if (scheme != null) {
            return "<a:schemeClr val=\"" + scheme + "\"></a:schemeClr>";
        }
        return "<a:srgbClr val=\"" + hex + "\"></a:srgbClr>";
    }

    public static String resolvePaint(Element elem, ConvertContext ctx, String attr, String styleKey) {
        String value = elem.getAttribute(attr);
        if (value == null || value.isBlank()) {
            value = ctx.style(styleKey != null ? styleKey : attr);
        }
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    public static String fillXml(Element elem, ConvertContext ctx, String usage) {
        String fill = resolvePaint(elem, ctx, "fill", "fill");
        if (fill == null) {
            // SVG default fill is black when unspecified for shapes.
            if (!elem.hasAttribute("fill") && ctx.style("fill") == null) {
                return solidFillXml("#000000", ctx.themeColors(), usage);
            }
            return "<a:noFill/>";
        }
        return solidFillXml(fill, ctx.themeColors(), usage);
    }

    public static String lineXml(Element elem, ConvertContext ctx) {
        String stroke = resolvePaint(elem, ctx, "stroke", "stroke");
        if (stroke == null) {
            return "<a:ln><a:noFill/></a:ln>";
        }
        String widthRaw = elem.getAttribute("stroke-width");
        if (widthRaw == null || widthRaw.isBlank()) {
            widthRaw = ctx.style("stroke-width");
        }
        double widthPx = 1.0;
        if (widthRaw != null && !widthRaw.isBlank()) {
            widthPx = Double.parseDouble(widthRaw.replace("px", "").trim());
        }
        double scale = (Math.abs(ctx.mapW(100) / 100.0) + Math.abs(ctx.mapH(100) / 100.0)) / 2.0;
        int widthEmu = Math.max(1, Emu.pxToEmu(widthPx * scale));
        return "<a:ln w=\"" + widthEmu + "\">\n"
                + solidFillXml(stroke, ctx.themeColors(), "stroke")
                + "\n</a:ln>";
    }

    /** Stroke line for shapes that pin emits as noFill when stroke absent. */
    public static String defaultNoStrokeLine() {
        return "<a:ln><a:noFill/></a:ln>";
    }
}
