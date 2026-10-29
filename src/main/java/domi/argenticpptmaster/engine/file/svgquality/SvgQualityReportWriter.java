// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import domi.argenticpptmaster.engine.file.svgtopptx.gate.QualityReportGate;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes gate-compatible {@code validation/svg_quality_report.json} for the Java quality subset.
 *
 * <p>Only fields read by {@link QualityReportGate} are authoritative; remaining keys are a minimal
 * legal skeleton so the report stays schema-shaped.</p>
 */
final class SvgQualityReportWriter {

    static final String SCHEMA = "ppt-master.svg-quality-report.v1";
    static final String STAGE = "final";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SvgQualityReportWriter() {}

    static void writeFinalReport(Path project, List<Path> svgFiles, List<String> failures)
            throws IOException {
        QualityReportGate.Fingerprint fingerprint = QualityReportGate.svgSourceFingerprint(svgFiles);
        Map<String, Object> report = buildReport(project, fingerprint, failures);
        Path validationDir = project.resolve("validation");
        Files.createDirectories(validationDir);
        Path target = validationDir.resolve("svg_quality_report.json");
        Path temporary = Files.createTempFile(validationDir, "svg_quality_report-", ".json.tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), report);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> buildReport(
            Path project, QualityReportGate.Fingerprint fingerprint, List<String> failures) {
        List<Map<String, String>> blockingIssues = toIssues(failures);
        int blockingCount = blockingIssues.size();

        Map<String, Object> sourceFingerprint = new LinkedHashMap<>();
        sourceFingerprint.put("algorithm", fingerprint.algorithm());
        sourceFingerprint.put("digest", fingerprint.digest());
        sourceFingerprint.put("file_count", fingerprint.fileCount());
        sourceFingerprint.put("files", fingerprint.files());

        Map<String, Object> blocking = new LinkedHashMap<>();
        blocking.put("count", blockingCount);
        blocking.put("issues", blockingIssues);

        Map<String, Object> emptyCategory = emptyIssuesCategory();
        Map<String, Object> categories = new LinkedHashMap<>();
        categories.put("blocking", blocking);
        categories.put("introduced", emptyCategory);
        categories.put("inherited", emptyCategory);
        Map<String, Object> sourceImport = new LinkedHashMap<>();
        sourceImport.put("count", 0);
        sourceImport.put("summary", Map.of("warning_count", 0, "by_code", Map.of()));
        categories.put("source-import", sourceImport);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", blockingCount);
        summary.put("passed", blockingCount == 0 ? fingerprint.fileCount() : 0);
        summary.put("warnings", 0);
        summary.put("errors", blockingCount);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", SCHEMA);
        report.put("stage", STAGE);
        report.put("target", project.toAbsolutePath().normalize().toString());
        report.put("source_fingerprint", sourceFingerprint);
        report.put("summary", summary);
        report.put("issue_types", Map.of());
        report.put("categories", categories);
        report.put("drift", Map.of("colors", Map.of(), "fonts", Map.of(), "sizes", Map.of()));
        report.put(
                "carrier_receipt",
                Map.of(
                        "scope",
                        "informational-not-a-quota",
                        "pages",
                        fingerprint.fileCount(),
                        "totals",
                        Map.of(),
                        "pages_with",
                        Map.of(),
                        "geometry_elements",
                        Map.of(),
                        "preset_names",
                        Map.of(),
                        "native_objects",
                        Map.of(),
                        "image_page_max_frame_share_range",
                        List.of()));
        report.put(
                "project_issues",
                Map.of(
                        "template",
                        List.of(),
                        "animation",
                        List.of(),
                        "illustration",
                        List.of(),
                        "communication_trace",
                        List.of(),
                        "pptx_structure",
                        List.of()));
        report.put("files", List.of());
        return report;
    }

    private static Map<String, Object> emptyIssuesCategory() {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("count", 0);
        category.put("issues", List.of());
        return category;
    }

    private static List<Map<String, String>> toIssues(List<String> failures) {
        List<Map<String, String>> issues = new ArrayList<>();
        for (String failure : failures) {
            Map<String, String> issue = new LinkedHashMap<>();
            int sep = failure.indexOf(": ");
            if (sep > 0) {
                issue.put("file", failure.substring(0, sep));
                issue.put("message", failure.substring(sep + 2));
            } else {
                issue.put("file", "");
                issue.put("message", failure);
            }
            issues.add(issue);
        }
        return issues;
    }
}
