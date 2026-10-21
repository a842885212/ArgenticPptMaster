// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Deep-clone a cloned slide's structured private dependency parts. */
public final class TfClone {

    private static final Set<String> SKIPPED_REL_TYPES = Set.of(
            TfConstants.CHART_REL_TYPE,
            OoxmlConstants.NOTES_SLIDE_REL_TYPE,
            OoxmlConstants.SLIDE_REL_TYPE);

    private TfClone() {}

    public static Function<String, String> makePartAllocator(Map<String, byte[]> entries) {
        Set<String> used = new HashSet<>(entries.keySet());
        return sourcePart -> {
            int slash = sourcePart.lastIndexOf('/');
            String directory = slash >= 0 ? sourcePart.substring(0, slash) : "";
            String basename = slash >= 0 ? sourcePart.substring(slash + 1) : sourcePart;
            int dot = basename.lastIndexOf('.');
            String stem = dot >= 0 ? basename.substring(0, dot) : basename;
            String ext = dot >= 0 ? basename.substring(dot) : "";
            int index = 1;
            while (true) {
                String candidate = (directory.isEmpty() ? "" : directory + "/") + stem + "_tf" + index + ext;
                if (used.add(candidate)) {
                    return candidate;
                }
                index++;
            }
        };
    }

    public static void deepCloneSlidePrivateParts(
            Element slideRelsRoot,
            String newSlidePart,
            Map<String, byte[]> entries,
            Element contentRoot,
            Function<String, String> allocate) throws Exception {
        clonePartPrivateDeps(
                slideRelsRoot,
                newSlidePart,
                entries,
                contentRoot,
                allocate,
                new HashMap<>(),
                SKIPPED_REL_TYPES,
                List.of(),
                Map.of());
    }

    private static void clonePartPrivateDeps(
            Element relsRoot,
            String ownerPart,
            Map<String, byte[]> entries,
            Element contentRoot,
            Function<String, String> allocate,
            Map<String, String> cloned,
            Set<String> skippedRelTypes,
            List<String> cloneDefaultPartPrefixes,
            Map<String, String> backReferences) throws Exception {
        for (Element rel : TfXml.descendants(relsRoot, "Relationship")) {
            if ("External".equals(rel.getAttribute("TargetMode"))) {
                continue;
            }
            String relType = rel.getAttribute("Type");
            String target = rel.getAttribute("Target");
            if (target == null || target.isBlank()) {
                continue;
            }
            String sourcePart = TfXml.normalizePart(target, ownerPart);
            String backReference = backReferences.get(sourcePart);
            if (OoxmlConstants.SLIDE_REL_TYPE.equals(relType) && backReference != null) {
                rel.setAttribute("Target", TfXml.relativeTarget(ownerPart, backReference));
                continue;
            }
            if (TfConstants.SHARED_REL_TYPES.contains(relType) || skippedRelTypes.contains(relType)) {
                continue;
            }
            if (!entries.containsKey(sourcePart)) {
                continue;
            }
            String contentType = TfPackage.overrideContentType(contentRoot, sourcePart);
            boolean cloneDefaultPart = false;
            for (String prefix : cloneDefaultPartPrefixes) {
                if (sourcePart.startsWith(prefix)) {
                    cloneDefaultPart = true;
                    break;
                }
            }
            if (contentType == null && !cloneDefaultPart) {
                continue;
            }
            String newPart = cloned.get(sourcePart);
            if (newPart == null) {
                newPart = allocate.apply(sourcePart);
                entries.put(newPart, entries.get(sourcePart));
                cloned.put(sourcePart, newPart);
                if (contentType != null) {
                    TfPackage.addContentTypeOverride(contentRoot, newPart, contentType);
                }
                byte[] subRelsData = entries.get(TfXml.relsNameForPart(sourcePart));
                if (subRelsData != null) {
                    Document subRels = TfXml.parse(subRelsData);
                    clonePartPrivateDeps(
                            subRels.getDocumentElement(),
                            newPart,
                            entries,
                            contentRoot,
                            allocate,
                            cloned,
                            skippedRelTypes,
                            cloneDefaultPartPrefixes,
                            backReferences);
                    entries.put(TfXml.relsNameForPart(newPart), TfXml.serialize(subRels));
                }
            }
            rel.setAttribute("Target", TfXml.relativeTarget(ownerPart, newPart));
        }
    }

    public static void remapSlideJumpRelationships(
            Element partRoot,
            Element relationshipsRoot,
            String sourceOwnerPart,
            String outputOwnerPart,
            String ownerLabel,
            Map<String, List<String>> outputsBySourcePart,
            Map<String, Integer> sourceIndexByPart,
            String selfSourcePart,
            String selfOutputPart) {
        Set<String> relationshipIds = slideJumpRelationshipIds(partRoot);
        Map<String, Element> relationships = new HashMap<>();
        for (Element rel : TfXml.descendants(relationshipsRoot, "Relationship")) {
            relationships.put(rel.getAttribute("Id"), rel);
        }
        List<String> sortedIds = new ArrayList<>(relationshipIds);
        sortedIds.sort(String::compareTo);
        for (String relationshipId : sortedIds) {
            Element rel = relationships.get(relationshipId);
            if (rel == null) {
                throw new IllegalStateException(
                        ownerLabel + " has a slide jump with missing relationship '" + relationshipId + "'");
            }
            if (!OoxmlConstants.SLIDE_REL_TYPE.equals(rel.getAttribute("Type"))) {
                throw new IllegalStateException(
                        ownerLabel + " slide jump '" + relationshipId + "' does not use a slide relationship");
            }
            if ("External".equals(rel.getAttribute("TargetMode"))) {
                throw new IllegalStateException(ownerLabel + " has an external slide relationship");
            }
            String target = rel.getAttribute("Target");
            if (target == null || target.isBlank()) {
                throw new IllegalStateException(ownerLabel + " has a slide relationship without a target");
            }
            String sourceTargetPart = TfXml.normalizePart(target, sourceOwnerPart);
            String outputTargetPart;
            if (selfSourcePart != null
                    && selfOutputPart != null
                    && sourceTargetPart.equals(selfSourcePart)) {
                outputTargetPart = selfOutputPart;
            } else {
                List<String> outputTargets = outputsBySourcePart.getOrDefault(sourceTargetPart, List.of());
                Integer sourceIndex = sourceIndexByPart.get(sourceTargetPart);
                String targetLabel = sourceIndex != null ? "source slide " + sourceIndex : sourceTargetPart;
                if (outputTargets.isEmpty()) {
                    throw new IllegalStateException(
                            ownerLabel + " links to omitted " + targetLabel
                                    + "; include the target exactly once or remove the link");
                }
                if (outputTargets.size() > 1) {
                    throw new IllegalStateException(
                            ownerLabel + " links to repeated " + targetLabel + "; the output target is ambiguous");
                }
                outputTargetPart = outputTargets.get(0);
            }
            String outputTarget = TfXml.relativeTarget(outputOwnerPart, outputTargetPart);
            if (!outputTarget.equals(rel.getAttribute("Target"))) {
                rel.setAttribute("Target", outputTarget);
            }
        }
    }

    public static void remapReachableSharedLayerSlideJumps(
            Map<String, byte[]> entries,
            List<String[]> outputSlides,
            Map<String, List<String>> outputsBySourcePart,
            Map<String, Integer> sourceIndexByPart) throws Exception {
        String layoutType =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout";
        String masterType =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster";
        List<String> pending = new ArrayList<>();
        for (String[] slide : outputSlides) {
            String partName = slide[0];
            String relsName = slide[1];
            byte[] relsData = entries.get(relsName);
            if (relsData == null) {
                continue;
            }
            Document relsRoot = TfXml.parse(relsData);
            for (Element rel : TfXml.descendants(relsRoot.getDocumentElement(), "Relationship")) {
                if (!layoutType.equals(rel.getAttribute("Type"))) {
                    continue;
                }
                String target = rel.getAttribute("Target");
                if (target != null && !target.isBlank()) {
                    pending.add(TfXml.normalizePart(target, partName));
                }
            }
        }
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String partName = pending.remove(pending.size() - 1);
            if (!visited.add(partName)) {
                continue;
            }
            byte[] partData = entries.get(partName);
            String relsName = TfXml.relsNameForPart(partName);
            byte[] relsData = entries.get(relsName);
            if (partData == null || relsData == null) {
                continue;
            }
            Document partRoot = TfXml.parse(partData);
            Document relsRoot = TfXml.parse(relsData);
            remapSlideJumpRelationships(
                    partRoot.getDocumentElement(),
                    relsRoot.getDocumentElement(),
                    partName,
                    partName,
                    partName,
                    outputsBySourcePart,
                    sourceIndexByPart,
                    null,
                    null);
            entries.put(relsName, TfXml.serialize(relsRoot));
            for (Element rel : TfXml.descendants(relsRoot.getDocumentElement(), "Relationship")) {
                if (!masterType.equals(rel.getAttribute("Type"))) {
                    continue;
                }
                String target = rel.getAttribute("Target");
                if (target != null && !target.isBlank()) {
                    pending.add(TfXml.normalizePart(target, partName));
                }
            }
        }
    }

    private static Set<String> slideJumpRelationshipIds(Element partRoot) {
        Set<String> ids = new HashSet<>();
        for (Element link : TfXml.descendants(partRoot, "hlinkClick")) {
            String action = link.getAttribute("action");
            if (action == null) {
                action = "";
            }
            if (!TfConstants.SLIDE_JUMP_ACTION.equals(action.strip())) {
                continue;
            }
            String relationshipId = TfXml.attrNs(link, OoxmlConstants.OFFICE_REL_NS, "id");
            if (relationshipId != null && !relationshipId.isBlank()) {
                ids.add(relationshipId.strip());
            }
        }
        return ids;
    }
}
