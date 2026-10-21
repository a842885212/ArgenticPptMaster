// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.TemplateFillEngine;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfAnalyzer;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfApplier;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfChecker;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfConstants;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Java production template-fill engine (analyze / check-plan / apply / validate).
 *
 * <p>Implements slide clone, text / table / chart edits, notes, and transitions with JDK ZIP + DOM
 * (no POI DrawingML writes), aligned with pin {@code cc535363} {@code template_fill_pptx/}.</p>
 */
public final class JavaTemplateFillEngine implements TemplateFillEngine {

    public static final String HEADER = "[JAVA] template-fill";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TIMESTAMPED_STEM = Pattern.compile(".*_\\d{8}_\\d{6}$");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
        try {
            ObjectNode library = TfAnalyzer.analyzePptx(templatePptx);
            Files.createDirectories(slideLibraryOut.getParent());
            Files.writeString(
                    slideLibraryOut,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(library) + "\n");
            return new EngineResult(
                    0,
                    HEADER + " Analyzed " + library.path("slide_count").asInt() + " slides -> " + slideLibraryOut,
                    false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " analyze failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
        try {
            JsonNode library = MAPPER.readTree(slideLibrary.toFile());
            JsonNode plan = MAPPER.readTree(fillPlan.toFile());
            ObjectNode report = TfChecker.checkPlan(library, plan);
            if (checkReportOut != null) {
                Files.createDirectories(checkReportOut.getParent());
                Files.writeString(
                        checkReportOut,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            }
            int errors = report.path("summary").path("error").asInt(0);
            int warns = report.path("summary").path("warn").asInt(0);
            int ok = report.path("summary").path("ok").asInt(0);
            String message = HEADER + " check-plan: ok=" + ok + " warn=" + warns + " error=" + errors;
            return new EngineResult(errors == 0 ? 0 : 1, message, false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " check-plan failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
        try {
            JsonNode plan = MAPPER.readTree(fillPlan.toFile());
            if (!"confirmed".equalsIgnoreCase(plan.path("status").asText())
                    && !plan.path("force").asBoolean(false)) {
                return new EngineResult(
                        1,
                        HEADER + " Error: fill plan is not confirmed: " + fillPlan
                                + " (set status to \"confirmed\" after user approval)",
                        false);
            }
            Path outputPath = timestampedPptxPath(exportOut);
            Files.createDirectories(outputPath.getParent());
            TfApplier.applyPlan(
                    templatePptx,
                    plan,
                    outputPath,
                    TfConstants.DEFAULT_TRANSITION,
                    TfConstants.DEFAULT_TRANSITION_DURATION);
            return new EngineResult(0, HEADER + " Template-filled PPTX -> " + outputPath, false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " apply failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        try {
            ObjectNode report = TfValidator.validateProject(project);
            int errors = report.path("summary").path("error").asInt(0);
            int warns = report.path("summary").path("warn").asInt(0);
            int ok = report.path("summary").path("ok").asInt(0);
            String message = HEADER + " validate: ok=" + ok + " warn=" + warns + " error=" + errors;
            return new EngineResult(errors == 0 ? 0 : 1, message, false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " validate failed: " + ex.getMessage(), false);
        }
    }

    private static Path timestampedPptxPath(Path path) {
        if (path == null || !path.getFileName().toString().toLowerCase().endsWith(".pptx")) {
            return path;
        }
        String stem = path.getFileName().toString();
        stem = stem.substring(0, stem.length() - 5);
        if (TIMESTAMPED_STEM.matcher(stem).matches()) {
            return path;
        }
        return path.resolveSibling(stem + "_" + LocalDateTime.now().format(TIMESTAMP) + ".pptx");
    }
}
