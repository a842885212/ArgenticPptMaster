// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Read SmartArt diagram inventory for slide-library analysis.
 *
 * <p>Inventory-only: template-fill preserves DiagramML unchanged.</p>
 */
public final class TfDiagramRead {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TfDiagramRead() {}

    public static ArrayNode readSmartArtDiagrams(Map<String, byte[]> entries, String slidePart, int slideIndex)
            throws Exception {
        ArrayNode diagrams = MAPPER.createArrayNode();
        byte[] slideBytes = entries.get(slidePart);
        if (slideBytes == null) {
            return diagrams;
        }
        Document slideDoc = TfXml.parse(slideBytes);
        int order = 0;
        for (Element frame : TfXml.descendants(slideDoc.getDocumentElement(), "graphicFrame")) {
            Element graphicData = TfXml.firstDescendant(frame, "graphicData");
            if (graphicData == null) {
                continue;
            }
            if (!TfConstants.DIAGRAM_URI.equals(graphicData.getAttribute("uri"))) {
                continue;
            }
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(frame, order);
            ObjectNode diagram = MAPPER.createObjectNode();
            diagram.put("diagram_id", "s%02d_dgm%s".formatted(slideIndex, identity.id()));
            diagram.put("shape_id", identity.id());
            diagram.put("shape_name", identity.name());
            diagram.putArray("text_items");
            diagrams.add(diagram);
        }
        return diagrams;
    }
}
