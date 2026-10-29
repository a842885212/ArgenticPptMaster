// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import java.util.ArrayList;
import java.util.Base64;
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
 * Phase-3 preset geometry metadata / transform checks.
 *
 * <p>Fail-closed only when related markers are present (confirmed boundary). Does not scan
 * unmarked trees. Authored-preset tree / preview-fingerprint deep compare remain residual when
 * those advanced contracts appear without invalid structural metadata.</p>
 */
final class PresetGeometryQualitySupport {

    static final String AUTHORING_ATTR = "data-pptx-authoring";
    static final String AUTHORING_VALUE = "preset";
    private static final String AV_PREFIX = "data-pptx-av-";
    private static final Pattern GUIDE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}");
    private static final Pattern VAL_FORMULA = Pattern.compile("val[\\t ]+([+-]?\\d+)");
    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SPLIT = Pattern.compile("[\\s,]+");

    private PresetGeometryQualitySupport() {}

    static List<String> checkPresetGeometry(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        List<Element> marked = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element elem && hasPresetMarker(elem)) {
                marked.add(elem);
            }
        }
        if (marked.isEmpty()) {
            return List.of();
        }

        Set<String> issues = new LinkedHashSet<>();
        for (Element elem : marked) {
            String label = SvgQualityXml.elementLabel(elem);
            for (String error : validatePresetGeometryMetadata(elem)) {
                issues.add(label + " has invalid PPTX shape metadata: " + error);
            }
        }
        issues.addAll(checkPresetGeometryTransforms(root));
        return new ArrayList<>(issues);
    }

    private static boolean hasPresetMarker(Element elem) {
        if (elem.hasAttribute("data-pptx-prst")
                || elem.hasAttribute("data-pptx-frame")
                || elem.hasAttribute("data-pptx-geometry-status")
                || elem.hasAttribute("data-pptx-geometry-reason")
                || elem.hasAttribute("data-pptx-geometry-kind")
                || elem.hasAttribute("data-pptx-custgeom")
                || elem.hasAttribute("data-pptx-preview-sha256")
                || elem.hasAttribute("data-pptx-shape-id")
                || elem.hasAttribute("data-pptx-shape-scope")
                || elem.hasAttribute("data-pptx-shape-style")
                || elem.hasAttribute(AUTHORING_ATTR)) {
            return true;
        }
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            if (attrs.item(i).getNodeName().startsWith(AV_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    static List<String> validatePresetGeometryMetadata(Element elem) {
        List<String> errors = new ArrayList<>();
        try {
            parsePresetGeometryMetadata(elem);
        } catch (IllegalArgumentException ex) {
            errors.add(ex.getMessage());
        }
        if (elem.hasAttribute("data-pptx-custgeom")) {
            try {
                validateCustomGeom(elem);
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        if (elem.hasAttribute("data-pptx-shape-style")) {
            try {
                decodeShapeStyle(elem);
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        if (elem.hasAttribute("data-pptx-shape-id")) {
            String raw = elem.getAttribute("data-pptx-shape-id");
            try {
                long shapeId = Long.parseLong(raw);
                if (shapeId < 2 || shapeId > 0xFFFF_FFFFL) {
                    errors.add("data-pptx-shape-id must be between 2 and 4294967295");
                }
            } catch (NumberFormatException ex) {
                errors.add("Invalid data-pptx-shape-id '" + raw + "'");
            }
        }
        if (elem.hasAttribute("data-pptx-shape-scope")) {
            String scope = elem.getAttribute("data-pptx-shape-scope");
            if (!SCOPE.matcher(scope).matches()) {
                errors.add("Invalid data-pptx-shape-scope '" + scope + "'");
            }
        }
        for (String endpoint : List.of("start", "end")) {
            String targetAttr = "data-pptx-" + endpoint + "-shape-id";
            String siteAttr = "data-pptx-" + endpoint + "-site";
            boolean hasTarget = elem.hasAttribute(targetAttr);
            boolean hasSite = elem.hasAttribute(siteAttr);
            if (hasTarget != hasSite) {
                errors.add("Connector " + endpoint + " endpoint requires both shape-id and site");
            }
            if (hasTarget) {
                try {
                    long targetId = Long.parseLong(elem.getAttribute(targetAttr));
                    long siteId = Long.parseLong(elem.getAttribute(siteAttr));
                    if (targetId < 2 || targetId > 0xFFFF_FFFFL) {
                        errors.add("Connector " + endpoint + " shape-id is out of range");
                    }
                    if (siteId < 0 || siteId > 0xFFFF_FFFFL) {
                        errors.add("Connector " + endpoint + " site is out of range");
                    }
                } catch (NumberFormatException ex) {
                    errors.add("Invalid connector " + endpoint + " endpoint metadata");
                }
            }
        }
        return errors;
    }

    private static void parsePresetGeometryMetadata(Element elem) {
        String status = attr(elem, "data-pptx-geometry-status").strip();
        String authoring = elem.hasAttribute(AUTHORING_ATTR) ? elem.getAttribute(AUTHORING_ATTR) : null;
        if (authoring != null && !AUTHORING_VALUE.equals(authoring)) {
            throw new IllegalArgumentException(
                    "Unsupported " + AUTHORING_ATTR + " value '" + authoring + "'");
        }
        if (AUTHORING_VALUE.equals(authoring)) {
            String objectKind = attr(elem, "data-pptx-object");
            if (!"shape".equals(objectKind) && !"connector".equals(objectKind)) {
                throw new IllegalArgumentException(
                        "Authored preset metadata requires data-pptx-object=\"shape\" or \"connector\"");
            }
            if (!elem.hasAttribute("data-pptx-prst")) {
                throw new IllegalArgumentException("Authored preset metadata requires data-pptx-prst");
            }
            String preset = elem.getAttribute("data-pptx-prst");
            if (SvgQualityPresetCatalog.isConnectorPreset(preset) && !"connector".equals(objectKind)) {
                throw new IllegalArgumentException(
                        "Connector preset '" + preset + "' requires data-pptx-object=\"connector\"");
            }
            if ("connector".equals(objectKind) && !SvgQualityPresetCatalog.isConnectorPreset(preset)) {
                throw new IllegalArgumentException(
                        "Authored connector requires a connector preset, got '" + preset + "'");
            }
            if (!elem.hasAttribute("data-pptx-frame")) {
                throw new IllegalArgumentException("Authored preset metadata requires data-pptx-frame");
            }
        }
        if (!status.isEmpty() && !"exact".equals(status) && !"unsupported".equals(status)) {
            throw new IllegalArgumentException(
                    "Unsupported data-pptx-geometry-status '" + status + "'; expected exact or unsupported");
        }
        if (elem.hasAttribute("data-pptx-geometry-reason") && !"unsupported".equals(status)) {
            throw new IllegalArgumentException(
                    "data-pptx-geometry-reason requires data-pptx-geometry-status=\"unsupported\"");
        }
        if ("unsupported".equals(status)) {
            String reason = elem.hasAttribute("data-pptx-geometry-reason")
                    ? elem.getAttribute("data-pptx-geometry-reason").strip()
                    : "unspecified";
            if (reason.isEmpty()) {
                reason = "unspecified";
            }
            throw new IllegalArgumentException("Unsupported source PPTX geometry: " + reason);
        }

        String prst = elem.hasAttribute("data-pptx-prst") ? elem.getAttribute("data-pptx-prst") : null;
        Set<String> allowedGuides = Set.of();
        if (prst != null) {
            if (!prst.equals(prst.strip()) || !SvgQualityPresetCatalog.isKnownPreset(prst)) {
                throw new IllegalArgumentException("Unknown or invalid data-pptx-prst '" + prst + "'");
            }
            allowedGuides = SvgQualityPresetCatalog.guideNames(prst);
        }

        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            String attrName = attrs.item(i).getNodeName();
            if (!attrName.startsWith(AV_PREFIX)) {
                continue;
            }
            if (prst == null) {
                throw new IllegalArgumentException(attrName + " requires data-pptx-prst");
            }
            String guideName = attrName.substring(AV_PREFIX.length());
            if (!GUIDE_NAME.matcher(guideName).matches()) {
                throw new IllegalArgumentException("Invalid preset adjustment guide name '" + guideName + "'");
            }
            if (!allowedGuides.contains(guideName)) {
                throw new IllegalArgumentException(
                        "Preset '" + prst + "' has no adjustment guide named '" + guideName + "'");
            }
            String formula = attrs.item(i).getNodeValue().strip();
            if (formula.isEmpty()) {
                throw new IllegalArgumentException(attrName + " must not be empty");
            }
            Matcher match = VAL_FORMULA.matcher(formula);
            if (match.matches()) {
                long value = Long.parseLong(match.group(1));
                if (value < SvgQualityAffine.OOXML_COORDINATE_MIN
                        || value > SvgQualityAffine.OOXML_COORDINATE_MAX) {
                    throw new IllegalArgumentException(
                            attrName + " value " + value + " is outside OOXML coordinate range");
                }
            }
            // Non-val formulas accepted structurally; full registry evaluate remains residual.
        }

        if (elem.hasAttribute("data-pptx-frame")) {
            String rawFrame = elem.getAttribute("data-pptx-frame");
            String[] parts = SPLIT.split(rawFrame.strip());
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "data-pptx-frame must contain exactly four numbers: x y width height");
            }
            double[] frame = new double[4];
            try {
                for (int i = 0; i < 4; i++) {
                    frame[i] = Double.parseDouble(parts[i]);
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid data-pptx-frame '" + rawFrame + "'", ex);
            }
            for (double value : frame) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "data-pptx-frame must contain finite numbers, got '" + rawFrame + "'");
                }
            }
            boolean connector = "connector".equals(attr(elem, "data-pptx-object"))
                    || SvgQualityPresetCatalog.isConnectorPreset(prst);
            if (connector) {
                if (frame[2] < 0 || frame[3] < 0 || (frame[2] == 0 && frame[3] == 0)) {
                    throw new IllegalArgumentException(
                            "Connector data-pptx-frame dimensions must be non-negative and not both zero, got '"
                                    + rawFrame + "'");
                }
            } else if (frame[2] <= 0 || frame[3] <= 0) {
                throw new IllegalArgumentException(
                        "data-pptx-frame width and height must be positive, got '" + rawFrame + "'");
            }
            SvgQualityAffine.validateOoxmlXfrm(
                    Emu.pxToEmu(frame[0]),
                    Emu.pxToEmu(frame[1]),
                    Emu.pxToEmu(frame[2]),
                    Emu.pxToEmu(frame[3]));
        }
    }

    private static void validateCustomGeom(Element elem) {
        String kind = attr(elem, "data-pptx-geometry-kind");
        if (kind.isEmpty()) {
            return;
        }
        if (!"custom".equals(kind)) {
            throw new IllegalArgumentException("Unsupported data-pptx-geometry-kind '" + kind + "'");
        }
        String encoded = attr(elem, "data-pptx-custgeom");
        String expectedHash = attr(elem, "data-pptx-geometry-sha256");
        if (encoded.isEmpty() || expectedHash.isEmpty()) {
            throw new IllegalArgumentException(
                    "Custom geometry metadata requires data-pptx-custgeom and data-pptx-geometry-sha256");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.strip());
            String xml = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (!xml.contains("custGeom")) {
                throw new IllegalArgumentException("Custom geometry metadata payload must be a:custGeom");
            }
            if (xml.contains("http://schemas.openxmlformats.org/officeDocument/2006/relationships")) {
                throw new IllegalArgumentException(
                        "Custom geometry metadata must not contain relationship attributes");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid custom geometry metadata: " + ex.getMessage(), ex);
        }
    }

    private static void decodeShapeStyle(Element elem) {
        String encoded = elem.getAttribute("data-pptx-shape-style").strip();
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException("data-pptx-shape-style must not be empty");
        }
        try {
            Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid data-pptx-shape-style payload", ex);
        }
    }

    private static List<String> checkPresetGeometryTransforms(Element root) {
        Set<Element> relevant = new LinkedHashSet<>();
        markRelevant(root, relevant);
        Set<String> issues = new LinkedHashSet<>();
        visit(root, SvgQualityAffine.Matrix.IDENTITY, relevant, issues);
        return new ArrayList<>(issues);
    }

    private static boolean markRelevant(Element element, Set<Element> relevant) {
        boolean found = element.hasAttribute("data-pptx-prst");
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                found = markRelevant(child, relevant) || found;
            }
        }
        if (found) {
            relevant.add(element);
        }
        return found;
    }

    private static void visit(
            Element element,
            SvgQualityAffine.Matrix parentMatrix,
            Set<Element> relevant,
            Set<String> issues) {
        if (!relevant.contains(element)) {
            return;
        }
        SvgQualityAffine.Matrix matrix = parentMatrix;
        if (element.hasAttribute("transform")) {
            try {
                matrix = SvgQualityAffine.multiply(
                        parentMatrix, SvgQualityAffine.parseTransformMatrix(element.getAttribute("transform")));
            } catch (IllegalArgumentException ex) {
                issues.add("<" + SvgQualityXml.localName(element) + "> has invalid preset transform: "
                        + ex.getMessage());
                return;
            }
        }
        if (element.hasAttribute("data-pptx-prst")) {
            try {
                String rawFrame = attr(element, "data-pptx-frame");
                if (!rawFrame.isEmpty()) {
                    String[] parts = SPLIT.split(rawFrame.strip());
                    if (parts.length != 4) {
                        throw new IllegalArgumentException("data-pptx-frame must contain four numbers");
                    }
                    double[] frame = new double[4];
                    for (int i = 0; i < 4; i++) {
                        frame[i] = Double.parseDouble(parts[i]);
                    }
                    String preset = attr(element, "data-pptx-prst");
                    boolean preserve = "connector".equals(attr(element, "data-pptx-object"))
                            || SvgQualityPresetCatalog.isConnectorPreset(preset);
                    SvgQualityAffine.validateRectToDmlXfrm(
                            frame[0], frame[1], frame[2], frame[3], matrix, preserve);
                } else {
                    SvgQualityAffine.validateDmlShapeMatrix(matrix);
                }
            } catch (RuntimeException ex) {
                String elemId = attr(element, "id");
                if (elemId.isEmpty()) {
                    elemId = "(no id)";
                }
                issues.add("<" + SvgQualityXml.localName(element) + " id=\"" + elemId
                        + "\"> has unsupported preset transform: " + ex.getMessage());
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child) {
                visit(child, matrix, relevant, issues);
            }
        }
    }

    private static String attr(Element elem, String name) {
        return elem.hasAttribute(name) ? elem.getAttribute(name) : "";
    }
}
