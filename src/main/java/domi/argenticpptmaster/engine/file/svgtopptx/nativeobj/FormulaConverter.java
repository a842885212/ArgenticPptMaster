// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.nativeobj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import domi.argenticpptmaster.engine.file.svgtopptx.drawingml.ConvertContext;
import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Service-subset block formula marker → OMML.
 *
 * <p>Supports simple literals, {@code =+-}, and {@code ^{...}} / {@code ^x}
 * superscripts (e.g. {@code E=mc^{2}}). Full LaTeX AST / mhchem remains a
 * documented residual gap vs pin.</p>
 */
public final class FormulaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FormulaConverter() {}

    public static String convert(Element group, ConvertContext ctx) {
        JsonNode payload = loadPayload(group);
        String latex = text(payload, "latex");
        if (latex == null || latex.isBlank()) {
            throw new IllegalStateException("formula marker missing latex");
        }
        double fontSize = payload.path("font_size").asDouble(36);
        String color = text(payload, "color");
        if (color == null) {
            color = "#000000";
        }
        String align = text(payload, "align");
        if (align == null) {
            align = "center";
        }
        double x = attrOrPayload(group, payload, "x", "data-pptx-x");
        double y = attrOrPayload(group, payload, "y", "data-pptx-y");
        double w = attrOrPayload(group, payload, "width", "data-pptx-width");
        double h = attrOrPayload(group, payload, "height", "data-pptx-height");
        int shapeId = ctx.claimShapeId();
        String name = group.getAttribute("id");
        if (name == null || name.isBlank()) {
            name = "Formula " + shapeId;
        }
        String lang = ctx.primaryLanguage() != null ? ctx.primaryLanguage() : "en-US";
        int sz = Emu.fontPxToHpt(fontSize);
        String hex = SpecLock.normalizeHex(color);
        String jc = switch (align) {
            case "left" -> "left";
            case "right" -> "right";
            default -> "center";
        };
        String pAlign = switch (align) {
            case "left" -> "l";
            case "right" -> "r";
            default -> "ctr";
        };
        String omml = buildOmml(latex, lang, sz, hex, jc);
        int offX = Emu.pxToEmu(ctx.mapX(x));
        int offY = Emu.pxToEmu(ctx.mapY(y));
        int extCx = Emu.pxToEmu(ctx.mapW(w));
        int extCy = Emu.pxToEmu(ctx.mapH(h));
        // Pin omits mc:Fallback for formula AlternateContent on this service path.
        return """
                <mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
                <mc:Choice xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main" Requires="a14">
                <p:sp>
                <p:nvSpPr>
                <p:cNvPr id="%d" name="%s"/>
                <p:cNvSpPr txBox="1"><a:spLocks noGrp="1"/></p:cNvSpPr>
                <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                <a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                <a:noFill/>
                <a:ln><a:noFill/></a:ln>
                </p:spPr>
                <p:txBody>
                <a:bodyPr lIns="0" tIns="0" rIns="0" bIns="0" wrap="none" anchor="ctr">
                <a:normAutofit/>
                </a:bodyPr>
                <a:lstStyle/>
                <a:p><a:pPr algn="%s"/>
                %s
                </a:p>
                </p:txBody>
                </p:sp>
                </mc:Choice>
                </mc:AlternateContent>
                """.formatted(
                shapeId,
                escapeXml(name),
                offX,
                offY,
                extCx,
                extCy,
                pAlign,
                omml);
    }

    static String buildOmml(String latex, String lang, int sz, String hex, String jc) {
        String body = String.join("", tokenize(latex, lang, sz, hex));
        return "<a14:m><m:oMathPara xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\">"
                + "<m:oMathParaPr><m:jc m:val=\"" + jc + "\" /></m:oMathParaPr>"
                + "<m:oMath>" + body + "</m:oMath></m:oMathPara></a14:m>";
    }

    static List<String> tokenize(String latex, String lang, int sz, String hex) {
        String remaining = latex.replace("\\(", "").replace("\\)", "").replace("$", "").trim();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < remaining.length()) {
            if (remaining.charAt(i) == '^') {
                throw new IllegalStateException("orphaned superscript in latex: " + latex);
            }
            // Lookahead for superscript on next char: X^{...} or X^y
            if (i + 1 < remaining.length() && remaining.charAt(i + 1) == '^') {
                String base = String.valueOf(remaining.charAt(i));
                i += 2;
                String exp;
                if (i < remaining.length() && remaining.charAt(i) == '{') {
                    int end = remaining.indexOf('}', i);
                    if (end < 0) {
                        throw new IllegalStateException("unclosed superscript in latex: " + latex);
                    }
                    exp = remaining.substring(i + 1, end);
                    i = end + 1;
                } else if (i < remaining.length()) {
                    exp = String.valueOf(remaining.charAt(i));
                    i++;
                } else {
                    throw new IllegalStateException("trailing ^ in latex: " + latex);
                }
                out.add("<m:sSup><m:e>" + mathRun(base, lang, sz, hex, false) + "</m:e><m:sup>"
                        + mathRun(exp, lang, sz, hex, false) + "</m:sup></m:sSup>");
                continue;
            }
            char ch = remaining.charAt(i);
            boolean plain = ch == '=' || ch == '+' || ch == '-' || ch == '*' || ch == '/';
            out.add(mathRun(String.valueOf(ch), lang, sz, hex, plain));
            i++;
        }
        return out;
    }

    private static String mathRun(String text, String lang, int sz, String hex, boolean plain) {
        String sty = plain ? "<m:rPr><m:sty m:val=\"p\" /></m:rPr>" : "";
        return "<m:r>" + sty
                + "<a:rPr lang=\"" + lang + "\" sz=\"" + sz + "\" dirty=\"0\">"
                + "<a:solidFill><a:srgbClr val=\"" + hex + "\" /></a:solidFill>"
                + "<a:latin typeface=\"Cambria Math\" /><a:ea typeface=\"Cambria Math\" /><a:cs typeface=\"Cambria Math\" />"
                + "</a:rPr><m:t>" + escapeXml(text) + "</m:t></m:r>";
    }

    private static JsonNode loadPayload(Element group) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (!"metadata".equals(localName(child))) {
                continue;
            }
            try {
                return MAPPER.readTree(child.getTextContent());
            } catch (Exception ex) {
                throw new IllegalStateException("invalid formula metadata JSON", ex);
            }
        }
        throw new IllegalStateException("formula marker missing metadata");
    }

    private static double attrOrPayload(Element group, JsonNode payload, String jsonKey, String attr) {
        if (payload.has(jsonKey) && payload.get(jsonKey).isNumber()) {
            return payload.get(jsonKey).asDouble();
        }
        String raw = group.getAttribute(attr);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("formula marker missing " + jsonKey);
        }
        return Double.parseDouble(raw.trim());
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
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

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
