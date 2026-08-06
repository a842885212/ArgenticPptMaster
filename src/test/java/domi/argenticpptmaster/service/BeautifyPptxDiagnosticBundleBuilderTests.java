package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxDiagnosticBundleBuilderTests {

    private static final String SOURCE_SECRET = "TOP-SECRET-SOURCE-PPTX";
    private static final String IMAGE_SECRET = "TOP-SECRET-IMAGE-BYTES";
    private static final String IDENTITY_SECRET = "TOP-SECRET-IDENTITY-PALETTE";
    private static final String INVENTORY_SECRET = "TOP-SECRET-INVENTORY-TEXT";
    private static final String PLAN_SECRET = "TOP-SECRET-BEAUTIFY-PLAN-BODY";
    private static final String SVG_SECRET = "TOP-SECRET-SVG-CONTENT";
    private static final String EXPORT_SECRET = "TOP-SECRET-EXPORT-PPTX";
    private static final String OWNER_SUBJECT = "owner-user-42";
    private static final String OWNER_TENANT = "tenant-secret";

    @TempDir
    Path tempDir;

    private BeautifyPptxDiagnosticBundleBuilder builder;
    private PptMasterProperties properties;
    private PptJob job;

    @BeforeEach
    void setUp() throws Exception {
        properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a");
        builder = new BeautifyPptxDiagnosticBundleBuilder(new BeautifyPptxLifecycleStore(properties));
        Path workspace = tempDir.resolve("job");
        Files.createDirectories(workspace);
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "beautify",
                PptWorkflowMode.BEAUTIFY_PPTX, workspace);
        job.assignOwnership(OWNER_SUBJECT, OWNER_TENANT);
        Path project = workspace.resolve("project");
        Files.createDirectories(project);
        job.prepareProject(project);
        seedSensitiveWorkspace(workspace, project);
        seedAllowedArtifacts(workspace, project, job);
    }

    @Test
    void bundleIncludesOnlyWhitelistedSummariesAndExcludesSensitiveArtifacts() throws Exception {
        BeautifyPptxDiagnosticBundleBuilder.DiagnosticBundle bundle = builder.build(job, properties);

        assertThat(bundle.path()).exists();
        assertThat(bundle.bytes().length).isLessThanOrEqualTo(BeautifyPptxDiagnosticBundleBuilder.MAX_ZIP_BYTES);

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
                "service/service-meta.json",
                "analysis/analysis-summary.json",
                "analysis/beautify_plan.meta.json",
                "validation/beautify-readback-summary.json",
                "events-summary.json");
        assertThat(entryNames).noneMatch(name -> name.contains("uploads/")
                || name.contains("exports/")
                || name.contains("images/")
                || name.contains("pages/")
                || name.contains("beautify_identity")
                || name.contains("beautify_inventory")
                || name.endsWith("beautify_plan.draft.json")
                || name.endsWith(".pptx")
                || name.endsWith(".svg")
                || name.contains("beautify-readback.json"));

        String payload = combined.toString();
        assertThat(payload).doesNotContain(
                SOURCE_SECRET,
                IMAGE_SECRET,
                IDENTITY_SECRET,
                INVENTORY_SECRET,
                PLAN_SECRET,
                SVG_SECRET,
                EXPORT_SECRET,
                OWNER_SUBJECT,
                OWNER_TENANT,
                "/home/",
                "uploads/source.pptx",
                "exports/out.pptx",
                "beautify_plan.draft.json");
        assertThat(payload).contains("beautify-pptx-readiness-marker-v1", "PASSED_WITH_WARNINGS", "JOB_ACCEPTED");
    }

    @Test
    void rejectsSymlinkedWhitelistCandidate() throws Exception {
        Path workspace = job.workspacePath();
        Path real = workspace.resolve("validation/beautify-readback.json");
        Files.delete(real);
        Files.createSymbolicLink(real, workspace.resolve("uploads/source.pptx"));

        assertThatThrownBy(() -> builder.build(job, properties))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void redactsAbsolutePathsInEventMessages() throws Exception {
        job.addEvent(PptJobEvent.of(
                PptJobEventType.JOB_FAILED,
                "failed at /home/operator/jobs/" + job.id() + "/analysis"));

        BeautifyPptxDiagnosticBundleBuilder.DiagnosticBundle bundle = builder.build(job, properties);
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

    private void seedSensitiveWorkspace(Path workspace, Path project) throws Exception {
        Path uploads = workspace.resolve("uploads");
        Path exports = workspace.resolve("exports");
        Path images = project.resolve("images");
        Path analysis = project.resolve("analysis");
        Path pages = project.resolve("pages");
        Path validation = workspace.resolve("validation");
        Files.createDirectories(uploads);
        Files.createDirectories(exports);
        Files.createDirectories(images);
        Files.createDirectories(analysis);
        Files.createDirectories(pages);
        Files.createDirectories(validation);

        Files.writeString(uploads.resolve("source.pptx"), SOURCE_SECRET);
        Files.writeString(images.resolve("logo.png"), IMAGE_SECRET);
        Files.writeString(analysis.resolve("beautify_identity.json"),
                "{\"palette\":\"" + IDENTITY_SECRET + "\"}");
        Files.writeString(analysis.resolve("beautify_inventory.json"),
                "{\"text\":\"" + INVENTORY_SECRET + "\"}");
        Files.writeString(analysis.resolve("beautify_plan.draft.json"), """
                {
                  "schema":"beautify_pptx_plan.v1",
                  "version":3,
                  "digest":"plan-digest-123",
                  "status":"draft",
                  "sourcePageCount":2,
                  "slides":[{"purpose":"%s"}]
                }
                """.formatted(PLAN_SECRET));
        Files.writeString(pages.resolve("page_001.svg"), SVG_SECRET);
        Files.writeString(exports.resolve("out.pptx"), EXPORT_SECRET);
        Files.writeString(validation.resolve("beautify-readback.json"), """
                {
                  "schema":"beautify_readback.v1",
                  "status":"PASSED_WITH_WARNINGS",
                  "planVersion":3,
                  "planDigest":"plan-digest-123",
                  "sourcePageCount":2,
                  "exportPageCount":2,
                  "warnings":[{"code":"dense_page","message":"%s"}],
                  "errors":[]
                }
                """.formatted("/home/secret/export/out.pptx"));
    }

    private void seedAllowedArtifacts(Path workspace, Path project, PptJob job) throws Exception {
        new BeautifyPptxLifecycleStore(properties).initialize(job);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of(
                "sourcePageCount", 2,
                "imageCount", 1,
                "tableCount", 0,
                "chartCount", 0,
                "complexObjectAlertCount", 1,
                "resolvedFormat", "ppt169",
                "imageManifestReady", true));
        job.addEvent(PptJobEvent.of(PptJobEventType.JOB_ACCEPTED, "job accepted for beautify"));
    }
}
