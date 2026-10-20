// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Finalize-SVG routing with {@code JavaSvgFinalizer} as the production authority.
 *
 * <p>When dual-run is enabled, Python runs as a secondary diagnostic on a project copy;
 * mismatches are logged (or thrown when fail-on-mismatch is set) and never replace the
 * Java {@code svg_final} artifact. Embedded image payloads are compared by MIME only
 * (ImageIO encode need not match PIL byte-for-byte).</p>
 */
public final class RoutingSvgFinalizer implements SvgFinalizer {

    private static final Logger log = LoggerFactory.getLogger(RoutingSvgFinalizer.class);
    private static final Pattern DATA_URI = Pattern.compile(
            "^(data:[^;]+;base64,)([A-Za-z0-9+/=]+)$", Pattern.CASE_INSENSITIVE);

    private final SvgFinalizer javaEngine;
    private final SvgFinalizer pythonEngine;
    private final PptEngineProperties properties;

    public RoutingSvgFinalizer(SvgFinalizer javaEngine, SvgFinalizer pythonEngine) {
        this(javaEngine, pythonEngine, PptEngineProperties.defaults());
    }

    public RoutingSvgFinalizer(
            SvgFinalizer javaEngine, SvgFinalizer pythonEngine, PptEngineProperties properties) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public EngineResult finalizeSvg(Path project, Duration timeout) {
        EngineResult javaResult = javaEngine.finalizeSvg(project, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose(project, javaResult, timeout);
        }
        return javaResult;
    }

    private void dualRunDiagnose(Path project, EngineResult javaResult, Duration timeout) {
        try {
            Path dualRoot = Files.createTempDirectory(project.getParent(), "dual-python-finalize-");
            try {
                copyProjectInputs(project, dualRoot);
                EngineResult pythonResult = pythonEngine.finalizeSvg(dualRoot, timeout);
                log.info(
                        "finalize-svg dual-run javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                if (javaResult.successful() && pythonResult.successful()) {
                    compareSvgFinalOrThrow(project.resolve("svg_final"), dualRoot.resolve("svg_final"));
                }
            } finally {
                deleteRecursivelyQuietly(dualRoot);
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("finalize-svg dual-run diagnostic failed; java remains authority", ex);
        }
    }

    private void compareSvgFinalOrThrow(Path javaFinal, Path pythonFinal) throws Exception {
        List<Path> javaSvgs = listSvgs(javaFinal);
        List<Path> pythonSvgs = listSvgs(pythonFinal);
        if (javaSvgs.size() != pythonSvgs.size()) {
            mismatch("svg count java=" + javaSvgs.size() + " python=" + pythonSvgs.size());
            return;
        }
        for (int i = 0; i < javaSvgs.size(); i++) {
            String javaNorm = normalizeSvg(javaSvgs.get(i));
            String pythonNorm = normalizeSvg(pythonSvgs.get(i));
            if (!javaNorm.equals(pythonNorm)) {
                mismatch("SVG mismatch for " + javaSvgs.get(i).getFileName());
            } else {
                log.info("finalize-svg dual-run match for {}", javaSvgs.get(i).getFileName());
            }
        }
    }

    private void mismatch(String summary) {
        String message = "finalize-svg dual-run " + summary;
        if (properties.isDualRunFailOnMismatch()) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    /** Structural SVG normalize: sorted attrs, collapsed whitespace, data-URI MIME stub. */
    static String normalizeSvg(Path svgPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(svgPath.toFile());
        StringBuilder sb = new StringBuilder();
        serializeNormalized(document.getDocumentElement(), sb);
        return sb.toString();
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
            if ("href".equals(localNameOfAttr(attr)) || name.endsWith(":href") || "href".equals(name)) {
                value = stubDataUri(value);
            }
            attrs.add(new String[] {name, value});
        }
        attrs.sort(Comparator.comparing(a -> a[0]));
        for (String[] attr : attrs) {
            sb.append(' ').append(attr[0]).append("=\"").append(attr[1]).append('"');
        }
        NodeList children = element.getChildNodes();
        boolean hasElementChild = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childEl) {
                hasElementChild = true;
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        if (!hasElementChild && text.toString().strip().isEmpty() && children.getLength() == 0) {
            sb.append("/>");
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
        if (value == null) {
            return "";
        }
        Matcher matcher = DATA_URI.matcher(value);
        if (matcher.matches()) {
            return matcher.group(1) + "<payload>";
        }
        return value;
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

    private static String localNameOfAttr(Node attr) {
        String local = attr.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String name = attr.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static void copyProjectInputs(Path source, Path target) throws IOException {
        for (String name : List.of("svg_output", "icons", "images")) {
            Path src = source.resolve(name);
            if (Files.exists(src)) {
                copyDirectory(src, target.resolve(name));
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<Path> listSvgs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort.
        }
    }
}
