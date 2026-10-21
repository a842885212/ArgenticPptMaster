// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Write-side OOXML package plumbing for the apply stage. */
public final class TfPackage {

    private TfPackage() {}

    public static Element contentTypeRoot(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !"Types".equals(root.getLocalName())) {
            throw new IllegalStateException("[Content_Types].xml has an unexpected root element");
        }
        return root;
    }

    public static void addContentTypeOverride(Element contentRoot, String partName, String contentType) {
        String partPn = "/" + partName.replaceFirst("^/+", "");
        for (Element override : TfXml.descendants(contentRoot, "Override")) {
            if (partPn.equals(override.getAttribute("PartName"))) {
                return;
            }
        }
        Document doc = contentRoot.getOwnerDocument();
        Element override = doc.createElementNS(OoxmlConstants.CONTENT_TYPES_NS, "Override");
        override.setAttribute("PartName", partPn);
        override.setAttribute("ContentType", contentType);
        contentRoot.appendChild(override);
    }

    public static void addSlideOverride(Element contentRoot, String partName) {
        addContentTypeOverride(contentRoot, partName, OoxmlConstants.CONTENT_TYPE_SLIDE);
    }

    public static void addNotesOverride(Element contentRoot, String partName) {
        addContentTypeOverride(contentRoot, partName, OoxmlConstants.CONTENT_TYPE_NOTES_SLIDE);
    }

    public static Element findRelationship(Element root, String relId) {
        for (Element rel : TfXml.descendants(root, "Relationship")) {
            if (relId.equals(rel.getAttribute("Id"))) {
                return rel;
            }
        }
        return null;
    }

    public static int maxSlidePartNumber(Map<String, byte[]> entries) {
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

    public static int maxSlideId(Element sldIdLst) {
        int maxId = 255;
        for (Element sldId : TfXml.childrenByLocalName(sldIdLst, "sldId")) {
            try {
                maxId = Math.max(maxId, Integer.parseInt(sldId.getAttribute("id")));
            } catch (NumberFormatException ignored) {
            }
        }
        return maxId;
    }

    public static int maxChartPartNumber(Map<String, byte[]> entries) {
        int max = 0;
        Pattern pattern = Pattern.compile("^ppt/charts/chart(\\d+)\\.xml$");
        for (String name : entries.keySet()) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    public static int maxEmbeddingPartNumber(Map<String, byte[]> entries) {
        int max = 0;
        Pattern pattern = Pattern.compile("^ppt/embeddings/templateFillChart(\\d+)\\.xlsx$");
        for (String name : entries.keySet()) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    public static String overrideContentType(Element contentRoot, String part) {
        String partPn = "/" + part.replaceFirst("^/+", "");
        for (Element override : TfXml.descendants(contentRoot, "Override")) {
            if (partPn.equals(override.getAttribute("PartName"))) {
                return override.getAttribute("ContentType");
            }
        }
        return null;
    }

    public static void pruneUnreferencedParts(Map<String, byte[]> entries, Element contentRoot) {
        Set<String> reachable = reachableParts(entries);
        Set<String> keep = new HashSet<>(reachable);
        keep.add("[Content_Types].xml");
        keep.add("_rels/.rels");
        for (String part : reachable) {
            String rels = TfXml.relsNameForPart(part);
            if (entries.containsKey(rels)) {
                keep.add(rels);
            }
        }
        entries.keySet().removeIf(name -> !keep.contains(name));
        for (Element override : new java.util.ArrayList<>(TfXml.descendants(contentRoot, "Override"))) {
            String partName = override.getAttribute("PartName").replaceFirst("^/+", "");
            if (!partName.isBlank() && !reachable.contains(partName)) {
                override.getParentNode().removeChild(override);
            }
        }
    }

    private static Set<String> reachableParts(Map<String, byte[]> entries) {
        Set<String> keep = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        enqueueRelTargets(entries, "_rels/.rels", "", queue);
        while (!queue.isEmpty()) {
            String part = queue.removeFirst();
            if (!keep.add(part)) {
                continue;
            }
            enqueueRelTargets(entries, TfXml.relsNameForPart(part), part, queue);
        }
        return keep;
    }

    private static void enqueueRelTargets(
            Map<String, byte[]> entries, String relsPart, String basePart, ArrayDeque<String> queue) {
        byte[] data = entries.get(relsPart);
        if (data == null) {
            return;
        }
        try {
            Document root = TfXml.parse(data);
            for (Element rel : TfXml.descendants(root.getDocumentElement(), "Relationship")) {
                if ("External".equals(rel.getAttribute("TargetMode"))) {
                    continue;
                }
                String target = rel.getAttribute("Target");
                if (target != null && !target.isBlank()) {
                    queue.add(TfXml.normalizePart(target, basePart.isBlank() ? "x" : basePart));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
