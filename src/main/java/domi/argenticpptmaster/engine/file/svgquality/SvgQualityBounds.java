// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.Locale;
import java.util.regex.Pattern;

/** Bounds parse / overflow helpers from pin {@code checker.py}. */
final class SvgQualityBounds {

    static final String BOUNDS_ATTR = "data-pptx-bounds";
    static final String MORPH_STAGING_ATTR = "data-pptx-morph-staging";
    static final double OVERFLOW_TOLERANCE = 1.0;
    static final double OVERFLOW_ERROR_RATIO = 0.05;

    private static final Pattern VIEWBOX_SPLIT = Pattern.compile("[\\s,]+");

    record Rect(double left, double top, double right, double bottom) {
        double width() {
            return right - left;
        }

        double height() {
            return bottom - top;
        }
    }

    record OverflowMetrics(String axes, double horizontalRatio, double verticalRatio) {}

    private SvgQualityBounds() {}

    static double[] parsePositiveBounds(String value) {
        String[] raw = VIEWBOX_SPLIT.split(value.strip());
        if (raw.length != 4) {
            throw new IllegalArgumentException("must contain exactly four numbers: x y width height");
        }
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Double.parseDouble(raw[i]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("must contain only numeric values", ex);
            }
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException("must contain only finite values");
            }
        }
        if (values[2] <= 0 || values[3] <= 0) {
            throw new IllegalArgumentException("must use positive width and height");
        }
        return values;
    }

    static Rect parseViewBoxCanvas(String viewBox) {
        if (viewBox == null || viewBox.isBlank()) {
            return null;
        }
        String[] raw = VIEWBOX_SPLIT.split(viewBox.strip());
        if (raw.length != 4) {
            return null;
        }
        try {
            double x = Double.parseDouble(raw[0]);
            double y = Double.parseDouble(raw[1]);
            double w = Double.parseDouble(raw[2]);
            double h = Double.parseDouble(raw[3]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h)) {
                return null;
            }
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new Rect(x, y, x + w, y + h);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Rect fromXywh(double x, double y, double w, double h) {
        return new Rect(x, y, x + w, y + h);
    }

    static boolean areDisjoint(Rect first, Rect second) {
        return first.right() <= second.left()
                || first.left() >= second.right()
                || first.bottom() <= second.top()
                || first.top() >= second.bottom();
    }

    static double[] overlapDimensions(Rect first, Rect second) {
        return new double[] {
            Math.max(Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()), 0.0),
            Math.max(Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()), 0.0)
        };
    }

    static OverflowMetrics overflowMetrics(Rect inner, Rect outer) {
        double leftOverflow = Math.max(outer.left() - inner.left(), 0.0);
        double rightOverflow = Math.max(inner.right() - outer.right(), 0.0);
        double topOverflow = Math.max(outer.top() - inner.top(), 0.0);
        double bottomOverflow = Math.max(inner.bottom() - outer.bottom(), 0.0);
        boolean horizontal = leftOverflow > OVERFLOW_TOLERANCE || rightOverflow > OVERFLOW_TOLERANCE;
        boolean vertical = topOverflow > OVERFLOW_TOLERANCE || bottomOverflow > OVERFLOW_TOLERANCE;
        if (!horizontal && !vertical) {
            return null;
        }
        double outerWidth = outer.width();
        double outerHeight = outer.height();
        if (outerWidth <= 0.0 || outerHeight <= 0.0) {
            return null;
        }
        double horizontalRatio = horizontal ? Math.max(leftOverflow, rightOverflow) / outerWidth : 0.0;
        double verticalRatio = vertical ? Math.max(topOverflow, bottomOverflow) / outerHeight : 0.0;
        String axes;
        if (horizontal && vertical) {
            axes = "horizontal and vertical";
        } else if (horizontal) {
            axes = "horizontal";
        } else {
            axes = "vertical";
        }
        return new OverflowMetrics(axes, horizontalRatio, verticalRatio);
    }

    static boolean exceedsErrorRatio(OverflowMetrics metrics) {
        double ratio = Math.max(metrics.horizontalRatio(), metrics.verticalRatio());
        return ratio > OVERFLOW_ERROR_RATIO
                && Math.abs(ratio - OVERFLOW_ERROR_RATIO) > 1e-9;
    }

    static String formatOverflow(
            String subject,
            String container,
            Rect inner,
            Rect outer,
            OverflowMetrics metrics,
            String repair) {
        return String.format(
                Locale.ROOT,
                "%s exceeds %s on the %s axis: content (%.1f, %.1f)-(%.1f, %.1f), container (%.1f, %.1f)-(%.1f, %.1f), "
                        + "overflow horizontal %.1f%%, vertical %.1f%%; %s",
                subject,
                container,
                metrics.axes(),
                inner.left(),
                inner.top(),
                inner.right(),
                inner.bottom(),
                outer.left(),
                outer.top(),
                outer.right(),
                outer.bottom(),
                metrics.horizontalRatio() * 100.0,
                metrics.verticalRatio() * 100.0,
                repair);
    }
}
