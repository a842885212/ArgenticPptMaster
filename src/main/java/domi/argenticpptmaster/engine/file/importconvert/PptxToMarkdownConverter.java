package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfShapes;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Contract-level PPTX→Markdown converter aligned with pin {@code ppt_to_md.py} structure
 * ({@code # stem}, {@code ## Slide N}, optional speaker notes, {@code <stem>_files/} images).
 */
public final class PptxToMarkdownConverter {

    private PptxToMarkdownConverter() {}

    public static Path convert(Path pptxPath, Path markdownPath) throws ImportConversionException {
        Path extractDir = null;
        try {
            extractDir = Files.createTempDirectory("java-pptx-to-md-");
            PptxPackage.extract(pptxPath, extractDir);
            List<SlidePartRef> slides = SlideParts.read(extractDir);
            Path assetDir = markdownPath.resolveSibling(stem(markdownPath) + "_files");
            deleteRecursively(assetDir);
            Files.createDirectories(assetDir.getParent());

            StringBuilder md = new StringBuilder();
            md.append("# ").append(stem(pptxPath)).append("\n\n");
            md.append("- Source: `").append(pptxPath.getFileName()).append("`\n");
            md.append("- Total slides: ").append(slides.size()).append("\n\n");

            int imageRefCount = 0;
            boolean assetDirUsed = false;
            for (SlidePartRef slide : slides) {
                md.append("## Slide ").append(slide.index()).append("\n\n");
                Path slideXml = extractDir.resolve(slide.partName());
                if (!Files.isRegularFile(slideXml)) {
                    md.append("_No extractable text content._\n\n");
                    continue;
                }
                Document doc = TfXml.parse(Files.readAllBytes(slideXml));
                Element root = doc.getDocumentElement();
                List<String> blocks = new ArrayList<>();

                for (Element tableFrame : TfShapes.tableContainers(root)) {
                    String tableMd = tableToMarkdown(tableFrame);
                    if (!tableMd.isBlank()) {
                        blocks.add(tableMd);
                    }
                }

                Path relsPath = extractDir.resolve(TfXml.relsNameForPart(slide.partName()));
                List<Path> images = extractSlideImages(extractDir, slide.partName(), relsPath, assetDir, slide.index());
                for (Path image : images) {
                    imageRefCount++;
                    assetDirUsed = true;
                    blocks.add("![Slide " + slide.index() + " Image " + imageRefCount + "]("
                            + assetDir.getFileName() + "/" + image.getFileName() + ")");
                }

                for (Element container : TfShapes.textContainers(root)) {
                    if (TfXml.firstDescendant(container, "tbl") != null) {
                        continue;
                    }
                    List<String> paragraphs = TfShapes.paragraphTexts(container);
                    if (!paragraphs.isEmpty()) {
                        blocks.add(String.join("\n\n", paragraphs));
                    }
                }

                if (blocks.isEmpty()) {
                    md.append("_No extractable text content._\n\n");
                } else {
                    md.append(String.join("\n\n", blocks)).append("\n\n");
                }

                String notes = extractNotes(extractDir, slide.partName(), relsPath);
                if (!notes.isBlank()) {
                    md.append("### Speaker Notes\n\n").append(notes).append("\n\n");
                }
            }

            if (!assetDirUsed) {
                deleteRecursively(assetDir);
            }
            Files.writeString(markdownPath, md.toString().strip() + "\n", StandardCharsets.UTF_8);
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "presentation conversion failed for " + pptxPath + ": " + ex.getMessage(), ex);
        } finally {
            if (extractDir != null) {
                try {
                    deleteRecursively(extractDir);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static List<Path> extractSlideImages(
            Path extractDir, String slidePart, Path relsPath, Path assetDir, int slideIndex)
            throws IOException {
        List<Path> saved = new ArrayList<>();
        if (!Files.isRegularFile(relsPath)) {
            return saved;
        }
        int imageIndex = 0;
        for (RelationshipsSupport.Relationship rel : RelationshipsSupport.parseRelationships(relsPath)) {
            if (!"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                    .equals(rel.type())) {
                continue;
            }
            String target = rel.target();
            if (target == null || target.isBlank()) {
                continue;
            }
            Path imagePart = extractDir.resolve(normalizePart(target, slidePart)).normalize();
            if (!imagePart.startsWith(extractDir) || !Files.isRegularFile(imagePart)) {
                continue;
            }
            imageIndex++;
            String ext = extension(imagePart.getFileName().toString());
            String filename = "slide_" + String.format(Locale.ROOT, "%02d", slideIndex)
                    + "_image_" + String.format(Locale.ROOT, "%02d", imageIndex) + ext;
            Files.createDirectories(assetDir);
            Path dest = assetDir.resolve(filename);
            Files.copy(imagePart, dest, StandardCopyOption.REPLACE_EXISTING);
            saved.add(dest);
        }
        saved.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return saved;
    }

    private static String extractNotes(Path extractDir, String slidePart, Path relsPath) throws Exception {
        if (!Files.isRegularFile(relsPath)) {
            return "";
        }
        for (RelationshipsSupport.Relationship rel : RelationshipsSupport.parseRelationships(relsPath)) {
            if (!"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide"
                    .equals(rel.type())) {
                continue;
            }
            Path notesPart = extractDir.resolve(normalizePart(rel.target(), slidePart)).normalize();
            if (!notesPart.startsWith(extractDir) || !Files.isRegularFile(notesPart)) {
                continue;
            }
            Document doc = TfXml.parse(Files.readAllBytes(notesPart));
            List<String> paragraphs = new ArrayList<>();
            for (Element container : TfShapes.textContainers(doc.getDocumentElement())) {
                paragraphs.addAll(TfShapes.paragraphTexts(container));
            }
            return String.join("\n\n", paragraphs).strip();
        }
        return "";
    }

    private static String tableToMarkdown(Element tableFrame) {
        Element tbl = TfXml.firstDescendant(tableFrame, "tbl");
        if (tbl == null) {
            return "";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : TfXml.descendants(tbl, "tr")) {
            List<String> cells = new ArrayList<>();
            for (Element tc : TfXml.descendants(tr, "tc")) {
                List<String> parts = TfShapes.paragraphTexts(tc);
                String cell = parts.isEmpty() ? " " : String.join("<br>", parts).replace("|", "\\|");
                cells.add(cell.isBlank() ? " " : cell);
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder out = new StringBuilder();
        List<String> header = pad(rows.get(0), width);
        out.append("| ").append(String.join(" | ", header)).append(" |\n");
        out.append("| ");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append("---");
        }
        out.append(" |\n");
        for (int r = 1; r < rows.size(); r++) {
            out.append("| ").append(String.join(" | ", pad(rows.get(r), width))).append(" |\n");
        }
        return out.toString().strip();
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < width) {
            padded.add(" ");
        }
        return padded;
    }

    private static String normalizePart(String target, String fromPart) {
        String cleaned = target.replace('\\', '/');
        if (cleaned.startsWith("/")) {
            return cleaned.substring(1);
        }
        Path base = Path.of(fromPart).getParent();
        if (base == null) {
            return cleaned;
        }
        return base.resolve(cleaned).normalize().toString().replace('\\', '/');
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return ".bin";
        }
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ".jpeg".equals(ext) ? ".jpg" : ext;
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }
}
