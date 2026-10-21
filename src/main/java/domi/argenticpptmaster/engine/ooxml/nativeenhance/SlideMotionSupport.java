// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Subset of {@code pptx_transitions.apply_slide_motion_xml} covering the service-allowed
 * transition effects (fade/push/wipe/split/cover/random/strips→wipe/none) plus advance timings.
 */
public final class SlideMotionSupport {

    public record EnterUpdate(String policy, String effect, double duration, Map<String, String> effectOptions) {
        public EnterUpdate {
            Objects.requireNonNull(policy, "policy");
            if (effectOptions == null) {
                effectOptions = Map.of();
            }
        }

        public static EnterUpdate preserve(double duration) {
            return new EnterUpdate("preserve", null, duration, Map.of());
        }

        public static EnterUpdate none(double duration) {
            return new EnterUpdate("none", null, duration, Map.of());
        }

        public static EnterUpdate replace(String effect, double duration) {
            return new EnterUpdate("replace", effect, duration, Map.of());
        }
    }

    public record AdvanceUpdate(String mode, Double after) {
        public AdvanceUpdate {
            Objects.requireNonNull(mode, "mode");
        }

        public static AdvanceUpdate preserve() {
            return new AdvanceUpdate("preserve", null);
        }

        public static AdvanceUpdate narration(double afterSeconds) {
            return new AdvanceUpdate("narration", afterSeconds);
        }
    }

    public record TransitionSummary(int logicalCount, Integer durationMs, Integer advanceAfterMs) {}

    public record MotionResult(String slideXml, boolean wroteAdvance) {}

    private SlideMotionSupport() {}

    public static double validateSeconds(Object value, String field, boolean allowZero) {
        if (value instanceof Boolean) {
            throw new IllegalArgumentException(field + " must be a finite number, not " + value);
        }
        double number;
        try {
            number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a finite number: " + value, ex);
        }
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(field + " must be finite: " + value);
        }
        if (allowZero) {
            if (number < 0) {
                throw new IllegalArgumentException(field + " must be non-negative: " + value);
            }
        } else if (number <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero: " + value);
        }
        return number;
    }

    public static String normalizeTransitionEffect(String effect, boolean allowNone) {
        if (effect == null || "none".equals(effect)) {
            if (allowNone) {
                return null;
            }
            throw new IllegalArgumentException("transition effect is required");
        }
        String canonical = NativeEnhanceConstants.TRANSITION_ALIASES.getOrDefault(effect, effect);
        if (!NativeEnhanceConstants.TRANSITION_EFFECTS.containsKey(canonical)) {
            throw new IllegalArgumentException("unknown transition effect '" + effect + "'");
        }
        return canonical;
    }

    public static TransitionSummary readSlideTransitionXml(String slideXml) {
        try {
            Document doc = parse(slideXml);
            Element root = doc.getDocumentElement();
            List<Element> carriers = transitionCarriers(root);
            if (carriers.isEmpty()) {
                return new TransitionSummary(0, null, null);
            }
            Element primary = primaryTransition(carriers.get(0));
            Integer durationMs = intAttr(primary, NativeEnhanceConstants.P14_NS, "dur");
            if (durationMs == null) {
                durationMs = intAttr(primary, null, "dur");
            }
            Integer advanceAfterMs = intAttr(primary, null, "advTm");
            return new TransitionSummary(carriers.size(), durationMs, advanceAfterMs);
        } catch (Exception ex) {
            return new TransitionSummary(0, null, null);
        }
    }

    public static MotionResult applySlideMotionXml(String slideXml, EnterUpdate enter, AdvanceUpdate advance)
            throws Exception {
        Document doc = parse(slideXml);
        Element root = doc.getDocumentElement();
        boolean wroteAdvance = applySlideMotion(root, enter, advance);
        String xml = serialize(doc, slideXml);
        return new MotionResult(xml, wroteAdvance);
    }

    public static void setDirectoryUseTimings(Path extractDir, boolean enabled) throws Exception {
        Path relsPath = extractDir.resolve("ppt/_rels/presentation.xml.rels");
        Path contentTypes = extractDir.resolve("[Content_Types].xml");
        if (!Files.isRegularFile(relsPath) || !Files.isRegularFile(contentTypes)) {
            throw new IOException("missing presentation relationships or content types");
        }
        String rels = Files.readString(relsPath, StandardCharsets.UTF_8);
        String propsPart = "ppt/presProps.xml";
        Matcher matcher = Pattern.compile(
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps\"[^>]*Target=\"([^\"]+)\"")
                .matcher(rels);
        if (matcher.find()) {
            String target = matcher.group(1);
            propsPart = target.startsWith("/")
                    ? target.replaceFirst("^/+", "")
                    : ("ppt/" + target.replaceFirst("^\\./", ""));
            if (!propsPart.startsWith("ppt/")) {
                propsPart = "ppt/" + Path.of(target).getFileName();
            }
            // Prefer resolving relative to ppt/
            if (target.contains("..")) {
                propsPart = Path.of("ppt").resolve(target).normalize().toString().replace('\\', '/');
            } else if (!target.startsWith("/") && !target.startsWith("ppt/")) {
                propsPart = "ppt/" + target.replaceFirst("^\\./", "");
            }
        } else {
            int next = maxRid(rels) + 1;
            String rel = "  <Relationship Id=\"rId"
                    + next
                    + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps\""
                    + " Target=\"presProps.xml\"/>";
            rels = rels.replace("</Relationships>", rel + "\n</Relationships>");
            Files.writeString(relsPath, rels, StandardCharsets.UTF_8);
        }

        Path propsPath = extractDir.resolve(propsPart);
        Document propsDoc;
        if (Files.isRegularFile(propsPath)) {
            propsDoc = parse(Files.readString(propsPath, StandardCharsets.UTF_8));
        } else {
            propsDoc = newDocument();
            Element presentationPr = propsDoc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:presentationPr");
            propsDoc.appendChild(presentationPr);
        }
        Element root = propsDoc.getDocumentElement();
        Element showPr = firstChild(root, OoxmlConstants.PRESENTATION_NS, "showPr");
        if (showPr == null) {
            showPr = propsDoc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:showPr");
            root.appendChild(showPr);
        }
        showPr.setAttribute("useTimings", enabled ? "1" : "0");
        Files.createDirectories(propsPath.getParent());
        Files.writeString(propsPath, serialize(propsDoc, null), StandardCharsets.UTF_8);

        String types = Files.readString(contentTypes, StandardCharsets.UTF_8);
        String override =
                "  <Override PartName=\"/"
                        + propsPart
                        + "\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>";
        if (!types.contains("PartName=\"/" + propsPart + "\"")) {
            types = types.replace("</Types>", override + "\n</Types>");
            Files.writeString(contentTypes, types, StandardCharsets.UTF_8);
        }
    }

    /** Apply enter/advance motion to an in-memory slide DOM root. */
    public static boolean applySlideMotion(Element slideRoot, EnterUpdate enter, AdvanceUpdate advance) {
        List<Element> carriers = transitionCarriers(slideRoot);
        if (carriers.size() > 1) {
            throw new IllegalArgumentException(
                    "slide contains multiple logical transition carriers; refusing to preserve or replace an ambiguous source");
        }
        Boolean preservedClick = null;
        Double preservedAfter = null;
        if (!carriers.isEmpty()) {
            Element primary = primaryTransition(carriers.get(0));
            if (primary != null) {
                if (primary.hasAttribute("advClick")) {
                    preservedClick = "1".equals(primary.getAttribute("advClick"));
                }
                Integer ms = intAttr(primary, null, "advTm");
                if (ms != null) {
                    preservedAfter = ms / 1000.0;
                }
            }
        }
        Boolean advanceOnClick;
        Double advanceAfter;
        switch (advance.mode()) {
            case "preserve" -> {
                advanceOnClick = preservedClick;
                advanceAfter = preservedAfter;
            }
            case "click" -> {
                advanceOnClick = true;
                advanceAfter = null;
            }
            case "narration", "after", "both" -> {
                if (advance.after() == null) {
                    throw new IllegalArgumentException("slide advance mode '" + advance.mode() + "' requires 'after'");
                }
                advanceAfter = validateSeconds(advance.after(), "slide advance after", true);
                advanceOnClick = "both".equals(advance.mode()) ? Boolean.TRUE : Boolean.FALSE;
            }
            default -> throw new IllegalArgumentException("unknown slide advance mode '" + advance.mode() + "'");
        }

        Document doc = slideRoot.getOwnerDocument();
        if ("preserve".equals(enter.policy())) {
            if ("preserve".equals(advance.mode())) {
                return carriers.stream().anyMatch(c -> primaryTransition(c) != null && primaryTransition(c).hasAttribute("advTm"));
            }
            if (!carriers.isEmpty()) {
                for (Element transition : transitionElements(carriers.get(0))) {
                    setAdvanceAttributes(transition, advanceOnClick, advanceAfter);
                }
                return advanceAfter != null;
            }
            Element transition = buildTransitionElement(
                    doc, null, enter.duration(), advanceAfter, advanceOnClick);
            if (transition != null) {
                insertTransitionCarrier(slideRoot, transition);
            }
            return advanceAfter != null;
        }

        String effect = null;
        if ("replace".equals(enter.policy())) {
            effect = normalizeTransitionEffect(enter.effect(), false);
        }
        double duration = validateSeconds(enter.duration(), "transition duration", false);
        if (!carriers.isEmpty()) {
            slideRoot.removeChild(carriers.get(0));
        }
        Element transition = buildTransitionElement(doc, effect, duration, advanceAfter, advanceOnClick);
        if (transition != null) {
            insertTransitionCarrier(slideRoot, transition);
        }
        return advanceAfter != null;
    }

    private static Element buildTransitionElement(
            Document doc, String effect, double duration, Double advanceAfter, Boolean advanceOnClick) {
        if (effect == null && advanceAfter == null && advanceOnClick == null) {
            return null;
        }
        Element transition = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:transition");
        if (effect != null) {
            long durationMs = Math.round(duration * 1000);
            transition.setAttributeNS(NativeEnhanceConstants.P14_NS, "p14:dur", Long.toString(durationMs));
            NativeEnhanceConstants.TransitionEffectSpec spec =
                    NativeEnhanceConstants.TRANSITION_EFFECTS.get(effect);
            Element child = doc.createElementNS(OoxmlConstants.PRESENTATION_NS, "p:" + spec.element());
            for (Map.Entry<String, String> attr : spec.attrs().entrySet()) {
                child.setAttribute(attr.getKey(), attr.getValue());
            }
            transition.appendChild(child);
        }
        setAdvanceAttributes(transition, advanceOnClick, advanceAfter);
        return transition;
    }

    private static void setAdvanceAttributes(Element transition, Boolean advanceOnClick, Double advanceAfter) {
        if (advanceOnClick == null) {
            transition.removeAttribute("advClick");
        } else {
            transition.setAttribute("advClick", advanceOnClick ? "1" : "0");
        }
        if (advanceAfter == null) {
            transition.removeAttribute("advTm");
        } else {
            long ms = Math.round(validateSeconds(advanceAfter, "slide advance after", true) * 1000);
            transition.setAttribute("advTm", Long.toString(ms));
        }
    }

    private static void insertTransitionCarrier(Element slideRoot, Element carrier) {
        NodeList children = slideRoot.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                String local = el.getLocalName();
                if ("timing".equals(local) || "extLst".equals(local)) {
                    slideRoot.insertBefore(carrier, el);
                    return;
                }
            }
        }
        Element clrMap = firstChild(slideRoot, OoxmlConstants.PRESENTATION_NS, "clrMapOvr");
        if (clrMap != null && clrMap.getNextSibling() != null) {
            slideRoot.insertBefore(carrier, clrMap.getNextSibling());
        } else if (clrMap != null) {
            slideRoot.appendChild(carrier);
        } else {
            slideRoot.appendChild(carrier);
        }
    }

    private static List<Element> transitionCarriers(Element slideRoot) {
        List<Element> carriers = new ArrayList<>();
        NodeList children = slideRoot.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el)) {
                continue;
            }
            if (OoxmlConstants.PRESENTATION_NS.equals(el.getNamespaceURI()) && "transition".equals(el.getLocalName())) {
                carriers.add(el);
            } else if (NativeEnhanceConstants.MC_NS.equals(el.getNamespaceURI())
                    && "AlternateContent".equals(el.getLocalName())
                    && !transitionElements(el).isEmpty()) {
                carriers.add(el);
            }
        }
        return carriers;
    }

    private static List<Element> transitionElements(Element carrier) {
        List<Element> found = new ArrayList<>();
        collectByLocalName(carrier, "transition", OoxmlConstants.PRESENTATION_NS, found);
        return found;
    }

    private static Element primaryTransition(Element carrier) {
        if (OoxmlConstants.PRESENTATION_NS.equals(carrier.getNamespaceURI())
                && "transition".equals(carrier.getLocalName())) {
            return carrier;
        }
        List<Element> transitions = transitionElements(carrier);
        return transitions.isEmpty() ? null : transitions.get(0);
    }

    private static void collectByLocalName(Element root, String local, String ns, List<Element> out) {
        if (ns.equals(root.getNamespaceURI()) && local.equals(root.getLocalName())) {
            out.add(root);
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                collectByLocalName(el, local, ns, out);
            }
        }
    }

    private static Element firstChild(Element parent, String ns, String local) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && ns.equals(el.getNamespaceURI()) && local.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static Integer intAttr(Element element, String ns, String name) {
        if (element == null) {
            return null;
        }
        String raw = ns == null ? element.getAttribute(name) : element.getAttributeNS(ns, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int maxRid(String relsContent) {
        Matcher matcher = Pattern.compile("Id=\"rId(\\d+)\"").matcher(relsContent);
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static Document newDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }

    static String serialize(Document doc, String sourceXml) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        String body = writer.toString();
        // Ensure p14 xmlns is declared on root when used.
        if (body.contains("p14:dur") && !body.contains("xmlns:p14=")) {
            body = body.replaceFirst(
                    "<p:sld\\b",
                    "<p:sld xmlns:p14=\"" + NativeEnhanceConstants.P14_NS + "\"");
            if (!body.contains("xmlns:p14=")) {
                body = body.replaceFirst(
                        "<p:transition\\b",
                        "<p:transition xmlns:p14=\"" + NativeEnhanceConstants.P14_NS + "\"");
            }
        }
        if (sourceXml != null && sourceXml.stripLeading().startsWith("<?xml") && !body.startsWith("<?xml")) {
            Matcher decl = Pattern.compile("^\\s*<\\?xml[^?]*\\?>").matcher(sourceXml);
            if (decl.find()) {
                body = decl.group() + "\n" + body;
            }
        }
        return body;
    }
}
