// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Affine transform helpers for preset / text bounds (pin {@code drawingml.utils}
 * matrix / DML validation subset).
 */
final class SvgQualityAffine {

    static final long OOXML_COORDINATE_MIN = -27_273_042_329_600L;
    static final long OOXML_COORDINATE_MAX = 27_273_042_316_900L;

    private static final Pattern OP = Pattern.compile(
            "(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");

    /** SVG affine matrix {@code (a b c d e f)}. */
    record Matrix(double a, double b, double c, double d, double e, double f) {
        static final Matrix IDENTITY = new Matrix(1, 0, 0, 1, 0, 0);
    }

    private SvgQualityAffine() {}

    static Matrix multiply(Matrix left, Matrix right) {
        return new Matrix(
                left.a * right.a + left.c * right.b,
                left.b * right.a + left.d * right.b,
                left.a * right.c + left.c * right.d,
                left.b * right.c + left.d * right.d,
                left.a * right.e + left.c * right.f + left.e,
                left.b * right.e + left.d * right.f + left.f);
    }

    static double[] transformPoint(Matrix matrix, double x, double y) {
        return new double[] {
            matrix.a * x + matrix.c * y + matrix.e,
            matrix.b * x + matrix.d * y + matrix.f
        };
    }

    static Matrix parseTransformMatrix(String transform) {
        if (transform == null || transform.isBlank()) {
            return Matrix.IDENTITY;
        }
        Matrix matrix = Matrix.IDENTITY;
        Matcher matcher = OP.matcher(transform);
        int end = 0;
        int found = 0;
        while (matcher.find()) {
            if (matcher.start() > end) {
                String gap = transform.substring(end, matcher.start()).strip();
                if (!gap.isEmpty()) {
                    throw new IllegalArgumentException("unsupported transform fragment '" + gap + "'");
                }
            }
            found++;
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            List<Double> args = new ArrayList<>();
            Matcher num = NUMBER.matcher(matcher.group(2));
            while (num.find()) {
                double value = Double.parseDouble(num.group());
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("transform numbers must be finite");
                }
                args.add(value);
            }
            matrix = multiply(matrix, localMatrix(name, args));
            end = matcher.end();
        }
        if (found == 0) {
            throw new IllegalArgumentException("SVG transform must not be empty");
        }
        String trailing = transform.substring(end).strip();
        if (!trailing.isEmpty()) {
            throw new IllegalArgumentException("unsupported transform fragment '" + trailing + "'");
        }
        return matrix;
    }

    static Matrix accumulatedTransform(Element element, java.util.Map<Element, Element> parents) {
        List<Element> chain = new ArrayList<>();
        Element current = element;
        while (current != null) {
            chain.add(current);
            current = parents.get(current);
        }
        Matrix matrix = Matrix.IDENTITY;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Element node = chain.get(i);
            if (!node.hasAttribute("transform")) {
                continue;
            }
            matrix = multiply(matrix, parseTransformMatrix(node.getAttribute("transform")));
        }
        return matrix;
    }

    static void validateDmlShapeMatrix(Matrix matrix) {
        if (!Double.isFinite(matrix.a)
                || !Double.isFinite(matrix.b)
                || !Double.isFinite(matrix.c)
                || !Double.isFinite(matrix.d)
                || !Double.isFinite(matrix.e)
                || !Double.isFinite(matrix.f)) {
            throw new IllegalArgumentException("SVG transform produces non-finite matrix values");
        }
        double xLength = Math.hypot(matrix.a, matrix.b);
        double yLength = Math.hypot(matrix.c, matrix.d);
        if (!Double.isFinite(xLength) || !Double.isFinite(yLength)) {
            throw new IllegalArgumentException("SVG transform produces non-finite axis lengths");
        }
        if (xLength <= 1e-12 || yLength <= 1e-12) {
            throw new IllegalArgumentException(
                    "SVG zero-scale transform cannot be represented by a visible DrawingML shape");
        }
        double normalizedDot =
                (matrix.a / xLength) * (matrix.c / yLength) + (matrix.b / xLength) * (matrix.d / yLength);
        if (!Double.isFinite(normalizedDot) || Math.abs(normalizedDot) > 1e-9) {
            throw new IllegalArgumentException(
                    "SVG shear/skew cannot be represented by a DrawingML shape transform");
        }
    }

    static void validateRectToDmlXfrm(
            double x, double y, double w, double h, Matrix matrix, boolean preserveDegenerateAxes) {
        double[] p0 = transformPoint(matrix, x, y);
        double[] p1 = transformPoint(matrix, x + w, y);
        double[] p3 = transformPoint(matrix, x, y + h);
        double ux = p1[0] - p0[0];
        double uy = p1[1] - p0[1];
        double vx = p3[0] - p0[0];
        double vy = p3[1] - p0[1];
        double rectW = Math.hypot(ux, uy);
        double rectH = Math.hypot(vx, vy);
        validateDmlShapeMatrix(matrix);
        if (!preserveDegenerateAxes) {
            rectW = Math.max(rectW, 0.001);
            rectH = Math.max(rectH, 0.001);
        }
        double[] p2 = transformPoint(matrix, x + w, y + h);
        double centerX = (p0[0] + p2[0]) / 2;
        double centerY = (p0[1] + p2[1]) / 2;
        int offX = Emu.pxToEmu(centerX - rectW / 2);
        int offY = Emu.pxToEmu(centerY - rectH / 2);
        int extCx = Emu.pxToEmu(rectW);
        int extCy = Emu.pxToEmu(rectH);
        validateOoxmlXfrm(offX, offY, extCx, extCy);
    }

    static void validateOoxmlXfrm(int offX, int offY, int extCx, int extCy) {
        for (long value : new long[] {offX, offY}) {
            if (value < OOXML_COORDINATE_MIN || value > OOXML_COORDINATE_MAX) {
                throw new IllegalArgumentException(
                        "DrawingML xfrm offset is outside the OOXML coordinate range");
            }
        }
        for (long value : new long[] {extCx, extCy}) {
            if (value < 0 || value > OOXML_COORDINATE_MAX) {
                throw new IllegalArgumentException(
                        "DrawingML xfrm extent is outside the OOXML positive-coordinate range");
            }
        }
    }

    static SvgQualityBounds.Rect transformedRectBounds(
            Element element, SvgQualityBounds.Rect local, java.util.Map<Element, Element> parents) {
        Matrix matrix = accumulatedTransform(element, parents);
        double[][] corners = {
            transformPoint(matrix, local.left(), local.top()),
            transformPoint(matrix, local.right(), local.top()),
            transformPoint(matrix, local.right(), local.bottom()),
            transformPoint(matrix, local.left(), local.bottom())
        };
        double minX = corners[0][0];
        double maxX = corners[0][0];
        double minY = corners[0][1];
        double maxY = corners[0][1];
        for (int i = 1; i < corners.length; i++) {
            minX = Math.min(minX, corners[i][0]);
            maxX = Math.max(maxX, corners[i][0]);
            minY = Math.min(minY, corners[i][1]);
            maxY = Math.max(maxY, corners[i][1]);
        }
        return new SvgQualityBounds.Rect(minX, minY, maxX, maxY);
    }

    private static Matrix localMatrix(String name, List<Double> args) {
        return switch (name) {
            case "matrix" -> {
                if (args.size() != 6) {
                    throw new IllegalArgumentException("transform matrix has invalid argument count");
                }
                yield new Matrix(args.get(0), args.get(1), args.get(2), args.get(3), args.get(4), args.get(5));
            }
            case "translate" -> {
                if (args.size() != 1 && args.size() != 2) {
                    throw new IllegalArgumentException("transform translate has invalid argument count");
                }
                yield new Matrix(1, 0, 0, 1, args.get(0), args.size() > 1 ? args.get(1) : 0.0);
            }
            case "scale" -> {
                if (args.size() != 1 && args.size() != 2) {
                    throw new IllegalArgumentException("transform scale has invalid argument count");
                }
                double sx = args.get(0);
                double sy = args.size() > 1 ? args.get(1) : sx;
                yield new Matrix(sx, 0, 0, sy, 0, 0);
            }
            case "rotate" -> {
                if (args.size() != 1 && args.size() != 3) {
                    throw new IllegalArgumentException("transform rotate has invalid argument count");
                }
                double angle = Math.toRadians(args.get(0));
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                Matrix rot = new Matrix(cos, sin, -sin, cos, 0, 0);
                if (args.size() == 1) {
                    yield rot;
                }
                double cx = args.get(1);
                double cy = args.get(2);
                yield multiply(multiply(new Matrix(1, 0, 0, 1, cx, cy), rot), new Matrix(1, 0, 0, 1, -cx, -cy));
            }
            case "skewx", "skewy" -> throw new IllegalArgumentException(
                    "SVG shear/skew cannot be represented by a DrawingML shape transform");
            default -> throw new IllegalArgumentException("unsupported transform " + name);
        };
    }
}
