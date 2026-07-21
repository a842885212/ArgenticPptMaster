package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillDiagnosticBundleBuilderTests {

    private static final String TEMPLATE_SECRET = "TOP-SECRET-TEMPLATE-BYTES";
    private static final String CONTENT_SECRET = "TOP-SECRET-CONTENT-TEXT";
    private static final String PLAN_SECRET = "TOP-SECRET-FILL-PLAN-BODY";
    private static final String EXPORT_SECRET = "TOP-SECRET-EXPORT-PPTX";
    private static final String OWNER_SUBJECT = "owner-user-42";
    private static final String OWNER_TENANT = "tenant-secret";

    @TempDir
    Path tempDir;

    private TemplateFillDiagnosticBundleBuilder builder;
    private PptMasterProperties properties;
    private PptJob job;

    @BeforeEach
    void setUp() throws Exception {
        properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a");
        builder = new TemplateFillDiagnosticBundleBuilder(
                new TemplateFillLifecycleStore(properties),
                new PptTemplateFillAnalysisReader());
        Path workspace = tempDir.resolve("job");
        Files.createDirectories(workspace);
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.assignOwnership(OWNER_SUBJECT, OWNER_TENANT);
        seedSensitiveWorkspace(workspace);
        seedAllowedArtifacts(workspace, job);
    }

    @Test
    void bundleIncludesOnlyWhitelistedSummariesAndExcludesSensitiveArtifacts() throws Exception {
        TemplateFillDiagnosticBundleBuilder.DiagnosticBundle bundle = builder.build(job, properties);

        assertThat(bundle.path()).exists();
        assertThat(bundle.bytes().length).isLessThanOrEqualTo(TemplateFillDiagnosticBundleBuilder.MAX_ZIP_BYTES);

        Set<String> entryNames = new HashSet<>();
        StringBuilder combined = new StringBuilder();
        try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryNames.add(entry.getName());
                combined.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertThat(entryNames).contains(
                "lifecycle/lifecycle-summary.json",
                "readiness/readiness-marker.txt",
                "analysis/analysis-summary.json",
                "analysis/fill_plan.meta.json",
                "analysis/fill_plan.service-meta.json",
                "validation/template-fill-readback-summary.json",
                "validation/check-report-summary.json",
                "events-summary.json");
        assertThat(entryNames).noneMatch(name -> name.contains("uploads/")
                || name.contains("exports/")
                || name.endsWith("fill_plan.json")
                || name.endsWith(".pptx")
                || name.contains("template-fill-readback.json"));

        String payload = combined.toString();
        assertThat(payload).doesNotContain(
                TEMPLATE_SECRET,
                CONTENT_SECRET,
                PLAN_SECRET,
                EXPORT_SECRET,
                OWNER_SUBJECT,
                OWNER_TENANT,
                "/home/",
                "uploads/template",
                "uploads/content",
                "exports/out.pptx",
                "fill_plan.json",
                "附录中的历史版本对比");
        assertThat(payload).contains("template-fill-readiness-marker-v1", "PASSED_WITH_WARNINGS", "JOB_ACCEPTED");
    }

    @Test
    void rejectsSymlinkedWhitelistCandidate() throws Exception {
        Path workspace = job.workspacePath();
        Path real = workspace.resolve("analysis/check_report.json");
        Files.delete(real);
        Files.createSymbolicLink(real, workspace.resolve("uploads/content/notes.md"));

        assertThatThrownBy(() -> builder.build(job, properties))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void redactsAbsolutePathsInEventMessages() throws Exception {
        job.addEvent(PptJobEvent.of(
                PptJobEventType.JOB_FAILED,
                "failed at /home/operator/jobs/" + job.id() + "/analysis"));

        TemplateFillDiagnosticBundleBuilder.DiagnosticBundle bundle = builder.build(job, properties);
        String eventsSummary = readZipEntry(bundle.path(), "events-summary.json");

        assertThat(eventsSummary).contains("[REDACTED_PATH]");
        assertThat(eventsSummary).doesNotContain("/home/operator");
    }

    private static String readZipEntry(Path bundlePath, String name) throws Exception {
        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertThat(entry).isNotNull();
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void seedSensitiveWorkspace(Path workspace) throws Exception {
        Path templateDir = workspace.resolve("uploads/template");
        Path contentDir = workspace.resolve("uploads/content");
        Path exportsDir = workspace.resolve("exports");
        Path analysisDir = workspace.resolve("analysis");
        Path validationDir = workspace.resolve("validation");
        Files.createDirectories(templateDir);
        Files.createDirectories(contentDir);
        Files.createDirectories(exportsDir);
        Files.createDirectories(analysisDir);
        Files.createDirectories(validationDir);

        Files.writeString(templateDir.resolve("template.pptx"), TEMPLATE_SECRET);
        Files.writeString(contentDir.resolve("notes.md"), CONTENT_SECRET);
        Files.writeString(exportsDir.resolve("out.pptx"), EXPORT_SECRET);
        Files.writeString(analysisDir.resolve("fill_plan.json"), """
                {"schema":"template_fill_pptx_plan.v1","status":"confirmed","slides":[
                {"source_slide":1,"purpose":"%s","notes":"speaker secret"}]}
                """.formatted(PLAN_SECRET));
        Files.writeString(validationDir.resolve("template-fill-readback.json"), """
                {
                  "schema":"template_fill_readback.v1",
                  "status":"PASSED_WITH_WARNINGS",
                  "planVersion":1,
                  "planDigest":"abc123",
                  "exportFileName":"out.pptx",
                  "exportFileHash":"deadbeef",
                  "warnings":[{"code":"animation_semantics_unverified","message":"%s"}],
                  "errors":[]
                }
                """.formatted("/home/secret/export/out.pptx"));
    }

    private void seedAllowedArtifacts(Path workspace, PptJob job) throws Exception {
        new TemplateFillLifecycleStore(properties).initialize(job);
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                workspace.resolve("analysis/template.slide_library.json"));
        Files.writeString(workspace.resolve("analysis/fill_plan.meta.json"), """
                {"version":2,"digest":"meta-digest-123","status":"confirmed"}
                """);
        Files.copy(getClass().getResourceAsStream("/template-fill/fill-plan.service-meta.stage4.json"),
                workspace.resolve("analysis/fill_plan.service-meta.json"));
        Files.writeString(workspace.resolve("analysis/check_report.json"), """
                {"summary":{"warn":1,"error":0,"info":0}}
                """);
        job.updateTemplateAnalysis(new TemplateFillAnalysisSummary(4, 1920, 1080, "1920x1080", 8, 1, 1, "v1"));
        job.addEvent(PptJobEvent.of(PptJobEventType.JOB_ACCEPTED, "job accepted for template fill"));
    }
}
