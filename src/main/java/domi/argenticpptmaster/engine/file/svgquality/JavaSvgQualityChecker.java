// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgQualityChecker;
import domi.argenticpptmaster.engine.file.SlideRoster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Java SVG quality subset for the service path ({@code project --format X}, stage final).
 *
 * <p>Phase-1 + phase-2 + phase-3 blocking rules. Pin behavioral reference:
 * {@code /tmp/ppt-master-cc535363} → {@code skills/ppt-master/scripts/svg_quality/checker.py}.
 * Phase-3 boundaries: typography recurring-role only (color/font info); preset fail-closed when
 * marked; preserved txBody only with payload; text/module bounds pin-aligned error paths.</p>
 *
 * <p>On every completed scan (pass or fail) writes gate-compatible
 * {@code validation/svg_quality_report.json}. Production authority via
 * {@code RoutingSvgQualityChecker}; Python remains dual-run diagnostics only.</p>
 */
public final class JavaSvgQualityChecker implements SvgQualityChecker {

    static final String SUBSET_HEADER =
            "[JAVA-SUBSET] viewBox, forbidden elements (+geometry/<use>), authoring properties, "
                    + "font-size, image references, icon placeholders, legacy pptx attrs, "
                    + "unsupported visuals, opacity/geometry/stroke errors, paint errors, "
                    + "clip/mask/defs/paint-ref/marker contracts, "
                    + "transform/freeform/gradient-filter/image-contract/text-property/"
                    + "hyperlink/pattern-fill/native-marker, "
                    + "preset-geometry(marked)/preserved-txbody(payload)/module-bounds/"
                    + "text-bounds/spec_lock-size-recurrence";

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
        Map<Path, Document> parsedDocuments = new LinkedHashMap<>();

        for (Path svgFile : svgFiles) {
            String fileLabel = svgFile.getFileName().toString();
            String content;
            try {
                content = Files.readString(svgFile, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                failures.add(fileLabel + ": Failed to read file: " + ex.getMessage());
                continue;
            }

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
            if (document.isEmpty()) {
                if (actual.isPresent()) {
                    failures.add(fileLabel + ": invalid XML (could not parse for quality checks)");
                }
                continue;
            }

            Document doc = document.get();
            parsedDocuments.put(svgFile, doc);
            // Stable phase-1 order: existing subset first, then new blocking families.
            for (String error : ForbiddenElementsQualitySupport.checkForbiddenElements(content, doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : AuthoringPropertyContractSupport.checkAuthoringPropertyContract(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : FontSizeQualitySupport.checkFontSizeValues(content)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : ImageReferenceQualitySupport.checkMissingExternalImages(svgFile, doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : IconPlaceholderQualitySupport.checkIconPlaceholders(svgFile, doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : LegacyPptxAttributeQualitySupport.checkLegacyPptxAttributes(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : UnsupportedVisualsQualitySupport.checkUnsupportedVisualElements(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : OpacityQualitySupport.checkOpacityValues(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : GeometryLengthQualitySupport.checkGeometryLengthValues(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : StrokeStyleQualitySupport.checkStrokeStyleValues(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : PaintCompatibilityQualitySupport.checkPaintCompatibility(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : ClipMaskContractQualitySupport.checkClipAndMaskContracts(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error :
                    DefinitionPaintMarkerContractQualitySupport.checkDefinitionPaintMarkerContracts(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            // Phase-2 blocking families (stable order).
            for (String error : TransformQualitySupport.checkTransformValues(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : FreeformGeometryQualitySupport.checkFreeformGeometryValues(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : GradientFilterQualitySupport.checkGradientAndFilterContracts(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : ImageContractQualitySupport.checkImageContract(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : TextPropertyQualitySupport.checkTextPropertyContract(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : HyperlinkQualitySupport.checkHyperlinks(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : PatternFillQualitySupport.checkPatternFills(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : NativeObjectMarkerQualitySupport.checkNativeObjectMarkers(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            // Phase-3 blocking families (stable order; per-file).
            for (String error : PresetGeometryQualitySupport.checkPresetGeometry(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : PreservedTxBodyQualitySupport.checkPreservedTxBody(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : ModuleBoundsQualitySupport.checkModuleBounds(doc)) {
                failures.add(fileLabel + ": " + error);
            }
            for (String error : TextBoundsQualitySupport.checkTextBounds(doc)) {
                failures.add(fileLabel + ": " + error);
            }
        }

        for (Map.Entry<Path, List<String>> entry :
                SpecLockTypographyRecurrenceQualitySupport.checkProject(project, svgFiles, parsedDocuments)
                        .entrySet()) {
            String fileLabel = entry.getKey().getFileName().toString();
            for (String error : entry.getValue()) {
                failures.add(fileLabel + ": " + error);
            }
        }

        String reportError = writeReport(project, svgFiles, failures);

        if (!failures.isEmpty()) {
            StringBuilder output = new StringBuilder();
            output.append(SUBSET_HEADER).append('\n');
            output.append("[ERROR] ").append(failures.size()).append(" SVG issue(s)\n");
            for (String line : failures) {
                output.append("  - ").append(line).append('\n');
            }
            if (reportError != null) {
                output.append("[ERROR] failed to write svg_quality_report.json: ").append(reportError);
            }
            return new EngineResult(1, output.toString().stripTrailing(), false);
        }

        if (reportError != null) {
            return new EngineResult(
                    1,
                    SUBSET_HEADER + "\n[ERROR] failed to write svg_quality_report.json: " + reportError,
                    false);
        }

        StringBuilder output = new StringBuilder();
        output.append(SUBSET_HEADER).append('\n');
        output.append("[OK] Checked ").append(svgFiles.size()).append(" SVG file(s); expected viewBox '")
                .append(expectedCompressed).append("'\n");
        for (Path svgFile : svgFiles) {
            output.append("  - ").append(svgFile.getFileName()).append('\n');
        }
        return new EngineResult(0, output.toString().stripTrailing(), false);
    }

    private static String writeReport(Path project, List<Path> svgFiles, List<String> failures) {
        try {
            SvgQualityReportWriter.writeFinalReport(project, svgFiles, failures);
            return null;
        } catch (IOException ex) {
            return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
    }
}
