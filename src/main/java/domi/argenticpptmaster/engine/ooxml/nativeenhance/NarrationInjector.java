// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Port of {@code svg_to_pptx.pptx_package.narration.inject_narration}. */
public final class NarrationInjector {

    private NarrationInjector() {}

    public static double narrationLeadInSeconds(double transitionDuration, double startFloor) {
        double transitionSeconds =
                SlideMotionSupport.validateSeconds(transitionDuration, "narration transition duration", true);
        double floorSeconds = SlideMotionSupport.validateSeconds(startFloor, "narration start floor", true);
        return Math.max(0.0, floorSeconds - transitionSeconds);
    }

    public static int nextShapeId(String slideXml) throws Exception {
        Document doc = SlideMotionSupport.parse(slideXml);
        Element root = doc.getDocumentElement();
        if (!"sld".equals(root.getLocalName())) {
            throw new IllegalArgumentException("narration source XML root must be p:sld");
        }
        List<Integer> ids = numericIds(root, "cNvPr", OoxmlConstants.PRESENTATION_NS, 1);
        long next = ids.stream().mapToInt(Integer::intValue).max().orElse(1) + 1L;
        if (next > NativeEnhanceConstants.MAX_OOXML_UNSIGNED_INT) {
            throw new IllegalArgumentException("narration source has no available shape identifiers");
        }
        return (int) next;
    }

    public static String injectNarration(
            String slideXml,
            int shapeId,
            String shapeName,
            String audioRid,
            String mediaRid,
            String posterRid,
            double startDelay)
            throws Exception {
        if (shapeId <= 0) {
            throw new IllegalArgumentException("narration shape_id must be a positive integer");
        }
        double startDelaySeconds =
                SlideMotionSupport.validateSeconds(startDelay, "narration start delay", true);
        long startDelayMs = Math.round(startDelaySeconds * 1000);
        if (startDelayMs > NativeEnhanceConstants.MAX_OOXML_UNSIGNED_INT) {
            throw new IllegalArgumentException(
                    "narration start delay exceeds the OOXML unsigned-integer limit: " + startDelayMs + " ms");
        }

        Document doc = SlideMotionSupport.parse(slideXml);
        Element root = doc.getDocumentElement();
        if (!"sld".equals(root.getLocalName())) {
            throw new IllegalArgumentException("narration source XML root must be p:sld");
        }
        Element cSld = directChild(root, OoxmlConstants.PRESENTATION_NS, "cSld");
        Element spTree = directChild(cSld, OoxmlConstants.PRESENTATION_NS, "spTree");

        List<Integer> shapeIds = numericIds(root, "cNvPr", OoxmlConstants.PRESENTATION_NS, 1);
        if (shapeIds.contains(shapeId)) {
            throw new IllegalArgumentException("narration shape id already exists on slide: " + shapeId);
        }

        List<Element> timingBranches = animationTimingBranches(root);
        List<Integer> timingIds = new ArrayList<>();
        for (Element timing : timingBranches) {
            timingIds.addAll(numericIds(timing, "cTn", OoxmlConstants.PRESENTATION_NS, 0));
        }
        long nextTimingId = timingIds.stream().mapToInt(Integer::intValue).max().orElse(0) + 1L;
        if (nextTimingId > NativeEnhanceConstants.MAX_OOXML_UNSIGNED_INT) {
            throw new IllegalArgumentException("narration source has no available timing node identifiers");
        }

        spTree.appendChild(createAudioPic(doc, shapeId, shapeName, audioRid, mediaRid, posterRid));

        if (!timingBranches.isEmpty()) {
            for (Element timing : timingBranches) {
                Element timingRoot = existingTimingRoot(timing);
                Element childNodes = directChild(timingRoot, OoxmlConstants.PRESENTATION_NS, "childTnLst");
                childNodes.appendChild(
                        createAudioTiming(doc, shapeId, (int) nextTimingId, (int) startDelayMs));
            }
        } else {
            Element audioTiming = createAudioTiming(doc, shapeId, (int) nextTimingId + 1, (int) startDelayMs);
            insertRootTiming(root, newTiming(doc, audioTiming, (int) nextTimingId));
        }
        return SlideMotionSupport.serialize(doc, slideXml);
    }

    /** Lightweight fingerprint: presence of non-audio object-animation behaviors. */
    public static String objectAnimationFingerprint(String slideXml) throws Exception {
        Document doc = SlideMotionSupport.parse(slideXml);
        Element root = doc.getDocumentElement();
        Element timing = firstDirectChild(root, OoxmlConstants.PRESENTATION_NS, "timing");
        if (timing == null) {
            return null;
        }
        Set<String> behaviorTags = Set.of("anim", "animClr", "animEffect", "animMotion", "animRot", "animScale", "cmd", "set");
        StringBuilder fingerprint = new StringBuilder();
        collectBehaviorFingerprint(timing, behaviorTags, fingerprint);
        return fingerprint.isEmpty() ? null : fingerprint.toString();
    }

    private static void collectBehaviorFingerprint(Element node, Set<String> behaviorTags, StringBuilder out) {
        if (OoxmlConstants.PRESENTATION_NS.equals(node.getNamespaceURI()) && "audio".equals(node.getLocalName())) {
            return;
        }
        if (OoxmlConstants.PRESENTATION_NS.equals(node.getNamespaceURI()) && behaviorTags.contains(node.getLocalName())) {
            out.append(node.getLocalName()).append(';');
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                collectBehaviorFingerprint(el, behaviorTags, out);
            }
        }
    }

    private static Element createAudioPic(
            Document doc, int shapeId, String shapeName, String audioRid, String mediaRid, String posterRid) {
        Element pic = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:pic");
        Element nvPicPr = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:nvPicPr");
        Element cNvPr = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cNvPr");
        cNvPr.setAttribute("id", Integer.toString(shapeId));
        cNvPr.setAttribute("name", shapeName);
        Element hlink = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:hlinkClick");
        hlink.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:id", "");
        hlink.setAttribute("action", "ppaction://media");
        cNvPr.appendChild(hlink);
        nvPicPr.appendChild(cNvPr);

        Element cNvPicPr = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cNvPicPr");
        Element picLocks = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:picLocks");
        picLocks.setAttribute("noChangeAspect", "1");
        cNvPicPr.appendChild(picLocks);
        nvPicPr.appendChild(cNvPicPr);

        Element nvPr = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:nvPr");
        Element audioFile = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:audioFile");
        audioFile.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:link", audioRid);
        nvPr.appendChild(audioFile);
        Element extLst = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:extLst");
        Element ext = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:ext");
        ext.setAttribute("uri", "{DAA4B4D4-6D71-4841-9C94-3DE7FCFB9230}");
        Element media = doc.createElementNS(NativeEnhanceConstants.P14_NS, "p14:media");
        media.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:embed", mediaRid);
        ext.appendChild(media);
        extLst.appendChild(ext);
        nvPr.appendChild(extLst);
        nvPicPr.appendChild(nvPr);
        pic.appendChild(nvPicPr);

        Element blipFill = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:blipFill");
        Element blip = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:blip");
        blip.setAttributeNS(OoxmlConstants.OFFICE_REL_NS, "r:embed", posterRid);
        blipFill.appendChild(blip);
        Element stretch = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:stretch");
        stretch.appendChild(doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:fillRect"));
        blipFill.appendChild(stretch);
        pic.appendChild(blipFill);

        Element spPr = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:spPr");
        Element xfrm = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:xfrm");
        Element off = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:off");
        off.setAttribute("x", Long.toString(NativeEnhanceConstants.AUDIO_MARKER_OFF_CANVAS_EMU));
        off.setAttribute("y", Long.toString(NativeEnhanceConstants.AUDIO_MARKER_OFF_CANVAS_EMU));
        Element extSize = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:ext");
        extSize.setAttribute("cx", Long.toString(NativeEnhanceConstants.AUDIO_MARKER_SIZE_EMU));
        extSize.setAttribute("cy", Long.toString(NativeEnhanceConstants.AUDIO_MARKER_SIZE_EMU));
        xfrm.appendChild(off);
        xfrm.appendChild(extSize);
        spPr.appendChild(xfrm);
        Element prstGeom = doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:prstGeom");
        prstGeom.setAttribute("prst", "rect");
        prstGeom.appendChild(doc.createElementNS(OoxmlConstants.DRAWING_NS, "a:avLst"));
        spPr.appendChild(prstGeom);
        pic.appendChild(spPr);
        return pic;
    }

    private static Element createAudioTiming(Document doc, int shapeId, int ctnId, int startDelayMs) {
        Element audio = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:audio");
        Element mediaNode = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cMediaNode");
        mediaNode.setAttribute("vol", "80000");
        Element cTn = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cTn");
        cTn.setAttribute("id", Integer.toString(ctnId));
        cTn.setAttribute("fill", "hold");
        cTn.setAttribute("display", "0");
        Element stCondLst = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:stCondLst");
        Element cond = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cond");
        cond.setAttribute("delay", Integer.toString(startDelayMs));
        stCondLst.appendChild(cond);
        cTn.appendChild(stCondLst);
        mediaNode.appendChild(cTn);
        Element tgtEl = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:tgtEl");
        Element spTgt = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:spTgt");
        spTgt.setAttribute("spid", Integer.toString(shapeId));
        tgtEl.appendChild(spTgt);
        mediaNode.appendChild(tgtEl);
        audio.appendChild(mediaNode);
        return audio;
    }

    private static Element newTiming(Document doc, Element audioTiming, int rootId) {
        Element timing = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:timing");
        Element tnLst = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:tnLst");
        Element par = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:par");
        Element cTn = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:cTn");
        cTn.setAttribute("id", Integer.toString(rootId));
        cTn.setAttribute("dur", "indefinite");
        cTn.setAttribute("restart", "never");
        cTn.setAttribute("nodeType", "tmRoot");
        Element childTnLst = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:childTnLst");
        childTnLst.appendChild(audioTiming);
        cTn.appendChild(childTnLst);
        par.appendChild(cTn);
        tnLst.appendChild(par);
        timing.appendChild(tnLst);
        return timing;
    }

    private static void insertRootTiming(Element slide, Element timing) {
        Element extLst = firstDirectChild(slide, OoxmlConstants.PRESENTATION_NS, "extLst");
        if (extLst != null) {
            slide.insertBefore(timing, extLst);
        } else {
            slide.appendChild(timing);
        }
    }

    private static Element existingTimingRoot(Element timing) {
        Element tnLst = directChild(timing, OoxmlConstants.PRESENTATION_NS, "tnLst");
        List<Element> roots = new ArrayList<>();
        collectTmRoots(tnLst, roots);
        if (roots.size() != 1) {
            throw new IllegalArgumentException(
                    "narration source timing must contain exactly one tmRoot; found " + roots.size());
        }
        return roots.get(0);
    }

    private static void collectTmRoots(Element node, List<Element> roots) {
        if (OoxmlConstants.PRESENTATION_NS.equals(node.getNamespaceURI())
                && "cTn".equals(node.getLocalName())
                && "tmRoot".equals(node.getAttribute("nodeType"))) {
            roots.add(node);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                collectTmRoots(el, roots);
            }
        }
    }

    private static List<Element> animationTimingBranches(Element slide) {
        List<Element> direct = new ArrayList<>();
        NodeList children = slide.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el
                    && OoxmlConstants.PRESENTATION_NS.equals(el.getNamespaceURI())
                    && "timing".equals(el.getLocalName())) {
                direct.add(el);
            }
        }
        return direct;
    }

    private static List<Integer> numericIds(Element root, String local, String ns, int minimum) {
        List<Integer> ids = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        collectIds(root, local, ns, minimum, ids, seen);
        return ids;
    }

    private static void collectIds(
            Element node, String local, String ns, int minimum, List<Integer> ids, Set<Integer> seen) {
        if (ns.equals(node.getNamespaceURI()) && local.equals(node.getLocalName())) {
            String raw = node.getAttribute("id");
            int numericId = Integer.parseInt(raw);
            if (numericId < minimum) {
                throw new IllegalArgumentException("narration source has id below " + minimum + ": " + numericId);
            }
            if (!seen.add(numericId)) {
                throw new IllegalArgumentException("narration source has duplicate id: " + numericId);
            }
            ids.add(numericId);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                collectIds(el, local, ns, minimum, ids, seen);
            }
        }
    }

    private static Element directChild(Element parent, String ns, String local) {
        List<Element> matches = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && ns.equals(el.getNamespaceURI()) && local.equals(el.getLocalName())) {
                matches.add(el);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "narration source must contain exactly one direct " + local + "; found " + matches.size());
        }
        return matches.get(0);
    }

    private static Element firstDirectChild(Element parent, String ns, String local) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && ns.equals(el.getNamespaceURI()) && local.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }
}
