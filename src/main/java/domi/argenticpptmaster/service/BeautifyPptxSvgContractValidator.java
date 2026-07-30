package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** 校验 beautify 的逐页 SVG 文本、页映射与源图绑定契约。 */
public final class BeautifyPptxSvgContractValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SLIDE_FILE = Pattern.compile(
            "(?:slide[-_]?)?0*(\\d+)(?:[_-].*)?\\.svg", Pattern.CASE_INSENSITIVE);

    private BeautifyPptxSvgContractValidator() {
    }

    public static void validate(Path projectPath) {
        try {
            JsonNode inventory = OBJECT_MAPPER.readTree(projectPath.resolve("analysis/beautify_inventory.json").toFile());
            JsonNode slides = inventory.path("slides");
            int slideCount = inventory.path("slide_count").asInt(0);
            if (slideCount <= 0 || !slides.isArray() || slides.size() != slideCount) {
                throw violation("invalid inventory page contract");
            }
            Map<Integer, Path> svgBySlide = svgBySlide(projectPath.resolve("svg_output"));
            if (svgBySlide.size() != slideCount) {
                throw violation("SVG page count does not match inventory");
            }
            for (int index = 0; index < slideCount; index++) {
                int page = index + 1;
                JsonNode slide = slides.get(index);
                if (slide.path("slide_index").asInt(-1) != page || !svgBySlide.containsKey(page)) {
                    throw violation("SVG page numbering is not continuous at page " + page);
                }
                String svg = Files.readString(svgBySlide.get(page));
                JsonNode textBlocks = slide.path("text_blocks");
                if (!textBlocks.isArray()) {
                    throw violation("inventory text ledger is missing on page " + page);
                }
                Map<String, Integer> expectedTexts = new HashMap<>();
                for (JsonNode text : textBlocks) {
                    JsonNode value = text.isObject() ? text.path("text") : text;
                    if (!value.isTextual()) {
                        throw violation("text fidelity mismatch on page " + page);
                    }
                    expectedTexts.merge(value.asText(), 1, Integer::sum);
                }
                collectFrozenDataTexts(slide, expectedTexts);
                Map<String, Integer> actualTexts = svgTextCounts(svg);
                for (Map.Entry<String, Integer> expected : expectedTexts.entrySet()) {
                    int actual = actualTexts.getOrDefault(expected.getKey(), 0);
                    if (actual < expected.getValue()) {
                        throw violation("text fidelity mismatch on page " + page);
                    }
                }
                validateBoundImages(projectPath, slide, svg, page);
            }
        } catch (IOException ex) {
            throw violation("unable to read SVG contract artifacts");
        }
    }

    private static void collectFrozenDataTexts(JsonNode slide, Map<String, Integer> expectedTexts) {
        for (JsonNode table : iterable(slide.path("tables"))) {
            JsonNode rows = table.path("rows");
            if (!rows.isArray()) {
                continue;
            }
            for (JsonNode row : rows) {
                if (!row.isArray()) {
                    continue;
                }
                for (JsonNode cell : row) {
                    String value = cell.isTextual() ? cell.asText() : cell.path("text").asText("");
                    if (!value.isBlank()) {
                        expectedTexts.merge(value, 1, Integer::sum);
                    }
                }
            }
        }
        for (JsonNode chart : iterable(slide.path("charts"))) {
            for (JsonNode category : iterable(chart.path("categories"))) {
                if (category.isTextual() && !category.asText().isBlank()) {
                    expectedTexts.merge(category.asText(), 1, Integer::sum);
                }
            }
            for (JsonNode series : iterable(chart.path("series"))) {
                String name = series.path("name").asText("");
                if (!name.isBlank()) {
                    expectedTexts.merge(name, 1, Integer::sum);
                }
                for (JsonNode value : iterable(series.path("values"))) {
                    if (value.isNumber() || value.isTextual()) {
                        expectedTexts.merge(value.asText(), 1, Integer::sum);
                    }
                }
            }
        }
    }

    private static void validateBoundImages(Path projectPath, JsonNode slide, String svg, int page) {
        Set<String> allowed = new HashSet<>();
        for (JsonNode image : iterable(slide.path("images"))) {
            String filename = image.path("filename").asText(image.path("path").asText(""));
            if (!filename.isBlank()) {
                allowed.add(normalizeImageRef(filename));
            }
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            if (hasElement(document, "foreignObject")) {
                throw violation("non-text SVG element is forbidden");
            }
            NodeList images = elements(document, "image");
            if (images.getLength() > 0 && allowed.isEmpty()) {
                throw violation("non-text SVG element is forbidden");
            }
            for (int index = 0; index < images.getLength(); index++) {
                Element element = (Element) images.item(index);
                String href = firstHref(element);
                if (href == null || href.isBlank() || href.startsWith("data:") || href.contains("://")) {
                    throw violation("substituted generated image on page " + page);
                }
                String normalized = normalizeImageRef(href);
                if (!allowed.contains(normalized) && !allowed.contains(stripImagesPrefix(normalized))) {
                    throw violation("substituted generated image on page " + page);
                }
                Path resolved = projectPath.resolve(normalized).normalize();
                if (!resolved.startsWith(projectPath.normalize()) || !Files.isRegularFile(resolved)) {
                    Path alt = projectPath.resolve("images").resolve(stripImagesPrefix(normalized)).normalize();
                    if (!alt.startsWith(projectPath.normalize()) || !Files.isRegularFile(alt)) {
                        throw violation("substituted generated image on page " + page);
                    }
                }
            }
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw violation("invalid SVG XML");
        }
    }

    private static String firstHref(Element element) {
        if (element.hasAttribute("href")) {
            return element.getAttribute("href");
        }
        if (element.hasAttributeNS("http://www.w3.org/1999/xlink", "href")) {
            return element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        }
        return element.getAttribute("xlink:href");
    }

    private static String normalizeImageRef(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String stripImagesPrefix(String value) {
        String normalized = normalizeImageRef(value);
        if (normalized.toLowerCase(Locale.ROOT).startsWith("images/")) {
            return normalized.substring("images/".length());
        }
        return normalized;
    }

    private static Map<Integer, Path> svgBySlide(Path svgOutput) throws IOException {
        if (!Files.isDirectory(svgOutput)) {
            throw violation("svg_output is missing");
        }
        Map<Integer, Path> result = new HashMap<>();
        try (Stream<Path> paths = Files.list(svgOutput)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".svg"))
                    .forEach(path -> {
                Matcher matcher = SLIDE_FILE.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    throw violation("SVG file name does not contain a page number");
                }
                Path duplicate = result.put(Integer.parseInt(matcher.group(1)), path);
                if (duplicate != null) {
                    throw violation("duplicate SVG page number");
                }
            });
        }
        return result;
    }

    private static Map<String, Integer> svgTextCounts(String svg) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            NodeList nodes = elements(document, "text");
            Map<String, Integer> result = new HashMap<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                result.merge(nodes.item(index).getTextContent(), 1, Integer::sum);
            }
            return result;
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw violation("invalid SVG XML");
        }
    }

    private static boolean hasElement(org.w3c.dom.Document document, String localName) {
        return elements(document, localName).getLength() > 0;
    }

    private static NodeList elements(org.w3c.dom.Document document, String localName) {
        NodeList namespaced = document.getElementsByTagNameNS("*", localName);
        return namespaced.getLength() > 0 ? namespaced : document.getElementsByTagName(localName);
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (!node.isArray()) {
            return java.util.List.of();
        }
        return node;
    }

    private static PptJobStateException violation(String detail) {
        return new PptJobStateException("BEAUTIFY_SVG_CONTRACT_FAILED: " + detail);
    }
}
