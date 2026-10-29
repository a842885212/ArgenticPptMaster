// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Clip-path and mask contracts from {@code check_clip_path_contract} / {@code check_mask_contract}
 * (fail-closed subset).
 */
final class ClipMaskContractQualitySupport {

    private static final Pattern URL_REF = Pattern.compile("^url\\(#([^)]+)\\)$");
    private static final Set<String> CLIP_SHAPE_TAGS =
            Set.of("circle", "ellipse", "rect", "path", "polygon");

    private ClipMaskContractQualitySupport() {}

    static List<String> checkClipAndMaskContracts(Document document) {
        Set<String> errors = new LinkedHashSet<>();
        errors.addAll(checkMaskContract(document));
        errors.addAll(checkClipPathContract(document));
        return new ArrayList<>(errors);
    }

    private static List<String> checkMaskContract(Document document) {
        List<String> errors = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            if ("mask".equals(tag)) {
                errors.add(
                        label + " is an unsupported SVG mask definition; replace it "
                                + "with editable overlay or Boolean/cutout shapes, an image "
                                + "clip-path, or pre-rendered alpha imagery");
            }
            List<String> sources = new ArrayList<>();
            NamedNodeMap attrs = element.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                String name = attrs.item(j).getNodeName();
                int brace = name.lastIndexOf('}');
                String local = brace >= 0 ? name.substring(brace + 1) : name;
                if ("mask".equalsIgnoreCase(local)) {
                    sources.add("mask attribute");
                    break;
                }
            }
            Map<String, String> style = SvgQualityXml.parseInlineStyle(element.getAttribute("style"));
            if (style.containsKey("mask")) {
                sources.add("inline style mask property");
            }
            if (!sources.isEmpty()) {
                errors.add(
                        label + " uses unsupported SVG mask presentation via "
                                + String.join(", ", sources)
                                + "; native PPTX export would drop the effect. Use editable overlay "
                                + "or Boolean/cutout shapes, an image clip-path, or pre-rendered alpha imagery");
            }
        }
        return errors;
    }

    private static List<String> checkClipPathContract(Document document) {
        List<String> errors = new ArrayList<>();
        Map<String, Element> definitions = definitionIndex(document);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            if (!element.hasAttribute("clip-path")) {
                continue;
            }
            String rawRef = element.getAttribute("clip-path");
            if (rawRef == null || rawRef.strip().equalsIgnoreCase("none")) {
                continue;
            }
            String label = SvgQualityXml.elementLabel(element);
            String tag = SvgQualityXml.localName(element).toLowerCase(Locale.ROOT);
            boolean isImage = "image".equals(tag);
            boolean isImportedCrop =
                    "svg".equals(tag) && "1".equals(element.getAttribute("data-pptx-crop"));
            if (!isImage && !isImportedCrop) {
                errors.add(
                        label + " clip-path is allowed only on <image> or an imported "
                                + "data-pptx-crop=\"1\" wrapper");
            }
            Matcher match = URL_REF.matcher(rawRef.strip());
            if (!match.matches()) {
                errors.add(
                        label + " clip-path must be an exact local url(#id) reference; got '"
                                + rawRef + "'");
                continue;
            }
            String clipId = match.group(1);
            Element clip = definitions.get(clipId);
            if (clip == null || !"clippath".equals(SvgQualityXml.localName(clip).toLowerCase(Locale.ROOT))) {
                errors.add(
                        label + " clip-path=url(#" + clipId + ") has no matching direct "
                                + "<defs><clipPath id=\"" + clipId + "\"> definition");
                continue;
            }
            String clipLabel = "<clipPath id=\"" + clipId + "\">";
            List<Element> visualChildren = new ArrayList<>();
            NodeList children = clip.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                Node node = children.item(c);
                if (node instanceof Element child) {
                    String childTag = SvgQualityXml.localName(child).toLowerCase(Locale.ROOT);
                    if (!Set.of("title", "desc", "metadata").contains(childTag)) {
                        visualChildren.add(child);
                    }
                }
            }
            if (visualChildren.size() != 1) {
                errors.add(clipLabel + " must contain exactly one direct supported shape");
                continue;
            }
            String shapeTag = SvgQualityXml.localName(visualChildren.get(0)).toLowerCase(Locale.ROOT);
            if (!CLIP_SHAPE_TAGS.contains(shapeTag)) {
                errors.add(
                        clipLabel + " child <" + shapeTag + "> is unsupported; use "
                                + "circle, ellipse, rect, path, or polygon");
            }
        }
        return errors;
    }

    private static Map<String, Element> definitionIndex(Document document) {
        Map<String, Element> definitions = new HashMap<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element defs)) {
                continue;
            }
            if (!"defs".equals(SvgQualityXml.localName(defs).toLowerCase(Locale.ROOT))) {
                continue;
            }
            NodeList children = defs.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node node = children.item(j);
                if (!(node instanceof Element child)) {
                    continue;
                }
                String id = child.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    definitions.put(id.strip(), child);
                }
            }
        }
        return definitions;
    }
}
