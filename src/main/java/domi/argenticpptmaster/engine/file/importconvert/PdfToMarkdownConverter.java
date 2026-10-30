package domi.argenticpptmaster.engine.file.importconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF → Markdown converter (PDFBox), contract-aligned with upstream {@code pdf_to_md.py} import path.
 *
 * <p>Complex/wireframe tables are best-effort residual; vector-figure rasterization is out of scope.
 */
public final class PdfToMarkdownConverter {

    static final int MIN_VISIBLE_CHARS = 32;

    private static final int MIN_IMAGE_PIXELS = 100;
    private static final int MIN_IMAGE_AREA = 30_000;
    private static final int MIN_IMAGE_BYTES = 2_048;
    private static final double MIN_PAGE_RATIO = 0.05;
    private static final double MAX_ASPECT_RATIO = 12;
    private static final double MAX_LOW_INFO_BPP = 0.08;
    private static final int MAX_LOW_INFO_AREA = 500_000;

    private static final Pattern LEADING_NUMERIC_PREFIX = Pattern.compile("^\\d+-");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]");
    private static final Pattern LIST_UNORDERED = Pattern.compile("^[•\\-\\*·]\\s+(.+)$");
    private static final Pattern LIST_ORDERED = Pattern.compile("^(\\d+)[.、)]\\s+(.+)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PdfToMarkdownConverter() {}

    public static Path convert(Path pdfPath, Path markdownPath) throws ImportConversionException {
        try {
            Files.createDirectories(markdownPath.getParent());
            Path assetDir = markdownPath.resolveSibling(stem(markdownPath) + "_files");
            deleteRecursively(assetDir);

            try (PDDocument document = open(pdfPath)) {
                StructureStripper stripper = new StructureStripper();
                stripper.setSortByPosition(true);
                stripper.getText(document);

                String visible = stripper.visibleText().replaceAll("\\s+", "");
                if (visible.length() < MIN_VISIBLE_CHARS || stripper.nonEmptyBlockCount() == 0) {
                    throw new ImportConversionException(
                            "PDF has insufficient extractable text (near-empty or scanned): " + pdfPath);
                }

                Set<String> noise = detectHeaderFooterNoise(stripper.pageLines());
                Map<String, Float> sizeMap = analyzeFontSizes(stripper.allSpans());

                StringBuilder md = new StringBuilder();
                String title = LEADING_NUMERIC_PREFIX.matcher(stem(pdfPath)).replaceFirst("").strip();
                if (title.isBlank()) {
                    title = stem(pdfPath);
                }
                md.append("# ").append(title).append("\n\n");

                List<ManifestEntry> manifest = new ArrayList<>();
                Set<String> seenHashes = new HashSet<>();
                int imageCount = 0;
                String relAsset = assetDir.getFileName().toString();
                String fileStem = stem(pdfPath).replace(' ', '_');

                int pageCount = document.getNumberOfPages();
                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    int pageNo = pageIndex + 1;
                    if (pageNo > 1) {
                        md.append("\n\n<!-- Page ").append(pageNo).append(" -->\n\n");
                    }

                    List<LineBlock> lines = stripper.linesForPage(pageNo);
                    appendTextBlocks(md, lines, sizeMap, noise);
                    appendBestEffortTables(md, lines);

                    PDPage page = document.getPage(pageIndex);
                    PDRectangle media = page.getMediaBox();
                    imageCount = extractPageImages(
                            page,
                            pageNo,
                            media,
                            assetDir,
                            relAsset,
                            fileStem,
                            imageCount,
                            seenHashes,
                            manifest,
                            md);
                }

                String cleaned = CONTROL_CHARS.matcher(md.toString()).replaceAll("");
                cleaned = cleaned.replaceAll("\n{3,}", "\n\n").strip() + "\n";
                Files.writeString(markdownPath, cleaned, StandardCharsets.UTF_8);

                if (!manifest.isEmpty()) {
                    Files.createDirectories(assetDir);
                    writeManifest(assetDir.resolve("image_manifest.json"), manifest);
                } else if (Files.isDirectory(assetDir) && isEmptyDir(assetDir)) {
                    deleteRecursively(assetDir);
                }
            }
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new ImportConversionException(
                    "PDF is encrypted and no password was provided: " + pdfPath, ex);
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "PDF conversion failed for " + pdfPath + ": " + ex.getMessage(), ex);
        }
    }

    private static PDDocument open(Path pdfPath) throws IOException {
        return Loader.loadPDF(new RandomAccessReadBufferedFile(pdfPath.toFile()));
    }

    private static void appendTextBlocks(
            StringBuilder md,
            List<LineBlock> lines,
            Map<String, Float> sizeMap,
            Set<String> noise) {
        List<String> paragraph = new ArrayList<>();
        for (LineBlock line : lines) {
            String text = cleanText(line.text);
            if (text.isBlank() || noise.contains(text)) {
                flushParagraph(md, paragraph);
                continue;
            }
            int heading = headingLevel(line.size, sizeMap, text, line.bold);
            if (heading > 0) {
                flushParagraph(md, paragraph);
                md.append("#".repeat(heading)).append(' ').append(text).append("\n\n");
                continue;
            }
            var unordered = LIST_UNORDERED.matcher(text);
            if (unordered.matches()) {
                flushParagraph(md, paragraph);
                md.append("- ").append(unordered.group(1).strip()).append("\n");
                continue;
            }
            var ordered = LIST_ORDERED.matcher(text);
            if (ordered.matches()) {
                flushParagraph(md, paragraph);
                md.append(ordered.group(1)).append(". ").append(ordered.group(2).strip()).append("\n");
                continue;
            }
            if (line.monospace) {
                flushParagraph(md, paragraph);
                md.append("```\n").append(text).append("\n```\n\n");
                continue;
            }
            String formatted = text;
            if (line.bold && line.italic) {
                formatted = "***" + text + "***";
            } else if (line.bold) {
                formatted = "**" + text + "**";
            } else if (line.italic) {
                formatted = "*" + text + "*";
            }
            paragraph.add(formatted);
            if (looksLikeSentenceEnd(text)) {
                flushParagraph(md, paragraph);
            }
        }
        flushParagraph(md, paragraph);
    }

    /**
     * Best-effort: consecutive lines with 2+ large horizontal gaps become a Markdown table.
     * Complex / borderless tables remain a residual vs PyMuPDF {@code find_tables}.
     */
    private static void appendBestEffortTables(StringBuilder md, List<LineBlock> lines) {
        List<List<String>> rows = new ArrayList<>();
        for (LineBlock line : lines) {
            List<String> cells = splitColumns(line.text);
            if (cells.size() >= 2) {
                rows.add(cells);
            } else if (!rows.isEmpty() && rows.size() >= 2) {
                emitTable(md, rows);
                rows = new ArrayList<>();
            } else {
                rows.clear();
            }
        }
        if (rows.size() >= 2) {
            emitTable(md, rows);
        }
    }

    private static List<String> splitColumns(String text) {
        String[] parts = text.split("\\s{2,}");
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            String cell = part.strip();
            if (!cell.isEmpty()) {
                cells.add(cell.replace("|", "\\|"));
            }
        }
        return cells;
    }

    private static void emitTable(StringBuilder md, List<List<String>> rows) {
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        if (width < 2) {
            return;
        }
        md.append("| ").append(String.join(" | ", pad(rows.get(0), width))).append(" |\n");
        md.append("|");
        for (int i = 0; i < width; i++) {
            md.append(" --- |");
        }
        md.append("\n");
        for (int r = 1; r < rows.size(); r++) {
            md.append("| ").append(String.join(" | ", pad(rows.get(r), width))).append(" |\n");
        }
        md.append("\n");
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> out = new ArrayList<>(row);
        while (out.size() < width) {
            out.add(" ");
        }
        return out;
    }

    private static int extractPageImages(
            PDPage page,
            int pageNo,
            PDRectangle media,
            Path assetDir,
            String relAsset,
            String fileStem,
            int imageCount,
            Set<String> seenHashes,
            List<ManifestEntry> manifest,
            StringBuilder md)
            throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            return imageCount;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject;
            try {
                xObject = resources.getXObject(name);
            } catch (IOException ex) {
                continue;
            }
            if (!(xObject instanceof PDImageXObject image)) {
                continue;
            }
            BufferedImage buffered;
            try {
                buffered = image.getImage();
            } catch (IOException ex) {
                continue;
            }
            if (buffered == null) {
                continue;
            }
            int w = buffered.getWidth();
            int h = buffered.getHeight();
            byte[] pngBytes = toPngBytes(buffered);
            if (!shouldKeepImage(w, h, pngBytes, media.getWidth(), media.getHeight(), seenHashes)) {
                continue;
            }
            imageCount++;
            Files.createDirectories(assetDir);
            String filename = fileStem + "_p" + pageNo + "_" + imageCount + ".png";
            Path dest = assetDir.resolve(filename);
            Files.write(dest, pngBytes);
            md.append("![").append(filename).append("](")
                    .append(relAsset)
                    .append('/')
                    .append(filename)
                    .append(")\n\n");

            String sha = sha256(pngBytes);
            manifest.add(new ManifestEntry(
                    imageCount,
                    filename,
                    name.getName(),
                    "bitmap",
                    true,
                    true,
                    "pdf_image",
                    ".png",
                    pageNo,
                    imageCount,
                    w,
                    h,
                    (double) w / Math.max(h, 1),
                    (double) w / Math.max(h, 1),
                    sha,
                    List.of(0.0, 0.0, (double) media.getWidth(), (double) media.getHeight())));
        }
        return imageCount;
    }

    private static boolean shouldKeepImage(
            int w,
            int h,
            byte[] data,
            float pageW,
            float pageH,
            Set<String> seenHashes) {
        if (w < MIN_IMAGE_PIXELS || h < MIN_IMAGE_PIXELS) {
            return false;
        }
        long area = (long) w * h;
        if (area < MIN_IMAGE_AREA) {
            return false;
        }
        if (data.length < MIN_IMAGE_BYTES) {
            return false;
        }
        String hash = md5(data);
        if (!seenHashes.add(hash)) {
            return false;
        }
        if (pageW > 0 && pageH > 0) {
            double rw = w / pageW;
            double rh = h / pageH;
            // Without placement bbox, approximate with pixel vs page point size.
            if (rw < MIN_PAGE_RATIO && rh < MIN_PAGE_RATIO) {
                return false;
            }
        }
        double aspect = (double) Math.max(w, h) / Math.max(Math.min(w, h), 1);
        if (aspect > MAX_ASPECT_RATIO) {
            return false;
        }
        double bpp = data.length / (double) area;
        if (bpp < MAX_LOW_INFO_BPP && area < MAX_LOW_INFO_AREA) {
            return false;
        }
        return true;
    }

    private static byte[] toPngBytes(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void writeManifest(Path path, List<ManifestEntry> entries) throws IOException {
        ArrayNode array = MAPPER.createArrayNode();
        for (ManifestEntry entry : entries) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("index", entry.index);
            node.put("filename", entry.filename);
            node.put("original_filename", entry.originalFilename);
            node.put("asset_kind", entry.assetKind);
            node.put("svg_renderable", entry.svgRenderable);
            node.put("pptx_native_supported", entry.pptxNativeSupported);
            node.put("source_kind", entry.sourceKind);
            node.put("source_ext", entry.sourceExt);
            node.put("page_index", entry.pageIndex);
            node.put("occurrence_index", entry.occurrenceIndex);
            node.put("pixel_width", entry.pixelWidth);
            node.put("pixel_height", entry.pixelHeight);
            node.put("pixel_ratio", entry.pixelRatio);
            node.put("display_ratio", entry.displayRatio);
            node.put("source_sha256", entry.sourceSha256);
            ArrayNode bbox = node.putArray("bbox");
            for (double v : entry.bbox) {
                bbox.add(v);
            }
            array.add(node);
        }
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n");
    }

    private static Map<String, Float> analyzeFontSizes(List<SpanInfo> spans) {
        Map<Float, Integer> counter = new HashMap<>();
        for (SpanInfo span : spans) {
            if (span.text == null || span.text.isBlank()) {
                continue;
            }
            float rounded = Math.round(span.size * 10f) / 10f;
            counter.merge(rounded, span.text.strip().length(), Integer::sum);
        }
        if (counter.isEmpty()) {
            return Map.of("body", 12f, "h1", 24f, "h2", 18f, "h3", 14f);
        }
        float body = counter.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(12f);
        List<Float> larger = counter.keySet().stream()
                .filter(s -> s > body + 1f)
                .sorted(Comparator.reverseOrder())
                .toList();
        Map<String, Float> map = new LinkedHashMap<>();
        map.put("body", body);
        if (!larger.isEmpty()) {
            map.put("h1", larger.get(0));
        }
        if (larger.size() >= 2) {
            map.put("h2", larger.get(1));
        }
        if (larger.size() >= 3) {
            map.put("h3", larger.get(2));
        }
        return map;
    }

    private static int headingLevel(float size, Map<String, Float> sizeMap, String text, boolean bold) {
        int level = 0;
        if (sizeMap.containsKey("h1") && size >= sizeMap.get("h1") - 0.5f) {
            level = 1;
        } else if (sizeMap.containsKey("h2") && size >= sizeMap.get("h2") - 0.5f) {
            level = 2;
        } else if (sizeMap.containsKey("h3") && size >= sizeMap.get("h3") - 0.5f) {
            level = 3;
        }
        if (level == 0) {
            return 0;
        }
        String trimmed = text.strip();
        if (trimmed.length() > 80) {
            return 0;
        }
        if (!trimmed.isEmpty() && ".。!！?？".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            if (!trimmed.matches("^[\\d第]+[.、章节].*")) {
                return 0;
            }
        }
        if (!bold && level >= 2) {
            float body = sizeMap.getOrDefault("body", 12f);
            if (size < body + 2) {
                return 0;
            }
        }
        return level;
    }

    private static Set<String> detectHeaderFooterNoise(Map<Integer, List<String>> pageLines) {
        Map<String, Integer> counts = new HashMap<>();
        int pages = pageLines.size();
        for (List<String> lines : pageLines.values()) {
            Set<String> edge = new HashSet<>();
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                edge.add(lines.get(i).strip());
            }
            for (int i = Math.max(0, lines.size() - 2); i < lines.size(); i++) {
                edge.add(lines.get(i).strip());
            }
            for (String t : edge) {
                if (!t.isBlank() && t.length() < 80) {
                    counts.merge(t, 1, Integer::sum);
                }
            }
        }
        Set<String> noise = new HashSet<>();
        if (pages == 0) {
            return noise;
        }
        double threshold = Math.max(2, pages * 0.6);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= threshold) {
                noise.add(e.getKey());
            }
        }
        return noise;
    }

    private static void flushParagraph(StringBuilder md, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        md.append(String.join(" ", paragraph)).append("\n\n");
        paragraph.clear();
    }

    private static boolean looksLikeSentenceEnd(String text) {
        if (text.isBlank()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return ".。!！?？".indexOf(last) >= 0;
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return CONTROL_CHARS.matcher(text).replaceAll("").strip();
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception ex) {
            return "";
        }
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception ex) {
            return Integer.toHexString(data.length);
        }
    }

    private record SpanInfo(String text, float size, boolean bold, boolean italic, boolean monospace) {}

    private record LineBlock(
            String text, float size, boolean bold, boolean italic, boolean monospace, float y) {}

    private record ManifestEntry(
            int index,
            String filename,
            String originalFilename,
            String assetKind,
            boolean svgRenderable,
            boolean pptxNativeSupported,
            String sourceKind,
            String sourceExt,
            int pageIndex,
            int occurrenceIndex,
            int pixelWidth,
            int pixelHeight,
            double pixelRatio,
            double displayRatio,
            String sourceSha256,
            List<Double> bbox) {}

    private static final class StructureStripper extends PDFTextStripper {
        private final List<SpanInfo> spans = new ArrayList<>();
        private final Map<Integer, List<LineBlock>> pageLineBlocks = new LinkedHashMap<>();
        private final Map<Integer, List<String>> pageLines = new LinkedHashMap<>();
        private final StringBuilder visible = new StringBuilder();
        private int nonEmptyBlocks;
        private final StringBuilder lineBuf = new StringBuilder();
        private float lineSize;
        private boolean lineBold;
        private boolean lineItalic;
        private boolean lineMono;
        private float lineY;
        private int lineSpanCount;

        StructureStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            pageLineBlocks.putIfAbsent(getCurrentPageNo(), new ArrayList<>());
            pageLines.putIfAbsent(getCurrentPageNo(), new ArrayList<>());
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }
            TextPosition first = textPositions.get(0);
            float size = first.getFontSizeInPt();
            String fontName = first.getFont() == null ? "" : String.valueOf(first.getFont().getName());
            boolean bold = fontName.toLowerCase(Locale.ROOT).contains("bold");
            boolean italic = fontName.toLowerCase(Locale.ROOT).contains("italic")
                    || fontName.toLowerCase(Locale.ROOT).contains("oblique");
            boolean mono = isMonospace(fontName);
            spans.add(new SpanInfo(text, size, bold, italic, mono));
            visible.append(text);
            if (lineBuf.isEmpty()) {
                lineY = first.getYDirAdj();
                lineSize = size;
                lineBold = bold;
                lineItalic = italic;
                lineMono = mono;
                lineSpanCount = 0;
            }
            lineBuf.append(text);
            lineSize = ((lineSize * lineSpanCount) + size) / (lineSpanCount + 1);
            lineBold = lineBold || bold;
            lineItalic = lineItalic || italic;
            lineMono = lineMono || mono;
            lineSpanCount++;
            nonEmptyBlocks++;
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
            super.writeLineSeparator();
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            flushLine();
            super.writeParagraphEnd();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            flushLine();
            super.endPage(page);
        }

        private void flushLine() {
            String text = lineBuf.toString().strip();
            lineBuf.setLength(0);
            if (text.isBlank()) {
                return;
            }
            int page = getCurrentPageNo();
            LineBlock block = new LineBlock(text, lineSize, lineBold, lineItalic, lineMono, lineY);
            pageLineBlocks.computeIfAbsent(page, k -> new ArrayList<>()).add(block);
            pageLines.computeIfAbsent(page, k -> new ArrayList<>()).add(text);
        }

        List<SpanInfo> allSpans() {
            return spans;
        }

        Map<Integer, List<String>> pageLines() {
            return pageLines;
        }

        List<LineBlock> linesForPage(int pageNo) {
            return pageLineBlocks.getOrDefault(pageNo, List.of());
        }

        String visibleText() {
            return visible.toString();
        }

        int nonEmptyBlockCount() {
            return nonEmptyBlocks;
        }

        private static boolean isMonospace(String fontName) {
            if (fontName == null) {
                return false;
            }
            String lower = fontName.toLowerCase(Locale.ROOT);
            return lower.contains("courier")
                    || lower.contains("consolas")
                    || lower.contains("mono")
                    || lower.contains("menlo");
        }
    }
}
