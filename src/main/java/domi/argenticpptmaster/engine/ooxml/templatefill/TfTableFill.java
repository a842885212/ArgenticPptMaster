// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/** Edit native PowerPoint table cell text on cloned slides. */
public final class TfTableFill {

    private TfTableFill() {}

    public static Map<String, Element> tableKeyMaps(Element slideRoot, int sourceSlide) {
        Map<String, Element> maps = new LinkedHashMap<>();
        int order = 0;
        for (Element container : TfShapes.tableContainers(slideRoot)) {
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
            maps.put("table_id:s%02d_tbl%s".formatted(sourceSlide, identity.id()), container);
            maps.put("shape_id:" + identity.id(), container);
            if (identity.name() != null && !identity.name().isBlank()) {
                maps.put("shape_name:" + identity.name(), container);
            }
        }
        return maps;
    }

    public static void applyTableEdits(Element slideRoot, int sourceSlide, JsonNode tableEdits) {
        if (tableEdits == null || !tableEdits.isArray()) {
            throw new IllegalArgumentException("Slide " + sourceSlide + " table_edits must be a list");
        }
        Map<String, Element> maps = tableKeyMaps(slideRoot, sourceSlide);
        List<String> errors = new ArrayList<>();
        List<Map.Entry<Element, String>> pending = new ArrayList<>();
        for (JsonNode tableEdit : tableEdits) {
            List<String> selectors = TfSelectors.tableSelectors(tableEdit);
            Element tableFrame = null;
            for (String key : selectors) {
                if (maps.containsKey(key)) {
                    tableFrame = maps.get(key);
                    break;
                }
            }
            if (tableFrame == null) {
                if (tableEdit.path("optional").asBoolean(false)) {
                    continue;
                }
                errors.add(selectors.isEmpty() ? "<missing selector>" : String.join(", ", selectors));
                continue;
            }
            Element table = TfXml.firstDescendant(tableFrame, "tbl");
            List<Element> rows = table == null ? List.of() : TfXml.childrenByLocalName(table, "tr");
            JsonNode cells = tableEdit.path("cells");
            if (!cells.isArray()) {
                throw new IllegalArgumentException("Slide " + sourceSlide + " table edit cells must be a list");
            }
            for (JsonNode cellEdit : cells) {
                int rowIndex = cellEdit.path("row").asInt(-1);
                int colIndex = cellEdit.path("col").asInt(-1);
                String target = selectors.isEmpty() ? "<table>" : selectors.get(0);
                if (rowIndex < 0 || rowIndex >= rows.size()) {
                    errors.add(target + " row=" + rowIndex);
                    continue;
                }
                List<Element> rowCells = TfXml.childrenByLocalName(rows.get(rowIndex), "tc");
                if (colIndex < 0 || colIndex >= rowCells.size()) {
                    errors.add(target + " row=" + rowIndex + " col=" + colIndex);
                    continue;
                }
                Element targetCell = rowCells.get(colIndex);
                if (Boolean.TRUE.equals(TfEditSafety.tableCellMergeInfo(targetCell).get("is_merge_slave"))) {
                    errors.add(target + " row=" + rowIndex + " col=" + colIndex
                            + " is a merged-cell slave [table_cell_is_merge_slave]; edit the merge anchor instead");
                    continue;
                }
                pending.add(Map.entry(targetCell, TfSelectors.tableCellText(cellEdit)));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid table edit target(s) on slide " + sourceSlide + ": " + String.join("; ", errors));
        }
        for (Map.Entry<Element, String> edit : pending) {
            TfTextFill.setContainerText(edit.getKey(), edit.getValue());
        }
    }
}
