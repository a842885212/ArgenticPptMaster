// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import domi.argenticpptmaster.engine.ooxml.nativeenhance.SlideMotionSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Apply fill plan: clone selected slides and write replacements. */
public final class TfApplier {

    private record PlannedSlideClone(
            int offset,
            JsonNode item,
            int sourceSlide,
            SlidePartRef sourceRef,
            int slideNumber,
            String partName,
            String relsName,
            String presentationRid) {}

    private TfApplier() {}

    public static void applyPlan(
            Path pptxPath,
            JsonNode plan,
            Path outputPath,
            String transition,
            double transitionDuration) throws Exception {
        JsonNode planSlides = plan.path("slides");
        if (!planSlides.isArray() || planSlides.isEmpty()) {
            throw new IllegalStateException("Plan must contain a non-empty 'slides' list");
        }
        Path extractDir = Files.createTempDirectory(outputPath.getParent(), "java-template-fill-");
        try {
            PptxPackage.extract(pptxPath, extractDir);
            Map<String, byte[]> entries = readEntries(extractDir);
            List<SlidePartRef> slideRefsList = SlideParts.read(extractDir);
            Map<Integer, SlidePartRef> slideRefs = new HashMap<>();
            for (SlidePartRef ref : slideRefsList) {
                slideRefs.put(ref.index(), ref);
            }
            String notesMasterTarget = TfNotes.findNotesMasterTarget(entries);

            Document presDoc = TfXml.parse(entries.get("ppt/presentation.xml"));
            Document presRelsDoc = TfXml.parse(entries.get("ppt/_rels/presentation.xml.rels"));
            Document contentDoc = TfXml.parse(entries.get("[Content_Types].xml"));
            Element contentRoot = TfPackage.contentTypeRoot(contentDoc);
            Element sldIdLst = TfXml.firstChild(presDoc.getDocumentElement(), "sldIdLst");
            if (sldIdLst == null) {
                sldIdLst = presDoc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:sldIdLst");
                presDoc.getDocumentElement().appendChild(sldIdLst);
            }
            while (sldIdLst.hasChildNodes()) {
                sldIdLst.removeChild(sldIdLst.getFirstChild());
            }
            for (Element rel : new ArrayList<>(TfXml.descendants(presRelsDoc.getDocumentElement(), "Relationship"))) {
                if (OoxmlConstants.SLIDE_REL_TYPE.equals(rel.getAttribute("Type"))) {
                    rel.getParentNode().removeChild(rel);
                }
            }

            int nextSlideNumber = TfPackage.maxSlidePartNumber(entries) + 1;
            int nextSlideId = TfPackage.maxSlideId(sldIdLst) + 1;
            int nextRelNumber = TfXml.maxNumericRid(presRelsDoc.getDocumentElement()) + 1;
            int nextChartNumber = TfPackage.maxChartPartNumber(entries);
            int nextEmbeddingNumber = TfPackage.maxEmbeddingPartNumber(entries);
            Function<String, String> allocate = TfClone.makePartAllocator(entries);
            boolean wroteAutoAdvance = false;

            List<PlannedSlideClone> roster = buildCloneRoster(planSlides, slideRefs, nextSlideNumber, nextRelNumber);
            Map<String, SlidePartRef> sourceRefByPart = new HashMap<>();
            for (SlidePartRef ref : slideRefs.values()) {
                sourceRefByPart.put(ref.partName(), ref);
            }
            Map<String, List<String>> outputsBySourcePart = new LinkedHashMap<>();
            for (PlannedSlideClone clone : roster) {
                outputsBySourcePart
                        .computeIfAbsent(clone.sourceRef().partName(), key -> new ArrayList<>())
                        .add(clone.partName());
            }
            Map<String, Integer> sourceIndexByPart = new HashMap<>();
            for (SlidePartRef ref : slideRefs.values()) {
                sourceIndexByPart.put(ref.partName(), ref.index());
            }

            for (PlannedSlideClone clone : roster) {
                JsonNode item = clone.item();
                int sourceSlide = clone.sourceSlide();
                SlidePartRef sourceRef = clone.sourceRef();
                byte[] sourceSlideXml = entries.get(sourceRef.partName());
                Document slideDoc = TfXml.parse(sourceSlideXml);
                Element slideRoot = slideDoc.getDocumentElement();

                JsonNode replacements = item.path("replacements");
                if (!replacements.isMissingNode() && !replacements.isArray()) {
                    throw new IllegalStateException("Slide " + sourceSlide + " replacements must be a list");
                }
                TfTextFill.applyReplacements(
                        slideRoot, sourceSlide, replacements.isMissingNode() ? MAPPER_EMPTY_ARRAY : replacements);

                JsonNode tableEdits = item.path("table_edits");
                if (!tableEdits.isMissingNode() && !tableEdits.isArray()) {
                    throw new IllegalStateException("Slide " + sourceSlide + " table_edits must be a list");
                }
                TfTableFill.applyTableEdits(
                        slideRoot, sourceSlide, tableEdits.isMissingNode() ? MAPPER_EMPTY_ARRAY : tableEdits);

                TfTransitions.ResolvedTransition resolved = TfTransitions.resolveSlideTransition(
                        item,
                        transition == null ? TfConstants.DEFAULT_TRANSITION : transition,
                        transitionDuration);
                boolean slideHasAutoAdvance = TfTransitions.setSlideTransition(
                        slideRoot,
                        resolved.effect(),
                        resolved.effectOptions(),
                        resolved.duration(),
                        resolved.advanceAfter());
                if (resolved.advanceAfter() != null && slideHasAutoAdvance) {
                    wroteAutoAdvance = true;
                }

                byte[] sourceRels = entries.get(TfXml.relsNameForPart(sourceRef.partName()));
                Document slideRelsDoc = sourceRels == null ? TfXml.emptyRelationships() : TfXml.parse(sourceRels);
                TfClone.remapSlideJumpRelationships(
                        slideRoot,
                        slideRelsDoc.getDocumentElement(),
                        sourceRef.partName(),
                        clone.partName(),
                        "Source slide " + sourceSlide,
                        outputsBySourcePart,
                        sourceIndexByPart,
                        sourceRef.partName(),
                        clone.partName());
                TfClone.deepCloneSlidePrivateParts(
                        slideRelsDoc.getDocumentElement(),
                        clone.partName(),
                        entries,
                        contentRoot,
                        allocate);

                JsonNode chartEdits = item.path("chart_edits");
                if (!chartEdits.isMissingNode() && !chartEdits.isArray()) {
                    throw new IllegalStateException("Slide " + sourceSlide + " chart_edits must be a list");
                }
                int[] next = TfChartFill.applyChartEdits(
                        slideRoot,
                        slideRelsDoc.getDocumentElement(),
                        entries,
                        contentRoot,
                        sourceSlide,
                        clone.partName(),
                        chartEdits.isMissingNode() ? MAPPER_EMPTY_ARRAY : chartEdits,
                        nextChartNumber,
                        nextEmbeddingNumber);
                nextChartNumber = next[0];
                nextEmbeddingNumber = next[1];

                entries.put(clone.partName(), TfXml.serialize(slideDoc));
                String notesText = item.path("notes").asText(item.path("speaker_notes").asText(""));
                Map.Entry<byte[], Map<String, byte[]>> notesBundle = TfNotes.slideRelsWithNotes(
                        TfXml.serialize(slideRelsDoc),
                        clone.slideNumber(),
                        notesText,
                        notesMasterTarget);
                entries.put(clone.relsName(), notesBundle.getKey());
                entries.putAll(notesBundle.getValue());
                TfPackage.addSlideOverride(contentRoot, clone.partName());
                if (!notesBundle.getValue().isEmpty()) {
                    TfPackage.addNotesOverride(
                            contentRoot, "ppt/notesSlides/notesSlide" + clone.slideNumber() + ".xml");
                }

                Element rel = presRelsDoc.createElementNS(OoxmlConstants.PACKAGE_REL_NS, "Relationship");
                rel.setAttribute("Id", clone.presentationRid());
                rel.setAttribute("Type", OoxmlConstants.SLIDE_REL_TYPE);
                rel.setAttribute("Target", "slides/slide" + clone.slideNumber() + ".xml");
                presRelsDoc.getDocumentElement().appendChild(rel);

                Element sldId = presDoc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:sldId");
                sldId.setAttribute("id", String.valueOf(nextSlideId + clone.offset()));
                sldId.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:id", clone.presentationRid());
                sldIdLst.appendChild(sldId);
            }

            List<String[]> outputSlides = new ArrayList<>();
            for (PlannedSlideClone clone : roster) {
                outputSlides.add(new String[] {clone.partName(), clone.relsName()});
            }
            TfClone.remapReachableSharedLayerSlideJumps(
                    entries, outputSlides, outputsBySourcePart, sourceIndexByPart);

            entries.put("ppt/presentation.xml", TfXml.serialize(presDoc));
            entries.put("ppt/_rels/presentation.xml.rels", TfXml.serialize(presRelsDoc));
            TfPackage.pruneUnreferencedParts(entries, contentRoot);
            entries.put("[Content_Types].xml", TfXml.serialize(contentDoc));

            writeEntries(extractDir, entries);
            if (wroteAutoAdvance) {
                SlideMotionSupport.setDirectoryUseTimings(extractDir, true);
            }
            Files.createDirectories(outputPath.getParent());
            PptxPackage.zipDirectory(extractDir, outputPath);
        } finally {
            deleteRecursively(extractDir);
        }
    }

    private static final JsonNode MAPPER_EMPTY_ARRAY =
            new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();

    private static List<PlannedSlideClone> buildCloneRoster(
            JsonNode planSlides,
            Map<Integer, SlidePartRef> slideRefs,
            int nextSlideNumber,
            int nextRelNumber) {
        List<PlannedSlideClone> roster = new ArrayList<>();
        for (int offset = 0; offset < planSlides.size(); offset++) {
            JsonNode item = planSlides.get(offset);
            if (item == null || !item.isObject()) {
                throw new IllegalStateException("Plan slide " + (offset + 1) + " must be an object");
            }
            int sourceSlide = item.path("source_slide").asInt(0);
            SlidePartRef sourceRef = slideRefs.get(sourceSlide);
            if (sourceRef == null) {
                throw new IllegalStateException("Plan references a missing source slide: " + sourceSlide);
            }
            int slideNumber = nextSlideNumber + offset;
            roster.add(new PlannedSlideClone(
                    offset,
                    item,
                    sourceSlide,
                    sourceRef,
                    slideNumber,
                    "ppt/slides/slide" + slideNumber + ".xml",
                    "ppt/slides/_rels/slide" + slideNumber + ".xml.rels",
                    "rId" + (nextRelNumber + offset)));
        }
        return roster;
    }

    private static Map<String, byte[]> readEntries(Path extractDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var walk = Files.walk(extractDir)) {
            Path root = extractDir.toAbsolutePath().normalize();
            walk.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                try {
                    entries.put(name, Files.readAllBytes(path));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        return entries;
    }

    private static void writeEntries(Path extractDir, Map<String, byte[]> entries) throws Exception {
        deleteRecursively(extractDir);
        Files.createDirectories(extractDir);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Path target = extractDir.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
