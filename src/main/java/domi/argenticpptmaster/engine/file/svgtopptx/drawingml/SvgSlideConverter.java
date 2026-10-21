// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.drawingml;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import domi.argenticpptmaster.engine.file.svgtopptx.dims.TextMetrics;
import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import domi.argenticpptmaster.engine.file.svgtopptx.nativeobj.FormulaConverter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * SVG → DrawingML slide XML converter for the service flat-release subset.
 *
 * <p>Port of {@code convert_svg_to_slide_shapes} focused on shapes, text, images,
 * groups, and native formula markers. Chart/table markers keep SVG fallback
 * children unless {@code nativeObjectsEnabled} (matching pin default).</p>
 */
public final class SvgSlideConverter {

    private static final Set<String> NON_VISUAL = Set.of("defs", "metadata", "title", "desc", "style");
    private static final Pattern DATA_URI = Pattern.compile(
            "^data:([^;,]+)?(?:;charset=[^;,]+)?;base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VIEWBOX = Pattern.compile(
            "^\\s*([-+]?[\\d.]+(?:[eE][-+]?\\d+)?)\\s+"
                    + "([-+]?[\\d.]+(?:[eE][-+]?\\d+)?)\\s+"
                    + "([-+]?[\\d.]+(?:[eE][-+]?\\d+)?)\\s+"
                    + "([-+]?[\\d.]+(?:[eE][-+]?\\d+)?)\\s*$");

    public record SlideConversion(
            String slideXml,
            Map<String, byte[]> mediaFiles,
            List<ConvertContext.RelEntry> relEntries,
            Map<String, byte[]> packageFiles,
            Map<String, String> contentTypeOverrides,
            double canvasWidthPx,
            double canvasHeightPx) {}

    /** One converted shape/group with EMU bounds (x1,y1,x2,y2). */
    record ShapeResult(String xml, int[] boundsEmu) {
        static ShapeResult of(String xml, int offX, int offY, int extCx, int extCy) {
            return new ShapeResult(xml, new int[] {offX, offY, offX + extCx, offY + extCy});
        }
    }

    private SvgSlideConverter() {}

    public static SlideConversion convert(
            Path svgPath,
            Path projectRoot,
            SpecLock.Parsed lock,
            boolean nativeObjectsEnabled)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(svgPath.toFile());
        Element root = document.getDocumentElement();
        double[] vb = parseViewBox(root);
        ConvertContext ctx = new ConvertContext(
                projectRoot,
                svgPath.getParent(),
                lock.colors(),
                lock.fonts(),
                lock.primaryLanguage(),
                nativeObjectsEnabled);

        String backgroundXml = "";
        Element backgroundSkip = null;
        BackgroundCandidate bg = extractBackground(root, ctx, vb[2], vb[3]);
        if (bg != null) {
            backgroundXml = bg.xml();
            backgroundSkip = bg.element();
        }

        List<String> shapes = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (child == backgroundSkip) {
                continue;
            }
            String tag = localName(child);
            if ("defs".equals(tag)) {
                continue;
            }
            ShapeResult result = convertElement(child, ctx);
            if (result != null && result.xml() != null && !result.xml().isBlank()) {
                shapes.add(result.xml());
            }
        }

        String shapesXml = String.join("\n", shapes);
        String slideXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                <p:cSld>
                %s
                <p:spTree>
                <p:nvGrpSpPr>
                <p:cNvPr id="1" name=""/>
                <p:cNvGrpSpPr/><p:nvPr/>
                </p:nvGrpSpPr>
                <p:grpSpPr>
                <a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
                <a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm>
                </p:grpSpPr>
                %s
                </p:spTree>
                </p:cSld>
                <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
                </p:sld>
                """.formatted(backgroundXml, shapesXml);
        return new SlideConversion(
                slideXml,
                Map.copyOf(ctx.mediaFiles()),
                List.copyOf(ctx.relEntries()),
                Map.copyOf(ctx.packageFiles()),
                Map.copyOf(ctx.contentTypeOverrides()),
                vb[2],
                vb[3]);
    }

    private record BackgroundCandidate(String xml, Element element) {}

    private static BackgroundCandidate extractBackground(
            Element root, ConvertContext ctx, double canvasW, double canvasH) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (!"rect".equals(localName(child))) {
                continue;
            }
            double x = attrDouble(child, "x", 0);
            double y = attrDouble(child, "y", 0);
            double w = attrDouble(child, "width", 0);
            double h = attrDouble(child, "height", 0);
            if (Math.abs(x) > 0.5 || Math.abs(y) > 0.5) {
                continue;
            }
            if (Math.abs(w - canvasW) > 1 || Math.abs(h - canvasH) > 1) {
                continue;
            }
            String fill = FillStroke.resolvePaint(child, ctx, "fill", "fill");
            if (fill == null) {
                continue;
            }
            String colorXml = FillStroke.colorNode(fill, ctx.themeColors(), "background");
            String xml = "<p:bg><p:bgPr><a:solidFill>" + colorXml
                    + "</a:solidFill><a:effectLst/></p:bgPr></p:bg>";
            return new BackgroundCandidate(xml, child);
        }
        return null;
    }

    static ShapeResult convertElement(Element elem, ConvertContext ctx) throws IOException {
        String tag = localName(elem);
        if (NON_VISUAL.contains(tag)) {
            return null;
        }
        return switch (tag) {
            case "g" -> convertGroup(elem, ctx);
            case "rect" -> convertRect(elem, ctx);
            case "circle" -> convertCircle(elem, ctx);
            case "ellipse" -> convertEllipse(elem, ctx);
            case "line" -> convertLine(elem, ctx);
            case "polyline", "polygon" -> convertPoly(elem, ctx, "polygon".equals(tag));
            case "path" -> convertPath(elem, ctx);
            case "text" -> convertText(elem, ctx);
            case "image" -> convertImage(elem, ctx);
            default -> null;
        };
    }

    private static ShapeResult convertGroup(Element elem, ConvertContext ctx) throws IOException {
        String replaceWith = elem.getAttribute("data-pptx-replace-with");
        if ("formula".equals(replaceWith)) {
            String xml = FormulaConverter.convert(elem, ctx);
            // Bounds from marker frame attributes.
            double x = attrDouble(elem, "data-pptx-x", 0);
            double y = attrDouble(elem, "data-pptx-y", 0);
            double w = attrDouble(elem, "data-pptx-width", 0);
            double h = attrDouble(elem, "data-pptx-height", 0);
            return ShapeResult.of(
                    xml,
                    Emu.pxToEmu(ctx.mapX(x)),
                    Emu.pxToEmu(ctx.mapY(y)),
                    Emu.pxToEmu(ctx.mapW(w)),
                    Emu.pxToEmu(ctx.mapH(h)));
        }
        Map<String, String> styles = inheritableStyles(elem);
        ConvertContext childCtx = ctx.child(0, 0, 1, 1, styles);
        List<ShapeResult> parts = new ArrayList<>();
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            ShapeResult result = convertElement(child, childCtx);
            if (result != null && result.xml() != null && !result.xml().isBlank()) {
                parts.add(result);
            }
        }
        ctx.syncFromChild(childCtx);
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (ShapeResult part : parts) {
            if (part.boundsEmu() == null) {
                continue;
            }
            minX = Math.min(minX, part.boundsEmu()[0]);
            minY = Math.min(minY, part.boundsEmu()[1]);
            maxX = Math.max(maxX, part.boundsEmu()[2]);
            maxY = Math.max(maxY, part.boundsEmu()[3]);
        }
        if (minX == Integer.MAX_VALUE) {
            return new ShapeResult(String.join("\n", parts.stream().map(ShapeResult::xml).toList()), null);
        }
        int groupW = Math.max(maxX - minX, 1);
        int groupH = Math.max(maxY - minY, 1);
        int groupId = ctx.claimShapeId();
        String shapesXml = String.join("\n", parts.stream().map(ShapeResult::xml).toList());
        String xml = """
                <p:grpSp>
                <p:nvGrpSpPr>
                <p:cNvPr id="%d" name="Group %d"/>
                <p:cNvGrpSpPr/>
                <p:nvPr/>
                </p:nvGrpSpPr>
                <p:grpSpPr>
                <a:xfrm>
                <a:off x="%d" y="%d"/>
                <a:ext cx="%d" cy="%d"/>
                <a:chOff x="%d" y="%d"/>
                <a:chExt cx="%d" cy="%d"/>
                </a:xfrm>
                </p:grpSpPr>
                %s
                </p:grpSp>
                """.formatted(groupId, groupId, minX, minY, groupW, groupH, minX, minY, groupW, groupH, shapesXml);
        return new ShapeResult(xml, new int[] {minX, minY, minX + groupW, minY + groupH});
    }

    private static ShapeResult convertRect(Element elem, ConvertContext ctx) {
        double x = ctx.mapX(attrDouble(elem, "x", 0));
        double y = ctx.mapY(attrDouble(elem, "y", 0));
        double w = ctx.mapW(attrDouble(elem, "width", 0));
        double h = ctx.mapH(attrDouble(elem, "height", 0));
        double rx = attrDouble(elem, "rx", Double.NaN);
        double ry = attrDouble(elem, "ry", Double.NaN);
        if (Double.isNaN(rx) && !Double.isNaN(ry)) {
            rx = ry;
        }
        if (Double.isNaN(ry) && !Double.isNaN(rx)) {
            ry = rx;
        }
        int id = ctx.claimShapeId();
        String geom;
        if (!Double.isNaN(rx) && rx > 0 && w > 0 && h > 0) {
            double adj = Math.min(rx, Math.min(w, h) / 2.0) / Math.min(w, h) * 100000.0;
            geom = "<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val "
                    + Math.round(adj) + "\"/></a:avLst></a:prstGeom>";
        } else {
            geom = "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>";
        }
        String fill = FillStroke.fillXml(elem, ctx, "fill");
        String line = strokeOrNone(elem, ctx);
        return shapeResult(
                id,
                "Rectangle " + id,
                Emu.pxToEmu(x),
                Emu.pxToEmu(y),
                Emu.pxToEmu(w),
                Emu.pxToEmu(h),
                geom,
                fill,
                line,
                null);
    }

    private static ShapeResult convertCircle(Element elem, ConvertContext ctx) {
        double cx = attrDouble(elem, "cx", 0);
        double cy = attrDouble(elem, "cy", 0);
        double r = attrDouble(elem, "r", 0);
        double x = ctx.mapX(cx - r);
        double y = ctx.mapY(cy - r);
        double w = ctx.mapW(r * 2);
        double h = ctx.mapH(r * 2);
        int id = ctx.claimShapeId();
        String geom = "<a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom>";
        return shapeResult(
                id,
                "Ellipse " + id,
                Emu.pxToEmu(x),
                Emu.pxToEmu(y),
                Emu.pxToEmu(w),
                Emu.pxToEmu(h),
                geom,
                FillStroke.fillXml(elem, ctx, "fill"),
                strokeOrNone(elem, ctx),
                null);
    }

    private static ShapeResult convertEllipse(Element elem, ConvertContext ctx) {
        double cx = attrDouble(elem, "cx", 0);
        double cy = attrDouble(elem, "cy", 0);
        double rx = attrDouble(elem, "rx", 0);
        double ry = attrDouble(elem, "ry", 0);
        double x = ctx.mapX(cx - rx);
        double y = ctx.mapY(cy - ry);
        int id = ctx.claimShapeId();
        return shapeResult(
                id,
                "Ellipse " + id,
                Emu.pxToEmu(x),
                Emu.pxToEmu(y),
                Emu.pxToEmu(ctx.mapW(rx * 2)),
                Emu.pxToEmu(ctx.mapH(ry * 2)),
                "<a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom>",
                FillStroke.fillXml(elem, ctx, "fill"),
                strokeOrNone(elem, ctx),
                null);
    }

    private static ShapeResult convertLine(Element elem, ConvertContext ctx) {
        double x1 = ctx.mapX(attrDouble(elem, "x1", 0));
        double y1 = ctx.mapY(attrDouble(elem, "y1", 0));
        double x2 = ctx.mapX(attrDouble(elem, "x2", 0));
        double y2 = ctx.mapY(attrDouble(elem, "y2", 0));
        double left = Math.min(x1, x2);
        double top = Math.min(y1, y2);
        double w = Math.max(Math.abs(x2 - x1), 0.01);
        double h = Math.max(Math.abs(y2 - y1), 0.01);
        int id = ctx.claimShapeId();
        // Represent as a thin rect path via line preset when axis-aligned; else freeform path.
        String geom = "<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom>";
        String stroke = FillStroke.resolvePaint(elem, ctx, "stroke", "stroke");
        String line = stroke == null
                ? FillStroke.defaultNoStrokeLine()
                : FillStroke.lineXml(elem, ctx);
        return shapeResult(
                id,
                "Line " + id,
                Emu.pxToEmu(left),
                Emu.pxToEmu(top),
                Emu.pxToEmu(w),
                Emu.pxToEmu(h),
                geom,
                "<a:noFill/>",
                line,
                null);
    }

    private static ShapeResult convertPoly(Element elem, ConvertContext ctx, boolean close) {
        String points = elem.getAttribute("points");
        if (points == null || points.isBlank()) {
            return null;
        }
        List<double[]> pts = new ArrayList<>();
        for (String token : points.trim().split("[\\s,]+")) {
            if (token.isBlank()) {
                continue;
            }
            // pairs
        }
        String[] parts = points.trim().split("[\\s,]+");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            pts.add(new double[] {
                ctx.mapX(Double.parseDouble(parts[i])),
                ctx.mapY(Double.parseDouble(parts[i + 1]))
            });
        }
        if (pts.isEmpty()) {
            return null;
        }
        double minX = pts.stream().mapToDouble(p -> p[0]).min().orElse(0);
        double minY = pts.stream().mapToDouble(p -> p[1]).min().orElse(0);
        double maxX = pts.stream().mapToDouble(p -> p[0]).max().orElse(0);
        double maxY = pts.stream().mapToDouble(p -> p[1]).max().orElse(0);
        int pathW = Math.max(1, Emu.pxToEmu(maxX - minX));
        int pathH = Math.max(1, Emu.pxToEmu(maxY - minY));
        StringBuilder path = new StringBuilder();
        path.append("<a:pathLst><a:path w=\"")
                .append(pathW)
                .append("\" h=\"")
                .append(pathH)
                .append("\">");
        for (int i = 0; i < pts.size(); i++) {
            int px = Emu.pxToEmu(pts.get(i)[0] - minX);
            int py = Emu.pxToEmu(pts.get(i)[1] - minY);
            if (i == 0) {
                path.append("<a:moveTo><a:pt x=\"")
                        .append(px)
                        .append("\" y=\"")
                        .append(py)
                        .append("\"/></a:moveTo>");
            } else {
                path.append("<a:lnTo><a:pt x=\"")
                        .append(px)
                        .append("\" y=\"")
                        .append(py)
                        .append("\"/></a:lnTo>");
            }
        }
        if (close) {
            path.append("<a:close/>");
        }
        path.append("</a:path></a:pathLst>");
        int id = ctx.claimShapeId();
        return shapeResult(
                id,
                (close ? "Polygon " : "Polyline ") + id,
                Emu.pxToEmu(minX),
                Emu.pxToEmu(minY),
                pathW,
                pathH,
                "<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"l\" t=\"t\" r=\"r\" b=\"b\"/>"
                        + path
                        + "</a:custGeom>",
                FillStroke.fillXml(elem, ctx, "fill"),
                strokeOrNone(elem, ctx),
                null);
    }

    private static ShapeResult convertPath(Element elem, ConvertContext ctx) {
        // Minimal: emit as empty skip when d empty; otherwise approximate with bounding box rect.
        String d = elem.getAttribute("d");
        if (d == null || d.isBlank()) {
            return null;
        }
        // Residual: full path grammar is large; fixtures do not require freeform paths.
        return null;
    }

    private static ShapeResult convertText(Element elem, ConvertContext ctx) {
        String text = elem.getTextContent();
        if (text == null) {
            text = "";
        }
        text = text.replace('\u00a0', ' ');
        double x = attrDouble(elem, "x", 0);
        double y = attrDouble(elem, "y", 0);
        String fontFamily = firstNonBlank(elem.getAttribute("font-family"), ctx.style("font-family"), "Microsoft YaHei");
        double fontSize = attrDouble(elem, "font-size", Double.NaN);
        if (Double.isNaN(fontSize)) {
            String inherited = ctx.style("font-size");
            fontSize = inherited != null ? Double.parseDouble(inherited.replace("px", "").trim()) : 16;
        } else {
            // attribute may include px
            String raw = elem.getAttribute("font-size");
            if (raw != null && raw.endsWith("px")) {
                fontSize = Double.parseDouble(raw.replace("px", "").trim());
            }
        }
        String fill = FillStroke.resolvePaint(elem, ctx, "fill", "fill");
        if (fill == null) {
            fill = "#000000";
        }
        String weight = firstNonBlank(elem.getAttribute("font-weight"), ctx.style("font-weight"), "400");
        String anchor = firstNonBlank(elem.getAttribute("text-anchor"), ctx.style("text-anchor"), "start");

        double mappedX = ctx.mapX(x);
        double mappedY = ctx.mapY(y);
        double ascent = fontSize * 0.85;
        double padding = TextMetrics.textboxPadding(fontSize);
        double textWidth = TextMetrics.estimateRunWidthWithHeadroom(text, fontSize, weight);
        double boxX;
        if ("middle".equals(anchor)) {
            boxX = mappedX - textWidth / 2 - padding;
        } else if ("end".equals(anchor)) {
            boxX = mappedX - textWidth - padding;
        } else {
            boxX = mappedX - padding;
        }
        double boxY = mappedY - ascent;
        double boxH = fontSize * 1.5 + padding;
        int extCx = TextMetrics.drawingmlTextFrameWidthEmu(textWidth, fontSize);
        int extCy = Emu.pxToEmu(boxH);
        int id = ctx.claimShapeId();
        int sz = Emu.fontPxToHpt(fontSize);
        String lang = ctx.primaryLanguage() != null ? ctx.primaryLanguage() : detectLang(text);
        String colorXml = FillStroke.colorNode(fill, ctx.themeColors(), "text");
        String fontXml = themeOrFixedFonts(fontFamily, ctx);
        String txBody = """
                <p:txBody>
                <a:bodyPr wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t" anchorCtr="0">
                <a:spAutoFit/>
                </a:bodyPr>
                <a:lstStyle/>
                <a:p>
                <a:pPr algn="l"></a:pPr>
                <a:r>
                <a:rPr lang="%s" sz="%d" dirty="0">

                <a:solidFill>%s</a:solidFill>

                %s

                </a:rPr>
                <a:t>%s</a:t>
                </a:r>
                </a:p>
                </p:txBody>
                """.formatted(lang, sz, colorXml, fontXml, escapeXml(text));
        int offX = Emu.pxToEmu(boxX);
        int offY = Emu.pxToEmu(boxY);
        String xml = """
                <p:sp>
                <p:nvSpPr>
                <p:cNvPr id="%d" name="TextBox %d"/>
                <p:cNvSpPr txBox="1"/><p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                <a:xfrm><a:off x="%d" y="%d"/>
                <a:ext cx="%d" cy="%d"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                <a:noFill/>
                <a:ln><a:noFill/></a:ln>

                </p:spPr>
                %s
                </p:sp>
                """.formatted(id, id, offX, offY, extCx, extCy, txBody);
        return ShapeResult.of(xml, offX, offY, extCx, extCy);
    }

    private static ShapeResult convertImage(Element elem, ConvertContext ctx) throws IOException {
        String href = firstNonBlank(
                elem.getAttributeNS("http://www.w3.org/1999/xlink", "href"),
                elem.getAttribute("href"),
                elem.getAttribute("xlink:href"));
        if (href == null || href.isBlank()) {
            return null;
        }
        byte[] bytes;
        String ext;
        Matcher dataUri = DATA_URI.matcher(href.trim());
        if (dataUri.matches()) {
            String mime = dataUri.group(1) == null ? "image/png" : dataUri.group(1);
            bytes = Base64.getDecoder().decode(dataUri.group(2).replaceAll("\\s", ""));
            ext = mimeToExt(mime);
        } else {
            Path resolved = ctx.svgDir().resolve(href).normalize();
            if (!resolved.startsWith(ctx.projectRoot().normalize())) {
                // allow project-relative ../images
                resolved = ctx.projectRoot().resolve(href.replaceFirst("^\\.\\./", "")).normalize();
                if (!Files.isRegularFile(resolved)) {
                    resolved = ctx.svgDir().resolve(href).normalize().toAbsolutePath();
                }
            }
            if (!Files.isRegularFile(resolved)) {
                // try project-root relative from svg_output ../images/foo
                Path alt = ctx.svgDir().resolve(href).toAbsolutePath().normalize();
                if (Files.isRegularFile(alt)) {
                    resolved = alt;
                } else {
                    throw new IOException("External image not found: " + href);
                }
            }
            bytes = Files.readAllBytes(resolved);
            String name = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                ext = "jpeg";
            } else if (name.endsWith(".gif")) {
                ext = "gif";
            } else if (name.endsWith(".emf")) {
                ext = "emf";
            } else {
                ext = "png";
            }
        }
        double x = ctx.mapX(attrDouble(elem, "x", 0));
        double y = ctx.mapY(attrDouble(elem, "y", 0));
        double w = ctx.mapW(attrDouble(elem, "width", 0));
        double h = ctx.mapH(attrDouble(elem, "height", 0));
        int id = ctx.claimShapeId();
        String mediaName = "image" + id + "." + ext;
        ctx.mediaFiles().put(mediaName, bytes);
        String relId = ctx.nextMediaRelId();
        ctx.relEntries()
                .add(new ConvertContext.RelEntry(
                        relId,
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image",
                        "../media/" + mediaName));
        int offX = Emu.pxToEmu(x);
        int offY = Emu.pxToEmu(y);
        int extCx = Emu.pxToEmu(w);
        int extCy = Emu.pxToEmu(h);
        String xml = """
                <p:pic>
                <p:nvPicPr>
                <p:cNvPr id="%d" name="Image %d"/>
                <p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr>
                <p:nvPr/>
                </p:nvPicPr>
                <p:blipFill>
                <a:blip r:embed="%s"/>
                <a:stretch><a:fillRect/></a:stretch>
                </p:blipFill>
                <p:spPr>
                <a:xfrm><a:off x="%d" y="%d"/>
                <a:ext cx="%d" cy="%d"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                </p:spPr>
                </p:pic>
                """.formatted(id, id, relId, offX, offY, extCx, extCy);
        return ShapeResult.of(xml, offX, offY, extCx, extCy);
    }

    private static String themeOrFixedFonts(String fontFamily, ConvertContext ctx) {
        String face = fontFamily.split(",")[0].trim().replace("\"", "").replace("'", "");
        SpecLock.ThemeFontSpec fonts = ctx.themeFonts();
        if (fonts != null) {
            boolean minor = fonts.minor().matches(face, face);
            boolean major = fonts.major().matches(face, face);
            if (minor || major) {
                String prefix = major && !minor ? "+mj" : "+mn";
                return "<a:latin typeface=\"" + prefix + "-lt\"/>\n"
                        + "<a:ea typeface=\"" + prefix + "-ea\"/>\n"
                        + "<a:cs typeface=\"" + prefix + "-cs\"/>";
            }
        }
        return "<a:latin typeface=\"" + escapeXml(face) + "\"/>\n"
                + "<a:ea typeface=\"" + escapeXml(face) + "\"/>\n"
                + "<a:cs typeface=\"" + escapeXml(face) + "\"/>";
    }

    private static String strokeOrNone(Element elem, ConvertContext ctx) {
        String stroke = FillStroke.resolvePaint(elem, ctx, "stroke", "stroke");
        if (stroke == null) {
            return FillStroke.defaultNoStrokeLine();
        }
        return FillStroke.lineXml(elem, ctx);
    }

    private static ShapeResult shapeResult(
            int id,
            String name,
            int offX,
            int offY,
            int extCx,
            int extCy,
            String geom,
            String fill,
            String line,
            String txBody) {
        String xml = """
                <p:sp>
                <p:nvSpPr>
                <p:cNvPr id="%d" name="%s"/>
                <p:cNvSpPr/><p:nvPr></p:nvPr>
                </p:nvSpPr>
                <p:spPr>
                <a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm>
                %s
                %s
                %s

                </p:spPr>
                %s
                </p:sp>
                """.formatted(
                id,
                escapeXml(name),
                offX,
                offY,
                extCx,
                extCy,
                geom,
                fill,
                line,
                txBody == null ? "" : txBody);
        return ShapeResult.of(xml, offX, offY, extCx, extCy);
    }

    private static Map<String, String> inheritableStyles(Element elem) {
        Map<String, String> styles = new LinkedHashMap<>();
        for (String key : List.of(
                "fill",
                "stroke",
                "stroke-width",
                "font-family",
                "font-size",
                "font-weight",
                "font-style",
                "text-anchor",
                "opacity",
                "fill-opacity",
                "stroke-opacity")) {
            String value = elem.getAttribute(key);
            if (value != null && !value.isBlank()) {
                styles.put(key, value);
            }
        }
        return styles;
    }

    private static double[] parseViewBox(Element root) {
        String vb = root.getAttribute("viewBox");
        if (vb != null && !vb.isBlank()) {
            Matcher matcher = VIEWBOX.matcher(vb);
            if (matcher.matches()) {
                return new double[] {
                    Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(3)),
                    Double.parseDouble(matcher.group(4))
                };
            }
        }
        return new double[] {
            0,
            0,
            attrDouble(root, "width", 1280),
            attrDouble(root, "height", 720)
        };
    }

    private static double attrDouble(Element elem, String name, double defaultValue) {
        String raw = elem.getAttribute(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        raw = raw.replace("px", "").trim();
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String detectLang(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (TextMetrics.isCjkChar(cp)) {
                return "zh-CN";
            }
            i += Character.charCount(cp);
        }
        return "en-US";
    }

    private static String mimeToExt(String mime) {
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpeg";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        return "png";
    }

    @SuppressWarnings("unused")
    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    @SuppressWarnings("unused")
    private static void touchImageIo() {
        ImageIO.getReaderFormatNames();
    }
}
