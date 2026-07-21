package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillConfirmationSummaryTests {

    @TempDir
    Path tempDir;

    @Test
    void buildsBoundedSummaryWithoutSensitiveText() throws Exception {
        Path workspace = tempDir.resolve("job");
        Path analysis = workspace.resolve("analysis");
        Files.createDirectories(analysis);
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                analysis.resolve("template.slide_library.json"));
        String draft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));

        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.assignTemplateConstraints(new TemplateFillConstraints(
                List.of(1, 2), List.of(), true, false, 5));
        PptTemplateFillPlanStore store = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        TemplateFillPlanMetadata metadata = store.storeDraftPlan(
                job, draft, analysis.resolve("template.slide_library.json"));

        Map<String, Object> summary = TemplateFillConfirmationSummary.fromWorkspace(job, Map.of(
                "type", "template_fill_plan",
                "version", metadata.version(),
                "digest", "agent-provided-stale",
                "pages", List.of(Map.of("secretNotes", "should not leak"))));

        assertThat(summary.get("type")).isEqualTo("template_fill_plan");
        assertThat(summary.get("version")).isEqualTo(metadata.version());
        assertThat(summary.get("digest")).isEqualTo(metadata.digest());
        assertThat(summary.get("digest")).isNotEqualTo("agent-provided-stale");
        assertThat(summary.toString()).doesNotContain("should not leak");
        assertThat(summary.toString()).doesNotContain("/home/");

        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) summary.get("constraintSatisfaction");
        assertThat(constraints.get("maxSlides")).isEqualTo(5);
        assertThat(constraints.get("preserveCover")).isEqualTo(true);
        assertThat(constraints.get("satisfied")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) summary.get("pages");
        assertThat(pages).isNotEmpty();
        assertThat(pages.get(0)).containsKeys(
                "sourceSlide", "purpose", "notesMappingCount", "tableMappingCount",
                "chartMappingCount", "transition", "slotIds");
        assertThat(pages.get(0).get("transition")).isEqualTo("fade");
    }

    @Test
    void truncatesOversizedPageListAndCapacityNotes() throws Exception {
        Path workspace = tempDir.resolve("job2");
        Files.createDirectories(workspace.resolve("analysis"));
        StringBuilder plan = new StringBuilder("""
                {"schema":"template_fill_pptx_plan.v1","status":"draft","source_pptx":"t.pptx","accepted_warnings":[],"slides":[
                """);
        for (int i = 1; i <= 45; i++) {
            if (i > 1) {
                plan.append(',');
            }
            plan.append("{\"source_slide\":1,\"purpose\":\"p\",\"replacements\":[],\"table_edits\":[],\"chart_edits\":[],\"transition\":\"fade\"}");
        }
        plan.append("]}");
        Files.writeString(workspace.resolve("analysis/fill_plan.json"), plan);
        Files.writeString(workspace.resolve("analysis/fill_plan.meta.json"),
                "{\"version\":1,\"digest\":\"abc\",\"status\":\"draft\"}");
        String longNote = "n".repeat(500);
        Files.writeString(workspace.resolve("analysis/fill_plan.service-meta.json"),
                "{\"schema\":\"template_fill_service_meta.v1\",\"version\":1,\"capacityDecisions\":["
                        + "{\"source_slide\":1,\"slot_id\":\"s1\",\"selected\":\"rewrite\","
                        + "\"evaluated\":[\"rewrite\"],\"note\":\"" + longNote + "\"}"
                        + "],\"fontAdjustments\":[],\"constraints\":{}}");

        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        Map<String, Object> summary = TemplateFillConfirmationSummary.fromWorkspace(job, Map.of());

        @SuppressWarnings("unchecked")
        List<?> pages = (List<?>) summary.get("pages");
        assertThat(pages).hasSize(TemplateFillConfirmationSummary.MAX_PAGES);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) summary.get("capacityRisks");
        assertThat(risks).hasSize(1);
        assertThat(((String) risks.get(0).get("note")).length())
                .isLessThanOrEqualTo(TemplateFillConfirmationSummary.MAX_NOTE_CHARS);
    }
}
