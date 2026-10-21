// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import domi.argenticpptmaster.engine.ooxml.MarkdownNotes;
import domi.argenticpptmaster.engine.ooxml.NotesSlideXml;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Speaker-notes parts for cloned slides. */
public final class TfNotes {

    private TfNotes() {}

    public static String findNotesMasterTarget(Map<String, byte[]> entries) throws Exception {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("ppt/notesSlides/_rels/notesSlide") || !name.endsWith(".xml.rels")) {
                continue;
            }
            Document root = TfXml.parse(entry.getValue());
            for (Element rel : TfXml.descendants(root.getDocumentElement(), "Relationship")) {
                if (OoxmlConstants.NOTES_MASTER_REL_TYPE.equals(rel.getAttribute("Type"))) {
                    return rel.getAttribute("Target");
                }
            }
        }
        byte[] presentationRels = entries.get("ppt/_rels/presentation.xml.rels");
        if (presentationRels == null) {
            return "../notesMasters/notesMaster1.xml";
        }
        Document root = TfXml.parse(presentationRels);
        for (Element rel : TfXml.descendants(root.getDocumentElement(), "Relationship")) {
            if (!OoxmlConstants.NOTES_MASTER_REL_TYPE.equals(rel.getAttribute("Type"))) {
                continue;
            }
            String target = rel.getAttribute("Target");
            if (target == null || target.isBlank()) {
                return "../notesMasters/notesMaster1.xml";
            }
            if (target.startsWith("/")) {
                target = target.replaceFirst("^/+", "");
            } else {
                target = TfXml.normalizePart(target, "ppt/presentation.xml");
            }
            return TfXml.relativeTarget("ppt/notesSlides/notesSlide1.xml", target);
        }
        return "../notesMasters/notesMaster1.xml";
    }

    public static Map.Entry<byte[], Map<String, byte[]>> slideRelsWithNotes(
            byte[] relsBytes, int slideNumber, String notesText, String notesMasterTarget) throws Exception {
        Document root = relsBytes == null || relsBytes.length == 0
                ? TfXml.emptyRelationships()
                : TfXml.parse(relsBytes);
        for (Element rel : new java.util.ArrayList<>(TfXml.descendants(root.getDocumentElement(), "Relationship"))) {
            if (OoxmlConstants.NOTES_SLIDE_REL_TYPE.equals(rel.getAttribute("Type"))) {
                rel.getParentNode().removeChild(rel);
            }
        }
        Map<String, byte[]> noteEntries = new LinkedHashMap<>();
        String plain = MarkdownNotes.toPlainText(notesText == null ? "" : notesText.strip());
        if (!plain.isEmpty()) {
            String relId = "rId" + (TfXml.maxNumericRid(root.getDocumentElement()) + 1);
            String notesPart = "ppt/notesSlides/notesSlide" + slideNumber + ".xml";
            String notesRelsPart = "ppt/notesSlides/_rels/notesSlide" + slideNumber + ".xml.rels";
            Element rel = root.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
            rel.setAttribute("Id", relId);
            rel.setAttribute("Type", OoxmlConstants.NOTES_SLIDE_REL_TYPE);
            rel.setAttribute("Target", "../notesSlides/notesSlide" + slideNumber + ".xml");
            root.getDocumentElement().appendChild(rel);
            noteEntries.put(notesPart, NotesSlideXml.createNotesSlideXml(slideNumber, plain).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            noteEntries.put(notesRelsPart, createNotesRelsXml(slideNumber, notesMasterTarget));
        }
        return Map.entry(TfXml.serialize(root), noteEntries);
    }

    private static byte[] createNotesRelsXml(int slideNumber, String notesMasterTarget) throws Exception {
        if (notesMasterTarget == null || notesMasterTarget.isBlank()) {
            notesMasterTarget = "../notesMasters/notesMaster1.xml";
        }
        Document root = TfXml.emptyRelationships();
        Element master = root.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
        master.setAttribute("Id", "rId1");
        master.setAttribute("Type", OoxmlConstants.NOTES_MASTER_REL_TYPE);
        master.setAttribute("Target", notesMasterTarget);
        root.getDocumentElement().appendChild(master);
        Element slide = root.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
        slide.setAttribute("Id", "rId2");
        slide.setAttribute("Type", OoxmlConstants.SLIDE_REL_TYPE);
        slide.setAttribute("Target", "../slides/slide" + slideNumber + ".xml");
        root.getDocumentElement().appendChild(slide);
        return TfXml.serialize(root);
    }
}
