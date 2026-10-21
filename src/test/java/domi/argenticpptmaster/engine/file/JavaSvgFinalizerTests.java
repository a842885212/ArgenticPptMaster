// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parity tests for {@link JavaSvgFinalizer} against pin {@code finalize_svg.py} gold.
 *
 * <p>Image payloads are compared by MIME stub only: ImageIO encode need not match PIL.</p>
 */
class JavaSvgFinalizerTests {

    private static final Pattern DATA_URI = Pattern.compile(
            "^(data:[^;]+;base64,)([A-Za-z0-9+/=]+)$", Pattern.CASE_INSENSITIVE);

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"text-tspan", "icons-use", "images-meet-slice"})
    void finalizeMatchesPinGold(String fixture) throws Exception {
        Path project = materializeFixture(fixture);
        var result = new JavaSvgFinalizer().finalizeSvg(project, Duration.ofMinutes(1));
        assertThat(result.successful()).as(result.output()).isTrue();

        Path actualFinal = project.resolve("svg_final");
        assertThat(Files.isDirectory(actualFinal)).isTrue();
        List<Path> actualSvgs = listSvgs(actualFinal);
        assertThat(actualSvgs).isNotEmpty();

        Path expectedRoot = Path.of("src/test/resources/finalize-svg", fixture, "expected-svg_final");
        List<Path> expectedSvgs = listSvgs(expectedRoot);
        assertThat(actualSvgs).hasSameSizeAs(expectedSvgs);

        for (int i = 0; i < expectedSvgs.size(); i++) {
            String expectedNorm = normalizeSvg(expectedSvgs.get(i));
            String actualNorm = normalizeSvg(actualSvgs.get(i));
            assertThat(actualNorm)
                    .as("normalized SVG for %s / %s", fixture, expectedSvgs.get(i).getFileName())
                    .isEqualTo(expectedNorm);
        }
    }

    @Test
    void textTspanAssertsIndependentTextLines() throws Exception {
        Path project = materializeFixture("text-tspan");
        assertThat(new JavaSvgFinalizer().finalizeSvg(project, Duration.ZERO).successful()).isTrue();
        Document doc = parse(project.resolve("svg_final/slide_01.svg"));
        List<Element> texts = descendants(doc.getDocumentElement(), "text");
        assertThat(texts).hasSizeGreaterThanOrEqualTo(3);
        assertThat(texts.get(0).getAttribute("y")).isEqualTo("120");
        assertThat(texts.get(1).getAttribute("y")).isEqualTo("152");
        assertThat(texts.get(2).getAttribute("y")).isEqualTo("184");
        assertThat(texts.get(2).getTextContent()).contains("Third bold");
    }

    @Test
    void iconsUseAssertsIconGroupAndLocalExpansion() throws Exception {
        Path project = materializeFixture("icons-use");
        assertThat(new JavaSvgFinalizer().finalizeSvg(project, Duration.ZERO).successful()).isTrue();
        Document doc = parse(project.resolve("svg_final/slide_01.svg"));
        assertThat(descendants(doc.getDocumentElement(), "use")).isEmpty();
        List<Element> groups = descendants(doc.getDocumentElement(), "g");
        assertThat(groups.stream().anyMatch(g -> {
                    String tf = g.getAttribute("transform");
                    return tf.contains("translate(80") && tf.contains("scale(3)") && "#0076A8".equals(g.getAttribute("fill"));
                }))
                .isTrue();
        assertThat(groups.stream().anyMatch(g -> g.getAttribute("id").startsWith("use-instance-")))
                .isTrue();
        Element rect = descendants(doc.getDocumentElement(), "rect").stream()
                .filter(r -> "20".equals(r.getAttribute("x")) && "200".equals(r.getAttribute("y")))
                .findFirst()
                .orElseThrow();
        assertThat(rect.getAttribute("width")).isEqualTo("40");
    }

    @Test
    void imagesMeetSliceAssertsGeometryAndDataUri() throws Exception {
        Path project = materializeFixture("images-meet-slice");
        assertThat(new JavaSvgFinalizer().finalizeSvg(project, Duration.ZERO).successful()).isTrue();
        Document doc = parse(project.resolve("svg_final/slide_01.svg"));
        List<Element> images = descendants(doc.getDocumentElement(), "image");
        assertThat(images).hasSize(2);

        Element meet = images.get(0);
        assertThat(meet.getAttribute("href")).startsWith("data:image/png;base64,");
        assertThat(meet.getAttribute("x")).isEqualTo("40");
        assertThat(meet.getAttribute("y")).isEqualTo("90");
        assertThat(meet.getAttribute("width")).isEqualTo("200");
        assertThat(meet.getAttribute("height")).isEqualTo("100");
        assertThat(meet.hasAttribute("preserveAspectRatio")).isFalse();

        Element slice = images.get(1);
        assertThat(slice.getAttribute("href")).startsWith("data:image/png;base64,");
        assertThat(slice.getAttribute("x")).isEqualTo("300");
        assertThat(slice.getAttribute("y")).isEqualTo("40");
        assertThat(slice.getAttribute("width")).isEqualTo("200");
        assertThat(slice.getAttribute("height")).isEqualTo("100");
    }

    @Test
    void failedProcessingDoesNotPublishBrokenSvgFinal() throws Exception {
        Path project = tempDir.resolve("broken");
        Files.createDirectories(project.resolve("svg_output"));
        Files.writeString(
                project.resolve("svg_output/bad.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><text><tspan><tspan dy=\"10\">x</tspan></tspan></text></svg>");
        Path prior = project.resolve("svg_final");
        Files.createDirectories(prior);
        Files.writeString(prior.resolve("keep.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

        var result = new JavaSvgFinalizer().finalizeSvg(project, Duration.ZERO);
        assertThat(result.successful()).isFalse();
        assertThat(Files.readString(prior.resolve("keep.svg"))).contains("<svg");
        assertThat(Files.exists(prior.resolve("bad.svg"))).isFalse();
    }

    private Path materializeFixture(String fixture) throws Exception {
        Path source = Path.of("src/test/resources/finalize-svg", fixture);
        Path project = tempDir.resolve(fixture);
        copyTree(source, project, name -> !name.equals("expected-svg_final"));
        return project;
    }

    private static void copyTree(Path source, Path target, java.util.function.Predicate<String> includeTop)
            throws Exception {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (!includeTop.test(name)) {
                    continue;
                }
                Path dest = target.resolve(name);
                if (Files.isDirectory(child)) {
                    copyDirectory(child, dest);
                } else {
                    Files.copy(child, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String normalizeSvg(Path svgPath) throws Exception {
        Document document = parse(svgPath);
        StringBuilder sb = new StringBuilder();
        serializeNormalized(document.getDocumentElement(), sb);
        return sb.toString();
    }

    private static Document parse(Path svgPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream in = Files.newInputStream(svgPath)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static void serializeNormalized(Element element, StringBuilder sb) {
        String local = localName(element);
        sb.append('<').append(local);
        List<String[]> attrs = new ArrayList<>();
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node attr = map.item(i);
            String name = attr.getNodeName();
            if (name.startsWith("xmlns")) {
                continue;
            }
            String value = attr.getNodeValue();
            String attrLocal = attr.getLocalName() != null ? attr.getLocalName() : name;
            if ("href".equals(attrLocal) || name.endsWith(":href")) {
                value = stubDataUri(value);
            }
            // Normalize float formatting noise on geometry attrs.
            if (List.of("x", "y", "width", "height", "cx", "cy", "r", "rx", "ry").contains(attrLocal)) {
                value = normalizeNumberToken(value);
            }
            attrs.add(new String[] {attrLocal, value});
        }
        attrs.sort(Comparator.comparing(a -> a[0]));
        for (String[] attr : attrs) {
            sb.append(' ').append(attr[0]).append("=\"").append(attr[1]).append('"');
        }
        NodeList children = element.getChildNodes();
        boolean hasElementChild = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                hasElementChild = true;
                break;
            }
        }
        if (!hasElementChild) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                    text.append(child.getNodeValue());
                }
            }
            String collapsed = text.toString().replaceAll("\\s+", " ").strip();
            if (collapsed.isEmpty()) {
                sb.append("/>");
            } else {
                sb.append('>').append(collapsed).append("</").append(local).append('>');
            }
            return;
        }
        sb.append('>');
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childEl) {
                serializeNormalized(childEl, sb);
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String collapsed = child.getNodeValue().replaceAll("\\s+", " ").strip();
                if (!collapsed.isEmpty()) {
                    sb.append(collapsed);
                }
            }
        }
        sb.append("</").append(local).append('>');
    }

    private static String stubDataUri(String value) {
        Matcher matcher = DATA_URI.matcher(value == null ? "" : value);
        if (matcher.matches()) {
            return matcher.group(1) + "<payload>";
        }
        return value;
    }

    private static String normalizeNumberToken(String value) {
        try {
            double n = Double.parseDouble(value);
            if (Math.abs(n - Math.round(n)) < 1e-6) {
                return Long.toString(Math.round(n));
            }
            return String.format(Locale.ROOT, "%.4f", n).replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Exception ex) {
            return value;
        }
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static List<Path> listSvgs(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }
}
