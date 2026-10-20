// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgQualityChecker;
import domi.argenticpptmaster.engine.file.SlideRoster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Partial SVG quality gate: viewBox, external image references, and icon placeholders.
 *
 * <p>Not a production {@link SvgQualityChecker}; font, geometry, animation, and roster rules
 * from ppt-master are intentionally omitted.</p>
 */
public final class JavaSvgQualityChecker implements SvgQualityChecker {

    private static final String PARTIAL_HEADER =
            "[PARTIAL] viewBox, image references, and icon placeholders";

    @Override
    public EngineResult check(Path project, String format, Duration timeout) {
        Optional<SvgQualityCanvasFormats.Format> canvas = SvgQualityCanvasFormats.resolve(format);
        if (canvas.isEmpty()) {
            String normalized = SvgQualityCanvasFormats.normalizeFormatKey(format);
            String detail = normalized.isEmpty()
                    ? "missing or empty canvas format"
                    : "unsupported canvas format: " + normalized;
            String output = "[ERROR] " + detail + "\n"
                    + "Available formats: " + SvgQualityCanvasFormats.sortedKeysList();
            return new EngineResult(1, output, false);
        }

        String expectedViewBox = canvas.get().viewBox();
        Path svgDir = ViewBoxQualitySupport.resolveSvgDirectory(project);
        if (!Files.isDirectory(svgDir)) {
            String output = "[ERROR] No SVG files found for viewBox check under: " + svgDir;
            return new EngineResult(1, output, false);
        }

        List<Path> svgFiles = SlideRoster.discoverSlideSvgs(svgDir);
        if (svgFiles.isEmpty()) {
            String output = "[ERROR] No SVG files found for viewBox check under: " + svgDir;
            return new EngineResult(1, output, false);
        }

        String expectedCompressed = ViewBoxQualitySupport.compressViewBoxWhitespace(expectedViewBox);
        List<String> failures = new ArrayList<>();

        for (Path svgFile : svgFiles) {
            String fileLabel = svgFile.getFileName().toString();

            Optional<String> actual = ViewBoxQualitySupport.readRootViewBox(svgFile);
            if (actual.isEmpty()) {
                failures.add(fileLabel
                        + ": could not read viewBox (missing attribute, invalid XML, or parse error); "
                        + "expected '" + expectedCompressed + "'");
            } else {
                String actualCompressed = ViewBoxQualitySupport.compressViewBoxWhitespace(actual.get());
                if (!expectedCompressed.equals(actualCompressed)) {
                    failures.add(fileLabel
                            + ": viewBox mismatch — expected '" + expectedCompressed
                            + "', got '" + actualCompressed + "'");
                }
            }

            Optional<Document> document = SvgQualityXml.parse(svgFile);
            if (document.isPresent()) {
                for (String error : ImageReferenceQualitySupport.checkMissingExternalImages(
                        svgFile, document.get())) {
                    failures.add(fileLabel + ": " + error);
                }
                for (String error : IconPlaceholderQualitySupport.checkIconPlaceholders(
                        svgFile, document.get())) {
                    failures.add(fileLabel + ": " + error);
                }
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder output = new StringBuilder();
            output.append(PARTIAL_HEADER).append('\n');
            output.append("[ERROR] ").append(failures.size()).append(" SVG issue(s)\n");
            for (String line : failures) {
                output.append("  - ").append(line).append('\n');
            }
            return new EngineResult(1, output.toString().stripTrailing(), false);
        }

        StringBuilder output = new StringBuilder();
        output.append(PARTIAL_HEADER).append('\n');
        output.append("[OK] Checked ").append(svgFiles.size()).append(" SVG file(s); expected viewBox '")
                .append(expectedCompressed).append("'\n");
        for (Path svgFile : svgFiles) {
            output.append("  - ").append(svgFile.getFileName()).append('\n');
        }
        return new EngineResult(0, output.toString().stripTrailing(), false);
    }
}
