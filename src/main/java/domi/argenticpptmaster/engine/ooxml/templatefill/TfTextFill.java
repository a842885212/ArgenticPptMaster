// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Replace text inside cloned slide shapes while keeping frames editable. */
public final class TfTextFill {

    private TfTextFill() {}

    public static Map<String, Element> shapeKeyMaps(Element slideRoot, int sourceSlide) {
        Map<String, Element> maps = new LinkedHashMap<>();
        int order = 0;
        for (Element container : TfShapes.textContainers(slideRoot)) {
            order++;
            TfShapes.ShapeIdentity identity = TfShapes.shapeIdentity(container, order);
            maps.put("slot_id:s%02d_sh%s".formatted(sourceSlide, identity.id()), container);
            maps.put("shape_id:" + identity.id(), container);
            if (identity.name() != null && !identity.name().isBlank()) {
                maps.put("shape_name:" + identity.name(), container);
            }
        }
        return maps;
    }

    public static void applyReplacements(Element slideRoot, int sourceSlide, JsonNode replacements)
            throws Exception {
        if (replacements == null || !replacements.isArray()) {
            throw new IllegalArgumentException("Slide " + sourceSlide + " replacements must be a list");
        }
        Map<String, Element> maps = shapeKeyMaps(slideRoot, sourceSlide);
        List<String> errors = new ArrayList<>();
        for (JsonNode replacement : replacements) {
            List<String> selectors = TfSelectors.replacementSelectors(replacement);
            Element container = null;
            for (String key : selectors) {
                if (maps.containsKey(key)) {
                    container = maps.get(key);
                    break;
                }
            }
            if (container == null) {
                if (replacement.path("optional").asBoolean(false)) {
                    continue;
                }
                errors.add(selectors.isEmpty() ? "<missing selector>" : String.join(", ", selectors));
                continue;
            }
            setContainerText(container, TfSelectors.replacementText(replacement));
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Missing replacement target(s) on slide " + sourceSlide + ": " + String.join("; ", errors));
        }
    }

    public static void setContainerText(Element container, String text) {
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) {
            lines = new String[] {""};
        }
        List<Element> paragraphs = drawingParagraphs(container);
        if (lines.length > 1 && !paragraphs.isEmpty()) {
            for (int index = 0; index < paragraphs.size(); index++) {
                if (index < lines.length) {
                    setParagraphText(paragraphs.get(index), lines[index]);
                } else {
                    setParagraphText(paragraphs.get(index), "");
                }
            }
            if (lines.length > paragraphs.size()) {
                setParagraphText(
                        paragraphs.get(paragraphs.size() - 1),
                        String.join("\n", java.util.Arrays.copyOfRange(lines, paragraphs.size() - 1, lines.length)));
            }
            return;
        }
        List<Element> textNodes = ensureTextNodes(container);
        if (textNodes.isEmpty()) {
            throw new IllegalStateException("Matched shape does not contain a text body");
        }
        if (lines.length <= textNodes.size()) {
            for (int i = 0; i < textNodes.size(); i++) {
                textNodes.get(i).setTextContent(i < lines.length ? lines[i] : "");
            }
            return;
        }
        textNodes.get(0).setTextContent(text);
        for (int i = 1; i < textNodes.size(); i++) {
            textNodes.get(i).setTextContent("");
        }
    }

    private static List<Element> drawingParagraphs(Element container) {
        List<Element> paragraphs = new ArrayList<>();
        for (Element paragraph : TfXml.descendants(container, "p")) {
            if (OoxmlConstants.DRAWING_NS.equals(paragraph.getNamespaceURI())
                    || paragraph.getNamespaceURI() == null
                    || paragraph.getNamespaceURI().contains("drawingml")) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private static void setParagraphText(Element paragraph, String text) {
        List<Element> textNodes = ensureParagraphTextNodes(paragraph);
        textNodes.get(0).setTextContent(text);
        for (int i = 1; i < textNodes.size(); i++) {
            textNodes.get(i).setTextContent("");
        }
    }

    private static List<Element> ensureParagraphTextNodes(Element paragraph) {
        List<Element> textNodes = TfXml.descendants(paragraph, "t");
        if (!textNodes.isEmpty()) {
            return textNodes;
        }
        Document doc = paragraph.getOwnerDocument();
        Element run = TfXml.firstChild(paragraph, "r");
        if (run == null) {
            run = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:r");
            paragraph.appendChild(run);
        }
        Element textNode = TfXml.firstChild(run, "t");
        if (textNode == null) {
            textNode = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:t");
            run.appendChild(textNode);
        }
        return List.of(textNode);
    }

    private static List<Element> ensureTextNodes(Element container) {
        List<Element> textNodes = TfXml.descendants(container, "t");
        if (!textNodes.isEmpty()) {
            return textNodes;
        }
        Element txBody = TfXml.firstDescendant(container, "txBody");
        if (txBody == null) {
            return List.of();
        }
        Document doc = container.getOwnerDocument();
        Element paragraph = TfXml.firstChild(txBody, "p");
        if (paragraph == null) {
            paragraph = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:p");
            txBody.appendChild(paragraph);
        }
        return ensureParagraphTextNodes(paragraph);
    }
}
