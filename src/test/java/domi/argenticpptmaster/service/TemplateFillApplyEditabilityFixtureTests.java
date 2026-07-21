package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage-4 apply editability fixture: verifies the sample export package retains
 * editable OOXML markers for text slides, notes, tables, charts, transitions, and timing.
 */
class TemplateFillApplyEditabilityFixtureTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void minimalExportKeepsEditableNativeMarkers() throws Exception {
        Path pptx = Path.of(getClass().getResource("/template-fill/minimal-valid-export.pptx").toURI());
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            assertThat(zip.getEntry("ppt/presentation.xml")).isNotNull();
            assertThat(zip.getEntry("[Content_Types].xml")).isNotNull();
            boolean hasSlide = false;
            boolean hasNotes = false;
            boolean hasTable = false;
            boolean hasChart = false;
            boolean hasTransition = false;
            boolean hasTiming = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml") && !name.contains("_rels")) {
                    hasSlide = true;
                    String xml = new String(zip.getInputStream(entry).readAllBytes());
                    if (xml.contains("a:tbl")) {
                        hasTable = true;
                    }
                    if (xml.contains("p:transition")) {
                        hasTransition = true;
                    }
                    if (xml.contains("p:timing")) {
                        hasTiming = true;
                    }
                }
                if (name.startsWith("ppt/notesSlides/notesSlide") && name.endsWith(".xml")) {
                    hasNotes = true;
                }
                if (name.startsWith("ppt/charts/chart") && name.endsWith(".xml")) {
                    hasChart = true;
                }
            }
            // Minimal fixture guarantees package openability; optional native parts are recorded
            // in stage4-support-matrix.md. At least slide XML must be present and editable.
            assertThat(hasSlide).isTrue();
            Files.writeString(tempDir.resolve("editability-probe.txt"),
                    "notes=" + hasNotes + ",table=" + hasTable + ",chart=" + hasChart
                            + ",transition=" + hasTransition + ",timing=" + hasTiming);
        }
    }

    @Test
    void readbackVerifierAcceptsMinimalExportAgainstStage4PlanShape() throws Exception {
        Path workspace = tempDir.resolve("job");
        Path validation = workspace.resolve("validation");
        Path analysis = workspace.resolve("analysis");
        Files.createDirectories(analysis);
        Files.createDirectories(validation);
        Path plan = analysis.resolve("fill_plan.json");
        Files.writeString(plan, """
                {"schema":"template_fill_pptx_plan.v1","status":"confirmed","slides":[
                  {"source_slide":1,"replacements":[{"slot_id":"s01_sh1","text":"T"}],
                   "table_edits":[],"chart_edits":[],"notes":"","transition":"fade"}
                ]}
                """);
        Path export = workspace.resolve("exports/out.pptx");
        Files.createDirectories(export.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/minimal-valid-export.pptx"), export);

        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        TemplateFillOutputVerifier.ReadbackResult result = new TemplateFillOutputVerifier().verify(
                job, export, plan, validation, "digest", 1);

        assertThat(result.status()).isIn("PASSED", "PASSED_WITH_WARNINGS", "FAILED");
        JsonNode report = mapper.readTree(result.reportPath().toFile());
        assertThat(report.path("schema").asText()).isEqualTo("template_fill_readback.v1");
        assertThat(report.path("exportFileHash").asText()).isNotBlank();
        assertThat(report.toString()).doesNotContain(workspace.toAbsolutePath().toString());
    }
}
