// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SVG→PPTX routing with {@code JavaSvgToPptxExporter} as the production authority.
 *
 * <p>When dual-run is enabled, Python runs as a secondary diagnostic on a project
 * copy; mismatches are logged (or thrown when fail-on-mismatch is set) and never
 * replace the Java {@code exports/} artifact. Comparison uses
 * {@link SlideXmlComparator} (ignore rIds / whitespace).</p>
 */
public final class RoutingSvgToPptxExporter implements SvgToPptxExporter {

    private static final Logger log = LoggerFactory.getLogger(RoutingSvgToPptxExporter.class);

    private final SvgToPptxExporter javaEngine;
    private final SvgToPptxExporter pythonEngine;
    private final PptEngineProperties properties;
    private final SlideXmlComparator slideComparator;

    public RoutingSvgToPptxExporter(SvgToPptxExporter javaEngine, SvgToPptxExporter pythonEngine) {
        this(javaEngine, pythonEngine, PptEngineProperties.defaults());
    }

    public RoutingSvgToPptxExporter(
            SvgToPptxExporter javaEngine, SvgToPptxExporter pythonEngine, PptEngineProperties properties) {
        this(javaEngine, pythonEngine, properties, new SlideXmlComparator());
    }

    public RoutingSvgToPptxExporter(
            SvgToPptxExporter javaEngine,
            SvgToPptxExporter pythonEngine,
            PptEngineProperties properties,
            SlideXmlComparator slideComparator) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.slideComparator = Objects.requireNonNull(slideComparator, "slideComparator");
    }

    @Override
    public EngineResult export(Path project, Duration timeout) {
        EngineResult javaResult = javaEngine.export(project, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose(project, javaResult, timeout);
        }
        return javaResult;
    }

    private void dualRunDiagnose(Path project, EngineResult javaResult, Duration timeout) {
        try {
            Path dualRoot = Files.createTempDirectory(project.getParent(), "dual-python-svg2pptx-");
            try {
                copyProjectInputs(project, dualRoot);
                EngineResult pythonResult = pythonEngine.export(dualRoot, timeout);
                log.info(
                        "svg-to-pptx dual-run javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                if (javaResult.successful() && pythonResult.successful()) {
                    Path javaPptx = newestExport(project.resolve("exports"));
                    Path pythonPptx = newestExport(dualRoot.resolve("exports"));
                    if (javaPptx == null || pythonPptx == null) {
                        log.warn("svg-to-pptx dual-run skipped: missing export pptx");
                        return;
                    }
                    SlideXmlComparator.Result slideResult = slideComparator.compare(javaPptx, pythonPptx);
                    if (!slideResult.equal()) {
                        String summary = "svg-to-pptx dual-run slide XML mismatch for "
                                + javaPptx.getFileName()
                                + ": slideDiffs="
                                + slideResult.diffs().size();
                        if (properties.isDualRunFailOnMismatch()) {
                            throw new IllegalStateException(summary);
                        }
                        log.warn(summary);
                    } else {
                        log.info("svg-to-pptx dual-run slide XML match for {}", javaPptx.getFileName());
                    }
                }
            } finally {
                deleteRecursivelyQuietly(dualRoot);
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("svg-to-pptx dual-run diagnostic failed; java remains authority", ex);
        }
    }

    private static Path newestExport(Path exportsDir) throws IOException {
        if (!Files.isDirectory(exportsDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(exportsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private static void copyProjectInputs(Path source, Path target) throws IOException {
        for (String name : List.of("svg_output", "notes", "images", "icons", "spec_lock.md", "validation")) {
            Path src = source.resolve(name);
            if (!Files.exists(src)) {
                continue;
            }
            if (Files.isDirectory(src)) {
                copyDirectory(src, target.resolve(name));
            } else {
                Files.createDirectories(target);
                Files.copy(src, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
