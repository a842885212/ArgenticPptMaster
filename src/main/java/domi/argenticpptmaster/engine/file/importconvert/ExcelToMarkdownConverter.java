package domi.argenticpptmaster.engine.file.importconvert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;

/** Excel → Markdown converter (visible sheets as Markdown tables). */
public final class ExcelToMarkdownConverter {

    private static final int DEFAULT_MAX_ROWS = 500;
    private static final int DEFAULT_MAX_COLS = 50;

    private ExcelToMarkdownConverter() {}

    public static Path convert(Path excelPath, Path markdownPath) throws ImportConversionException {
        try (Workbook workbook = WorkbookFactory.create(excelPath.toFile(), null, true)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<Sheet> visible = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                    visible.add(sheet);
                }
            }

            StringBuilder md = new StringBuilder();
            md.append("# Spreadsheet Source: ").append(excelPath.getFileName()).append("\n\n");
            md.append("## Workbook Summary\n\n");
            md.append("- Sheets: ").append(workbook.getNumberOfSheets()).append('\n');
            md.append("- Visible sheets: ");
            if (visible.isEmpty()) {
                md.append("None\n");
            } else {
                List<String> names = new ArrayList<>();
                for (Sheet sheet : visible) {
                    names.add(sheet.getSheetName());
                }
                md.append(String.join(", ", names)).append('\n');
            }
            md.append('\n');
            md.append("> Note: Formula cells are exported as cached values. This converter does not recalculate formulas.\n\n");

            if (visible.isEmpty()) {
                md.append("_No visible sheets found._\n");
            }

            for (Sheet sheet : visible) {
                md.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                md.append("- State: visible\n");
                Bounds bounds = contentBounds(sheet);
                if (bounds == null) {
                    md.append("\n_No content found._\n\n");
                    continue;
                }
                int rowCount = bounds.maxRow - bounds.minRow + 1;
                int colCount = bounds.maxCol - bounds.minCol + 1;
                boolean rowsTruncated = rowCount > DEFAULT_MAX_ROWS;
                boolean colsTruncated = colCount > DEFAULT_MAX_COLS;
                int endRow = Math.min(bounds.maxRow, bounds.minRow + DEFAULT_MAX_ROWS - 1);
                int endCol = Math.min(bounds.maxCol, bounds.minCol + DEFAULT_MAX_COLS - 1);

                String usedRange = CellReference.convertNumToColString(bounds.minCol)
                        + (bounds.minRow + 1)
                        + ":"
                        + CellReference.convertNumToColString(bounds.maxCol)
                        + (bounds.maxRow + 1);
                md.append("- Used range: ").append(usedRange).append('\n');
                md.append("- Rows: ").append(rowCount).append('\n');
                md.append("- Columns: ").append(colCount).append("\n\n");
                if (rowsTruncated || colsTruncated) {
                    List<String> notes = new ArrayList<>();
                    if (rowsTruncated) {
                        notes.add("rows limited to " + DEFAULT_MAX_ROWS);
                    }
                    if (colsTruncated) {
                        notes.add("columns limited to " + DEFAULT_MAX_COLS);
                    }
                    md.append("> Truncated: ").append(String.join(", ", notes)).append(".\n\n");
                }

                List<List<String>> rows = new ArrayList<>();
                for (int r = bounds.minRow; r <= endRow; r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    for (int c = bounds.minCol; c <= endCol; c++) {
                        cells.add(escapeCell(formatCell(row == null ? null : row.getCell(c), formatter)));
                    }
                    rows.add(cells);
                }
                md.append(rowsToMarkdown(rows)).append("\n\n");
            }

            Files.writeString(markdownPath, md.toString().strip() + "\n", StandardCharsets.UTF_8);
            return markdownPath;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "Excel conversion failed for " + excelPath + ": " + ex.getMessage(), ex);
        }
    }

    private static String formatCell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return formatter.formatCellValue(cell);
            } catch (RuntimeException ex) {
                return cell.getCellFormula();
            }
        }
        return formatter.formatCellValue(cell);
    }

    private static String escapeCell(String value) {
        if (value == null || value.isBlank()) {
            return " ";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("|", "\\|")
                .replaceAll("\\s*\\n\\s*", "<br>")
                .strip();
    }

    private static String rowsToMarkdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "_No tabular content found._";
        }
        int width = rows.get(0).size();
        StringBuilder out = new StringBuilder();
        out.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        out.append("| ");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append("---");
        }
        out.append(" |\n");
        for (int r = 1; r < rows.size(); r++) {
            out.append("| ").append(String.join(" | ", rows.get(r))).append(" |\n");
        }
        return out.toString().strip();
    }

    private static Bounds contentBounds(Sheet sheet) {
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        boolean found = false;
        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell == null || cell.getCellType() == CellType.BLANK) {
                    continue;
                }
                String text = new DataFormatter(Locale.ROOT).formatCellValue(cell);
                if (text == null || text.isBlank()) {
                    continue;
                }
                found = true;
                minRow = Math.min(minRow, row.getRowNum());
                maxRow = Math.max(maxRow, row.getRowNum());
                minCol = Math.min(minCol, cell.getColumnIndex());
                maxCol = Math.max(maxCol, cell.getColumnIndex());
            }
        }
        return found ? new Bounds(minRow, maxRow, minCol, maxCol) : null;
    }

    private record Bounds(int minRow, int maxRow, int minCol, int maxCol) {}
}
