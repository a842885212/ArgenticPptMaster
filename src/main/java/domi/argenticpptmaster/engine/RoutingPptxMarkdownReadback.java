package domi.argenticpptmaster.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPTX→Markdown read-back routing with {@link JavaPptxMarkdownReadback} as the production authority.
 *
 * <p>Dual-run（{@code ppt-master.engine.dual-run-enabled}）开启时，Python {@code ppt_to_md.py}
 * 作为附属对比跑输出到旁路临时文件，差异指标写入 {@code <markdownOut>.dual-run.json}：
 * slide 数、逐页文本覆盖率、image 标记数、manifest 一致性。对比跑任何失败只记
 * {@code comparisonStatus=SKIPPED/FAILED}，不影响 Java 权威结论（对齐 read-back 校验 spec）。</p>
 */
public final class RoutingPptxMarkdownReadback implements PptxMarkdownReadback {

    private static final Logger log = LoggerFactory.getLogger(RoutingPptxMarkdownReadback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PptxMarkdownReadback javaReadback;
    private final PptxMarkdownReadback pythonReadback;
    private final PptEngineProperties properties;

    public RoutingPptxMarkdownReadback(
            PptxMarkdownReadback javaReadback,
            PptxMarkdownReadback pythonReadback,
            PptEngineProperties properties) {
        this.javaReadback = Objects.requireNonNull(javaReadback, "javaReadback");
        this.pythonReadback = Objects.requireNonNull(pythonReadback, "pythonReadback");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public EngineResult toMarkdown(Path pptx, Path markdownOut, Duration timeout) {
        EngineResult javaResult = javaReadback.toMarkdown(pptx, markdownOut, timeout);
        if (properties.isDualRunEnabled()) {
            try {
                dualRunCompare(pptx, markdownOut, timeout);
            } catch (Exception ex) {
                // 对比跑绝不阻塞权威结论
                log.warn("readback dual-run compare failed: {}", ex.toString());
                writeReportQuietly(markdownOut, report("FAILED", 0, 0, false, 0, 0, false));
            }
        }
        return javaResult;
    }

    private void dualRunCompare(Path pptx, Path markdownOut, Duration timeout) throws IOException {
        Path parent = markdownOut.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Path pythonOut = Files.createTempFile(parent, "dual-python-readback-", ".md");
        try {
            EngineResult pythonResult = pythonReadback.toMarkdown(pptx, pythonOut, timeout);
            if (!pythonResult.successful()) {
                log.info("readback dual-run python exit={} (authority unchanged)", pythonResult.exitCode());
                writeReportQuietly(markdownOut, report("FAILED", 0, 0, false, 0, 0, false));
                return;
            }

            String pythonMd = Files.isRegularFile(pythonOut)
                    ? Files.readString(pythonOut, StandardCharsets.UTF_8) : "";
            String javaMd = Files.isRegularFile(markdownOut)
                    ? Files.readString(markdownOut, StandardCharsets.UTF_8) : "";

            List<String> pythonSlides = splitSlides(pythonMd);
            List<String> javaSlides = splitSlides(javaMd);
            double coverage = perSlideTextCoverage(pythonSlides, javaSlides);
            int pythonImages = countImageRefs(pythonMd);
            int javaImages = countImageRefs(javaMd);
            boolean manifestMatch = manifestsEqual(markdownOut, pythonOut);

            boolean matched = pythonSlides.size() == javaSlides.size()
                    && coverage >= 1.0
                    && pythonImages == javaImages
                    && manifestMatch
                    && normalize(pythonMd).equals(normalize(javaMd));
            String status = matched ? "MATCHED" : "DIVERGED";
            if (!matched) {
                log.warn(
                        "readback dual-run DIVERGED slides python={} java={} coverage={} images python={} java={} manifestMatch={}",
                        pythonSlides.size(), javaSlides.size(), coverage, pythonImages, javaImages, manifestMatch);
            }
            writeReportQuietly(markdownOut,
                    report(status, pythonSlides.size(), javaSlides.size(), true, coverage, pythonImages, manifestMatch));
        } finally {
            deleteQuietly(pythonOut);
            deleteRecursivelyQuietly(siblingFilesDir(pythonOut));
        }
    }

    private ObjectNode report(
            String status, int pythonSlides, int javaSlides, boolean pythonRan,
            double coverage, int pythonImages, boolean manifestMatch) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("comparisonStatus", status);
        node.put("pythonSlideCount", pythonSlides);
        node.put("javaSlideCount", javaSlides);
        node.put("pythonRan", pythonRan);
        node.put("perSlideTextCoverage", coverage);
        node.put("pythonImageRefCount", pythonImages);
        node.put("manifestMatch", manifestMatch);
        return node;
    }

    private void writeReportQuietly(Path markdownOut, ObjectNode report) {
        try {
            Path reportPath = markdownOut.resolveSibling(markdownOut.getFileName() + ".dual-run.json");
            Files.writeString(reportPath,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("readback dual-run report write failed: {}", ex.toString());
        }
    }

    /** 按 {@code ## Slide N} 分节（忽略标题行本身），用于逐页对比。 */
    private static List<String> splitSlides(String markdown) {
        List<String> slides = new ArrayList<>();
        StringBuilder current = null;
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## Slide ")) {
                if (current != null) {
                    slides.add(current.toString());
                }
                current = new StringBuilder();
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            slides.add(current.toString());
        }
        return slides;
    }

    /** 逐页文本覆盖率：python 每页规范化非空行在 java 同页中出现的比例，取平均。 */
    private static double perSlideTextCoverage(List<String> pythonSlides, List<String> javaSlides) {
        if (pythonSlides.isEmpty()) {
            return javaSlides.isEmpty() ? 1.0 : 0.0;
        }
        double total = 0;
        int counted = 0;
        for (int i = 0; i < pythonSlides.size(); i++) {
            List<String> pythonLines = normalizedLines(pythonSlides.get(i));
            if (pythonLines.isEmpty()) {
                continue;
            }
            counted++;
            List<String> javaLines = i < javaSlides.size()
                    ? normalizedLines(javaSlides.get(i))
                    : List.of();
            long hits = pythonLines.stream().filter(javaLines::contains).count();
            total += (double) hits / pythonLines.size();
        }
        return counted == 0 ? 1.0 : total / counted;
    }

    private static List<String> normalizedLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : normalize(text).split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int countImageRefs(String markdown) {
        int count = 0;
        for (String line : markdown.split("\n")) {
            if (line.contains("![")) {
                count++;
            }
        }
        return count;
    }

    private static boolean manifestsEqual(Path javaMd, Path pythonMd) throws IOException {
        Path javaManifest = siblingFilesDir(javaMd).resolve("image_manifest.json");
        Path pythonManifest = siblingFilesDir(pythonMd).resolve("image_manifest.json");
        boolean javaHas = Files.isRegularFile(javaManifest);
        boolean pythonHas = Files.isRegularFile(pythonManifest);
        if (javaHas != pythonHas) {
            return false;
        }
        if (!javaHas) {
            return true;
        }
        return normalize(Files.readString(javaManifest, StandardCharsets.UTF_8))
                .equals(normalize(Files.readString(pythonManifest, StandardCharsets.UTF_8)));
    }

    private static Path siblingFilesDir(Path markdownOut) {
        String name = markdownOut.getFileName().toString();
        String stem = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return markdownOut.resolveSibling(stem + "_files");
    }

    private static String normalize(String value) {
        String normalized = value.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder out = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String collapsed = line.replaceAll("\\s+", " ").strip();
            if (!collapsed.isEmpty()) {
                out.append(collapsed).append('\n');
            }
        }
        return out.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>();
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
