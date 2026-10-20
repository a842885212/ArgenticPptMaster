// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.TemplateFillEngine;
import domi.argenticpptmaster.engine.ooxml.ContentTypesSupport;
import domi.argenticpptmaster.engine.ooxml.MarkdownNotes;
import domi.argenticpptmaster.engine.ooxml.NotesSlideXml;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Java template-fill subset: notes + simple text replacements on cloned slides.
 *
 * <p>Table / chart / transition / deep-clone private parts remain on Python.
 * Production wiring must keep Python as the authority via {@code RoutingTemplateFillEngine}.</p>
 */
public final class JavaTemplateFillEngine implements TemplateFillEngine {

    public static final String SKIP_PREFIX = "[JAVA-SUBSET] skip:";
    public static final String SUBSET_HEADER = "[JAVA-SUBSET] template-fill notes+text";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
        return new EngineResult(0, SKIP_PREFIX + " analyze not implemented", false);
    }

    @Override
    public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
        return new EngineResult(0, SKIP_PREFIX + " check-plan not implemented", false);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return new EngineResult(0, SKIP_PREFIX + " validate not implemented", false);
    }

    @Override
    public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
        try {
            JsonNode plan = MAPPER.readTree(fillPlan.toFile());
            String skipReason = unsupportedReason(plan);
            if (skipReason != null) {
                return new EngineResult(0, SKIP_PREFIX + " " + skipReason, false);
            }
            Files.createDirectories(exportOut.getParent());
            Path extractDir = Files.createTempDirectory("java-template-fill-");
            try {
                PptxPackage.extract(templatePptx, extractDir);
                applyPlanInPlace(extractDir, plan);
                PptxPackage.zipDirectory(extractDir, exportOut);
                return new EngineResult(0, SUBSET_HEADER + " wrote " + exportOut, false);
            } finally {
                deleteRecursively(extractDir);
            }
        } catch (Exception ex) {
            return new EngineResult(1, SUBSET_HEADER + " apply failed: " + ex.getMessage(), false);
        }
    }

    public static boolean isSkip(EngineResult result) {
        return result != null && result.output() != null && result.output().startsWith(SKIP_PREFIX);
    }

    static String unsupportedReason(JsonNode plan) {
        JsonNode slides = plan.path("slides");
        if (!slides.isArray() || slides.isEmpty()) {
            return "plan must contain a non-empty slides list";
        }
        for (JsonNode item : slides) {
            if (item.path("table_edits").isArray() && !item.path("table_edits").isEmpty()) {
                return "table_edits not implemented";
            }
            if (item.path("chart_edits").isArray() && !item.path("chart_edits").isEmpty()) {
                return "chart_edits not implemented";
            }
            if (item.hasNonNull("transition") || item.hasNonNull("advance_after")) {
                return "per-slide transitions not implemented";
            }
        }
        return null;
    }

    private static void applyPlanInPlace(Path extractDir, JsonNode plan) throws Exception {
        List<SlidePartRef> sourceSlides = SlideParts.read(extractDir);
        Map<Integer, SlidePartRef> byIndex = new HashMap<>();
        for (SlidePartRef slide : sourceSlides) {
            byIndex.put(slide.index(), slide);
        }

        Map<String, byte[]> entries = readAllEntries(extractDir);
        String notesMasterTarget = findNotesMasterTarget(entries);

        Document presentation = parseXml(entries.get("ppt/presentation.xml"));
        Document presentationRels = parseXml(entries.get("ppt/_rels/presentation.xml.rels"));
        String contentTypes = new String(entries.get("[Content_Types].xml"), StandardCharsets.UTF_8);

        Element sldIdLst = firstChild(presentation.getDocumentElement(), "sldIdLst");
        if (sldIdLst == null) {
            sldIdLst = presentation.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:sldIdLst");
            presentation.getDocumentElement().appendChild(sldIdLst);
        }
        while (sldIdLst.hasChildNodes()) {
            sldIdLst.removeChild(sldIdLst.getFirstChild());
        }
        removeRelationshipsOfType(presentationRels, OoxmlConstants.SLIDE_REL_TYPE);

        int nextSlideNumber = maxSlidePartNumber(entries) + 1;
        int nextSlideId = 256;
        int nextRel = RelationshipsSupport.maxRid(
                        new String(entries.get("ppt/_rels/presentation.xml.rels"), StandardCharsets.UTF_8))
                + 1;
        Set<String> noteParts = new HashSet<>();
        JsonNode slides = plan.get("slides");

        for (int offset = 0; offset < slides.size(); offset++) {
            JsonNode item = slides.get(offset);
            int sourceSlide = item.path("source_slide").asInt(item.path("slide").asInt(0));
            if (sourceSlide <= 0) {
                throw new IllegalArgumentException("slide item missing source_slide");
            }
            SlidePartRef source = byIndex.get(sourceSlide);
            if (source == null) {
                throw new IllegalArgumentException("source slide not found: " + sourceSlide);
            }

            int newSlideNumber = nextSlideNumber + offset;
            String newPart = "ppt/slides/slide" + newSlideNumber + ".xml";
            String newRels = "ppt/slides/_rels/slide" + newSlideNumber + ".xml.rels";
            String newRid = "rId" + (nextRel + offset);

            byte[] sourceSlideXml = entries.get(source.partName());
            String slideXml = new String(sourceSlideXml, StandardCharsets.UTF_8);
            slideXml = applyReplacements(slideXml, sourceSlide, item.path("replacements"));
            entries.put(newPart, slideXml.getBytes(StandardCharsets.UTF_8));

            byte[] sourceRels = entries.getOrDefault(
                    "ppt/slides/_rels/slide" + source.slideNumber() + ".xml.rels",
                    emptyRels().getBytes(StandardCharsets.UTF_8));
            String notesText = item.path("notes").asText(item.path("speaker_notes").asText(""));
            Map.Entry<byte[], Map<String, byte[]>> notesBundle =
                    slideRelsWithNotes(sourceRels, newSlideNumber, notesText, notesMasterTarget);
            entries.put(newRels, notesBundle.getKey());
            entries.putAll(notesBundle.getValue());
            noteParts.addAll(notesBundle.getValue().keySet().stream()
                    .filter(name -> name.endsWith(".xml") && !name.contains("/_rels/"))
                    .toList());

            contentTypes = ContentTypesSupport.addSlideOverride(contentTypes, newPart);
            for (String notePart : noteParts) {
                if (notePart.contains("notesSlide" + newSlideNumber)) {
                    contentTypes = ContentTypesSupport.addOverride(
                            contentTypes, notePart, OoxmlConstants.CONTENT_TYPE_NOTES_SLIDE);
                }
            }

            Element rel = presentationRels.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
            rel.setAttribute("Id", newRid);
            rel.setAttribute("Type", OoxmlConstants.SLIDE_REL_TYPE);
            rel.setAttribute("Target", "slides/slide" + newSlideNumber + ".xml");
            presentationRels.getDocumentElement().appendChild(rel);

            Element sldId = presentation.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:sldId");
            sldId.setAttribute("id", String.valueOf(nextSlideId + offset));
            sldId.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:id", newRid);
            sldIdLst.appendChild(sldId);
        }

        entries.put("ppt/presentation.xml", serializeXml(presentation));
        entries.put("ppt/_rels/presentation.xml.rels", serializeXml(presentationRels));
        entries.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        writeAllEntries(extractDir, entries);
    }

    private static String applyReplacements(String slideXml, int sourceSlide, JsonNode replacements)
            throws Exception {
        if (replacements == null || !replacements.isArray() || replacements.isEmpty()) {
            return slideXml;
        }
        Document document = parseXml(slideXml.getBytes(StandardCharsets.UTF_8));
        Map<String, Element> maps = shapeKeyMaps(document, sourceSlide);
        List<String> errors = new ArrayList<>();
        for (JsonNode replacement : replacements) {
            List<String> selectors = new ArrayList<>();
            if (replacement.hasNonNull("slot_id")) {
                selectors.add("slot_id:" + replacement.get("slot_id").asText());
            }
            if (replacement.hasNonNull("shape_id")) {
                selectors.add("shape_id:" + replacement.get("shape_id").asText());
            }
            if (replacement.hasNonNull("shape_name")) {
                selectors.add("shape_name:" + replacement.get("shape_name").asText());
            }
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
                errors.add(String.join(", ", selectors));
                continue;
            }
            setContainerText(container, replacementText(replacement));
        }
        if (!errors.isEmpty()) {
            throw new IOException(
                    "Missing replacement target(s) on slide " + sourceSlide + ": " + String.join("; ", errors));
        }
        return new String(serializeXml(document), StandardCharsets.UTF_8);
    }

    private static Map<String, Element> shapeKeyMaps(Document document, int sourceSlide) {
        Map<String, Element> maps = new LinkedHashMap<>();
        List<Element> containers = textContainers(document.getDocumentElement());
        int order = 0;
        for (Element container : containers) {
            order++;
            ShapeIdentity identity = shapeIdentity(container, order);
            maps.put("slot_id:s%02d_sh%s".formatted(sourceSlide, identity.id()), container);
            maps.put("shape_id:" + identity.id(), container);
            if (identity.name() != null && !identity.name().isBlank()) {
                maps.put("shape_name:" + identity.name(), container);
            }
        }
        return maps;
    }

    private static List<Element> textContainers(Element root) {
        List<Element> containers = new ArrayList<>();
        collectByLocalName(root, "sp", containers);
        collectByLocalName(root, "graphicFrame", containers);
        List<Element> withText = new ArrayList<>();
        for (Element element : containers) {
            if (hasDescendant(element, "txBody") || hasDescendant(element, "t")) {
                withText.add(element);
            }
        }
        return withText;
    }

    private static void collectByLocalName(Element root, String localName, List<Element> out) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
    }

    private static boolean hasDescendant(Element root, String localName) {
        return root.getElementsByTagNameNS("*", localName).getLength() > 0;
    }

    private static ShapeIdentity shapeIdentity(Element container, int order) {
        NodeList cNvPr = container.getElementsByTagNameNS("*", "cNvPr");
        if (cNvPr.getLength() > 0) {
            Element el = (Element) cNvPr.item(0);
            String id = el.getAttribute("id");
            String name = el.getAttribute("name");
            if (id == null || id.isBlank()) {
                id = String.valueOf(order);
            }
            return new ShapeIdentity(id, name);
        }
        return new ShapeIdentity(String.valueOf(order), "");
    }

    private static void setContainerText(Element container, String text) {
        List<Element> textNodes = new ArrayList<>();
        NodeList nodes = container.getElementsByTagNameNS(OoxmlConstants.DRAWING_NS, "t");
        if (nodes.getLength() == 0) {
            nodes = container.getElementsByTagNameNS("*", "t");
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            textNodes.add((Element) nodes.item(i));
        }
        if (textNodes.isEmpty()) {
            throw new IllegalStateException("Matched shape does not contain a text body");
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) {
            lines = new String[] {""};
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

    private static String replacementText(JsonNode replacement) {
        if (replacement.has("paragraphs") && replacement.get("paragraphs").isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : replacement.get("paragraphs")) {
                parts.add(item.asText(""));
            }
            return String.join("\n", parts);
        }
        return replacement.path("text").asText("");
    }

    private static Map.Entry<byte[], Map<String, byte[]>> slideRelsWithNotes(
            byte[] relsBytes, int slideNumber, String notesText, String notesMasterTarget) throws Exception {
        Document root = parseXml(relsBytes == null || relsBytes.length == 0
                ? emptyRels().getBytes(StandardCharsets.UTF_8)
                : relsBytes);
        NodeList relationships = root.getDocumentElement().getElementsByTagNameNS("*", "Relationship");
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < relationships.getLength(); i++) {
            Element rel = (Element) relationships.item(i);
            if (OoxmlConstants.NOTES_SLIDE_REL_TYPE.equals(rel.getAttribute("Type"))) {
                toRemove.add(rel);
            }
        }
        for (Node node : toRemove) {
            node.getParentNode().removeChild(node);
        }

        Map<String, byte[]> noteEntries = new LinkedHashMap<>();
        String plain = MarkdownNotes.toPlainText(notesText == null ? "" : notesText.strip());
        if (!plain.isEmpty()) {
            int maxRid = RelationshipsSupport.maxRid(new String(serializeXml(root), StandardCharsets.UTF_8));
            String relId = "rId" + (maxRid + 1);
            String notesPart = "ppt/notesSlides/notesSlide" + slideNumber + ".xml";
            String notesRelsPart = "ppt/notesSlides/_rels/notesSlide" + slideNumber + ".xml.rels";
            Element rel = root.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
            rel.setAttribute("Id", relId);
            rel.setAttribute("Type", OoxmlConstants.NOTES_SLIDE_REL_TYPE);
            rel.setAttribute("Target", "../notesSlides/notesSlide" + slideNumber + ".xml");
            root.getDocumentElement().appendChild(rel);
            noteEntries.put(notesPart, NotesSlideXml.createNotesSlideXml(slideNumber, plain).getBytes(StandardCharsets.UTF_8));
            noteEntries.put(
                    notesRelsPart,
                    createNotesRelsXml(slideNumber, notesMasterTarget).getBytes(StandardCharsets.UTF_8));
        }
        return Map.entry(serializeXml(root), noteEntries);
    }

    private static String createNotesRelsXml(int slideNumber, String notesMasterTarget) {
        if (notesMasterTarget == null || notesMasterTarget.isBlank()) {
            notesMasterTarget = "../notesMasters/notesMaster1.xml";
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="%s" Target="%s"/>
                  <Relationship Id="rId2" Type="%s" Target="../slides/slide%d.xml"/>
                </Relationships>
                """
                .formatted(
                        OoxmlConstants.NOTES_MASTER_REL_TYPE,
                        notesMasterTarget,
                        OoxmlConstants.SLIDE_REL_TYPE,
                        slideNumber);
    }

    private static String findNotesMasterTarget(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith("ppt/notesSlides/_rels/notesSlide")
                    || !entry.getKey().endsWith(".xml.rels")) {
                continue;
            }
            String xml = new String(entry.getValue(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("Type=\"" + Pattern.quote(OoxmlConstants.NOTES_MASTER_REL_TYPE)
                            + "\"[^>]*Target=\"([^\"]+)\"")
                    .matcher(xml);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "../notesMasters/notesMaster1.xml";
    }

    private static int maxSlidePartNumber(Map<String, byte[]> entries) {
        int max = 0;
        Pattern pattern = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");
        for (String name : entries.keySet()) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    private static Map<String, byte[]> readAllEntries(Path extractDir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var walk = Files.walk(extractDir)) {
            Path root = extractDir.toAbsolutePath().normalize();
            walk.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                try {
                    entries.put(name, Files.readAllBytes(path));
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        return entries;
    }

    private static void writeAllEntries(Path extractDir, Map<String, byte[]> entries) throws IOException {
        deleteRecursively(extractDir);
        Files.createDirectories(extractDir);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Path target = extractDir.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(bytes));
    }

    private static byte[] serializeXml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toByteArray();
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static void removeRelationshipsOfType(Document rels, String type) {
        NodeList relationships = rels.getDocumentElement().getElementsByTagNameNS("*", "Relationship");
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < relationships.getLength(); i++) {
            Element rel = (Element) relationships.item(i);
            if (type.equals(rel.getAttribute("Type"))) {
                toRemove.add(rel);
            }
        }
        for (Node node : toRemove) {
            node.getParentNode().removeChild(node);
        }
    }

    private static String emptyRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\""
                + OoxmlConstants.PACKAGE_REL_NS
                + "\"></Relationships>";
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private record ShapeIdentity(String id, String name) {
    }
}
