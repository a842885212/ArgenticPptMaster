// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.beautify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Port of {@code beautify_identity.extract_identity}: theme palette/fonts/sizes, observed
 * usage sample, layout body sizes, and canvas.
 */
public final class BeautifyIdentityExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> NON_BODY_PH = Set.of("title", "ctrTitle", "ftr", "sldNum", "dt", "hdr");
    private static final Set<String> CJK_SCRIPTS = Set.of("Hans", "Hant", "Jpan", "Hang");
    private static final double EMU_PER_PX = 9525.0;

    private BeautifyIdentityExtractor() {}

    public static ObjectNode extractIdentity(Path pptxPath) throws Exception {
        Path extractDir = Files.createTempDirectory("java-beautify-identity-");
        try {
            PptxPackage.extract(pptxPath, extractDir);
            Document presentation = TfXml.parse(Files.readAllBytes(extractDir.resolve("ppt/presentation.xml")));
            List<SlidePartRef> slides = SlideParts.read(extractDir);

            String masterPart = null;
            Element masterRoot = null;
            Element themeRoot = null;
            if (!slides.isEmpty()) {
                String layoutPart = resolveRelTarget(
                        extractDir, slides.get(0).partName(), OoxmlConstants.SLIDE_LAYOUT_REL_TYPE);
                if (layoutPart != null) {
                    masterPart = resolveRelTarget(extractDir, layoutPart, OoxmlConstants.SLIDE_MASTER_REL_TYPE);
                }
                if (masterPart != null && Files.isRegularFile(extractDir.resolve(masterPart))) {
                    masterRoot = TfXml.parse(Files.readAllBytes(extractDir.resolve(masterPart))).getDocumentElement();
                    String themePart = resolveRelTarget(extractDir, masterPart, OoxmlConstants.THEME_REL_TYPE);
                    if (themePart != null && Files.isRegularFile(extractDir.resolve(themePart))) {
                        themeRoot = TfXml.parse(Files.readAllBytes(extractDir.resolve(themePart))).getDocumentElement();
                    }
                }
            }

            BeautifyThemePalette paletteResolver = new BeautifyThemePalette(masterRoot, themeRoot);
            ObjectNode palette = MAPPER.createObjectNode();
            putPaletteColor(palette, "background", paletteResolver.resolveScheme("bg1"));
            putPaletteColor(palette, "background_alt", paletteResolver.resolveScheme("bg2"));
            putPaletteColor(palette, "text", paletteResolver.resolveScheme("tx1"));
            putPaletteColor(palette, "text_alt", paletteResolver.resolveScheme("tx2"));
            putPaletteColor(palette, "hyperlink", paletteResolver.resolveScheme("hlink"));
            for (int i = 1; i <= 6; i++) {
                putPaletteColor(palette, "accent" + i, paletteResolver.resolveScheme("accent" + i));
            }
            if (palette.has("accent1") && !palette.get("accent1").isNull()) {
                palette.set("primary", palette.get("accent1"));
            } else {
                palette.putNull("primary");
            }

            ObjectNode fonts = MAPPER.createObjectNode();
            if (themeRoot != null) {
                fonts.set("title", fontPair(themeRoot, "majorFont"));
                fonts.set("body", fontPair(themeRoot, "minorFont"));
            }

            ObjectNode sizes = masterRoot != null ? masterTextSizes(masterRoot) : MAPPER.createObjectNode();

            Element sldSz = TfXml.firstChild(presentation.getDocumentElement(), "sldSz");
            double widthPx = 1280.0;
            double heightPx = 720.0;
            if (sldSz != null) {
                widthPx = Long.parseLong(sldSz.getAttribute("cx")) / EMU_PER_PX;
                heightPx = Long.parseLong(sldSz.getAttribute("cy")) / EMU_PER_PX;
            }
            ObjectNode canvas = MAPPER.createObjectNode();
            canvas.put("width_px", (int) Math.round(widthPx));
            canvas.put("height_px", (int) Math.round(heightPx));
            if (heightPx == 0) {
                canvas.putNull("aspect");
            } else {
                canvas.put("aspect", Math.round(widthPx / heightPx * 10000.0) / 10000.0);
            }

            ObjectNode theme = MAPPER.createObjectNode();
            theme.set("palette", palette);
            theme.set("fonts", fonts);
            theme.set("sizes", sizes);

            ObjectNode identity = MAPPER.createObjectNode();
            identity.put("source", pptxPath.toAbsolutePath().normalize().toString());
            identity.put("slide_count", slides.size());
            identity.set("canvas", canvas);
            identity.set("theme", theme);
            identity.set("observed", sampleObserved(extractDir, slides));
            identity.set("layout_sizes_pt", layoutTextSizes(extractDir, slides));
            return identity;
        } finally {
            deleteRecursively(extractDir);
        }
    }

    private static void putPaletteColor(ObjectNode palette, String key, String rgb) {
        if (rgb == null || rgb.isBlank()) {
            palette.putNull(key);
        } else {
            palette.put(key, "#" + rgb);
        }
    }

    private static ObjectNode fontPair(Element themeRoot, String fontTag) {
        ObjectNode out = MAPPER.createObjectNode();
        Element fontScheme = firstDescendant(themeRoot, "fontScheme");
        if (fontScheme == null) {
            return out;
        }
        Element font = TfXml.firstChild(fontScheme, fontTag);
        if (font == null) {
            return out;
        }
        for (String[] slot : new String[][] {{"latin", "latin"}, {"ea", "ea"}, {"cs", "cs"}}) {
            Element elem = TfXml.firstChild(font, slot[0]);
            if (elem != null) {
                String face = elem.getAttribute("typeface").strip();
                if (!face.isEmpty()) {
                    out.put(slot[1], face);
                }
            }
        }
        ObjectNode scripts = MAPPER.createObjectNode();
        for (Element elem : TfXml.childrenByLocalName(font, "font")) {
            String script = elem.getAttribute("script").strip();
            String face = elem.getAttribute("typeface").strip();
            if (CJK_SCRIPTS.contains(script) && !face.isEmpty()) {
                scripts.put(script, face);
            }
        }
        if (!scripts.isEmpty()) {
            out.set("scripts", scripts);
        }
        return out;
    }

    private static ObjectNode masterTextSizes(Element masterRoot) {
        ObjectNode out = MAPPER.createObjectNode();
        Element txStyles = firstDescendant(masterRoot, "txStyles");
        if (txStyles == null) {
            return out;
        }
        Element titleStyle = TfXml.firstChild(txStyles, "titleStyle");
        if (titleStyle != null) {
            Element defRPr = firstDescendant(titleStyle, "defRPr");
            Double titleSz = hundredthsToPt(defRPr);
            if (titleSz != null) {
                out.put("title", titleSz);
            }
        }
        Element bodyStyle = TfXml.firstChild(txStyles, "bodyStyle");
        ArrayNode levels = MAPPER.createArrayNode();
        if (bodyStyle != null) {
            for (Node child = bodyStyle.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element lvl)) {
                    continue;
                }
                Element defRPr = TfXml.firstChild(lvl, "defRPr");
                Double pt = hundredthsToPt(defRPr);
                if (pt != null) {
                    levels.add(pt);
                }
            }
        }
        if (!levels.isEmpty()) {
            out.put("body", levels.get(0).asDouble());
            out.set("body_levels", levels);
        }
        return out;
    }

    private static ArrayNode layoutTextSizes(Path extractDir, List<SlidePartRef> slides) throws Exception {
        Map<Double, Integer> sizes = new HashMap<>();
        Set<String> seenLayouts = new HashSet<>();
        for (SlidePartRef slide : slides) {
            String layoutPart = resolveRelTarget(extractDir, slide.partName(), OoxmlConstants.SLIDE_LAYOUT_REL_TYPE);
            if (layoutPart == null || !seenLayouts.add(layoutPart)) {
                continue;
            }
            Path layoutPath = extractDir.resolve(layoutPart);
            if (!Files.isRegularFile(layoutPath)) {
                continue;
            }
            Document layoutDoc = TfXml.parse(Files.readAllBytes(layoutPath));
            for (Element sp : TfXml.descendants(layoutDoc.getDocumentElement(), "sp")) {
                Element ph = null;
                Element nvSpPr = firstDescendant(sp, "nvSpPr");
                if (nvSpPr != null) {
                    Element nvPr = firstDescendant(nvSpPr, "nvPr");
                    if (nvPr != null) {
                        ph = TfXml.firstChild(nvPr, "ph");
                    }
                }
                String phType = ph != null ? ph.getAttribute("type") : null;
                if (phType != null && NON_BODY_PH.contains(phType)) {
                    continue;
                }
                Element lstStyle = firstDescendant(sp, "lstStyle");
                if (lstStyle == null) {
                    continue;
                }
                Element lvl1 = TfXml.firstChild(lstStyle, "lvl1pPr");
                if (lvl1 == null) {
                    continue;
                }
                Element defRPr = TfXml.firstChild(lvl1, "defRPr");
                Double pt = hundredthsToPt(defRPr);
                if (pt != null) {
                    sizes.merge(pt, 1, Integer::sum);
                }
            }
        }
        return rank(sizes, 10);
    }

    private static ObjectNode sampleObserved(Path extractDir, List<SlidePartRef> slides) throws Exception {
        Map<String, Integer> latin = new HashMap<>();
        Map<String, Integer> ea = new HashMap<>();
        Map<Double, Integer> sizes = new HashMap<>();
        Map<String, Integer> colors = new HashMap<>();
        for (SlidePartRef slide : slides) {
            Path slidePath = extractDir.resolve(slide.partName());
            if (!Files.isRegularFile(slidePath)) {
                continue;
            }
            Document slideDoc = TfXml.parse(Files.readAllBytes(slidePath));
            Element root = slideDoc.getDocumentElement();
            for (Element elem : TfXml.descendants(root, "latin")) {
                String face = elem.getAttribute("typeface").strip();
                if (!face.isEmpty() && !face.startsWith("+")) {
                    latin.merge(face, 1, Integer::sum);
                }
            }
            for (Element elem : TfXml.descendants(root, "ea")) {
                String face = elem.getAttribute("typeface").strip();
                if (!face.isEmpty() && !face.startsWith("+")) {
                    ea.merge(face, 1, Integer::sum);
                }
            }
            for (Element elem : TfXml.descendants(root, "rPr")) {
                Double pt = hundredthsToPt(elem);
                if (pt != null) {
                    sizes.merge(pt, 1, Integer::sum);
                }
            }
            for (Element elem : TfXml.descendants(root, "srgbClr")) {
                String val = elem.getAttribute("val").strip().toUpperCase();
                if (!val.isEmpty()) {
                    colors.merge("#" + val, 1, Integer::sum);
                }
            }
        }
        ObjectNode fonts = MAPPER.createObjectNode();
        fonts.set("latin", rankStrings(latin, 5));
        fonts.set("ea", rankStrings(ea, 5));
        ObjectNode observed = MAPPER.createObjectNode();
        observed.set("fonts", fonts);
        observed.set("sizes_pt", rank(sizes, 8));
        observed.set("colors", rankStrings(colors, 8));
        return observed;
    }

    private static ArrayNode rank(Map<Double, Integer> counter, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        counter.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Double, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparingDouble(Map.Entry::getKey))
                .limit(limit)
                .forEach(entry -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("value", entry.getKey());
                    item.put("count", entry.getValue());
                    out.add(item);
                });
        return out;
    }

    private static ArrayNode rankStrings(Map<String, Integer> counter, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        counter.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .forEach(entry -> {
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("value", entry.getKey());
                    item.put("count", entry.getValue());
                    out.add(item);
                });
        return out;
    }

    private static Double hundredthsToPt(Element defRPr) {
        if (defRPr == null) {
            return null;
        }
        String sz = defRPr.getAttribute("sz").strip();
        if (sz.isEmpty() || !sz.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.parseInt(sz) / 100.0;
    }

    private static String resolveRelTarget(Path extractDir, String sourcePart, String relType) throws Exception {
        Path rels = RelationshipsSupport.relationshipFileForPart(extractDir, sourcePart);
        for (RelationshipsSupport.Relationship rel : RelationshipsSupport.parseRelationships(rels)) {
            if (relType.equals(rel.type())) {
                return SlideParts.resolveRelationshipPart(sourcePart, rel.target());
            }
        }
        return null;
    }

    private static Element firstDescendant(Element root, String localName) {
        List<Element> found = TfXml.descendants(root, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best-effort cleanup
                    }
                });
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
