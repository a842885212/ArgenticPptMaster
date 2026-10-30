package domi.argenticpptmaster.engine.file.importconvert;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/** DOCX → Markdown converter with optional {@code <stem>_files/} media extraction. */
public final class DocxToMarkdownConverter {

    private DocxToMarkdownConverter() {}

    public static Path convert(Path docxPath, Path markdownPath) throws ImportConversionException {
        try (InputStream in = Files.newInputStream(docxPath);
                XWPFDocument document = new XWPFDocument(in)) {
            Path assetDir = markdownPath.resolveSibling(stem(markdownPath) + "_files");
            deleteRecursively(assetDir);

            StringBuilder md = new StringBuilder();
            md.append("# ").append(stem(docxPath)).append("\n\n");
            md.append("- Source: `").append(docxPath.getFileName()).append("`\n\n");

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        md.append(text.strip()).append("\n\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    String tableMd = tableToMarkdown(table);
                    if (!tableMd.isBlank()) {
                        md.append(tableMd).append("\n\n");
                    }
                }
            }

            List<XWPFPictureData> pictures = document.getAllPictures();
            if (!pictures.isEmpty()) {
                Files.createDirectories(assetDir);
                int index = 0;
                for (XWPFPictureData picture : pictures) {
                    index++;
                    String ext = extensionFor(picture.suggestFileExtension());
                    String filename = "image_" + String.format(Locale.ROOT, "%02d", index) + ext;
                    Path dest = assetDir.resolve(filename);
                    Files.write(dest, picture.getData());
                    md.append("![Image ").append(index).append("](")
                            .append(assetDir.getFileName())
                            .append('/')
                            .append(filename)
                            .append(")\n\n");
                }
            }

            if (Files.isDirectory(assetDir) && isEmptyDir(assetDir)) {
                deleteRecursively(assetDir);
            }

            Files.writeString(markdownPath, md.toString().strip() + "\n", StandardCharsets.UTF_8);
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "document conversion failed for " + docxPath + ": " + ex.getMessage(), ex);
        }
    }

    private static String tableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText();
                if (text == null || text.isBlank()) {
                    cells.add(" ");
                } else {
                    cells.add(text.replace("|", "\\|").replace('\n', ' ').strip());
                }
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
        out.append("| ").append(String.join(" | ", pad(rows.get(0), width))).append(" |\n");
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

    private static String extensionFor(String suggested) {
        if (suggested == null || suggested.isBlank()) {
            return ".bin";
        }
        String ext = suggested.toLowerCase(Locale.ROOT);
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        return ".jpeg".equals(ext) ? ".jpg" : ext;
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static boolean isEmptyDir(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        }
    }
}
