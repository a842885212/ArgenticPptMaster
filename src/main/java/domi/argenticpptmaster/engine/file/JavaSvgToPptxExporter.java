// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgToPptxExporter;
import domi.argenticpptmaster.engine.file.svgtopptx.drawingml.SvgSlideConverter;
import domi.argenticpptmaster.engine.file.svgtopptx.gate.QualityReportGate;
import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import domi.argenticpptmaster.engine.file.svgtopptx.pkg.PptxFlatPackageWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Java production authority for service flat-release {@code svg_to_pptx} export.
 *
 * <p>Reads {@code svg_output/} (not {@code svg_final/}), requires {@code spec_lock.md}
 * and a passing final {@code validation/svg_quality_report.json}, writes PPTX under
 * {@code exports/}. Does not use Apache POI as a DrawingML writer.</p>
 */
public final class JavaSvgToPptxExporter implements SvgToPptxExporter {

    private final boolean nativeObjectsEnabled;

    public JavaSvgToPptxExporter() {
        this(false);
    }

    public JavaSvgToPptxExporter(boolean nativeObjectsEnabled) {
        this.nativeObjectsEnabled = nativeObjectsEnabled;
    }

    @Override
    public EngineResult export(Path project, Duration timeout) {
        long started = System.nanoTime();
        try {
            if (project == null || !Files.isDirectory(project)) {
                return fail(1, "Error: project directory not found: " + project);
            }
            SpecLock.Parsed lock = SpecLock.load(project);
            List<Path> svgFiles = QualityReportGate.listSvgOutput(project);
            if (svgFiles.isEmpty()) {
                return fail(1, "Error: No SVG files found");
            }
            QualityReportGate.Fingerprint fingerprint = QualityReportGate.svgSourceFingerprint(svgFiles);
            QualityReportGate.GateResult gate = QualityReportGate.evaluate(project, fingerprint);
            if (!gate.passed()) {
                return fail(
                        1,
                        "Error: default release export requires a passing final SVG quality "
                                + "report for the current svg_output/; found "
                                + gate.status()
                                + ".\nCall validate_svg_output on this project (writes "
                                + "validation/svg_quality_report.json), then retry export.");
            }

            List<SvgSlideConverter.SlideConversion> slides = new ArrayList<>();
            for (Path svg : svgFiles) {
                if (timeoutExceeded(started, timeout)) {
                    return new EngineResult(1, "Error: export timed out", true);
                }
                slides.add(SvgSlideConverter.convert(svg, project, lock, nativeObjectsEnabled));
            }

            Map<String, String> notes = loadNotes(project);
            Path exports = project.resolve("exports");
            Files.createDirectories(exports);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String projectName = project.getFileName().toString();
            Path output = exports.resolve(projectName + "_" + timestamp + ".pptx");
            PptxFlatPackageWriter.write(project, lock, slides, svgFiles, notes, output);

            // Mirror pin: archive svg_output under backup/<ts>/ when present.
            Path svgOutput = project.resolve("svg_output");
            if (Files.isDirectory(svgOutput)) {
                Path backup = project.resolve("backup").resolve(timestamp).resolve("svg_output");
                copyDirectory(svgOutput, backup);
            }

            String message = "Exported " + slides.size() + " slide(s) -> " + output;
            return new EngineResult(0, message, false);
        } catch (IllegalStateException ex) {
            return fail(1, "Error: " + ex.getMessage());
        } catch (Exception ex) {
            return fail(1, "Error: " + ex.getMessage());
        }
    }

    private static Map<String, String> loadNotes(Path project) throws Exception {
        Map<String, String> notes = new LinkedHashMap<>();
        Path notesDir = project.resolve("notes");
        if (!Files.isDirectory(notesDir)) {
            return notes;
        }
        try (Stream<Path> stream = Files.list(notesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String stem = path.getFileName().toString().replaceFirst("\\.md$", "");
                            notes.put(stem, Files.readString(path).strip());
                        } catch (Exception ignored) {
                            // skip unreadable notes
                        }
                    });
        }
        return notes;
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static boolean timeoutExceeded(long startedNanos, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        return Duration.ofNanos(System.nanoTime() - startedNanos).compareTo(timeout) > 0;
    }

    private static EngineResult fail(int code, String message) {
        return new EngineResult(code, message, false);
    }
}
