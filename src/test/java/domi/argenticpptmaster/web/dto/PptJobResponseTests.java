package domi.argenticpptmaster.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptTemplateFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptJobResponseTests {

    @Test
    void hidesDownloadInfoUntilJobCompleted() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        job.complete(Path.of("var/ppt-master/jobs/demo/exports/demo.pptx"));
        job.requireConfirmation("confirm-again", java.util.Map.of("stage", "manual"));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.artifactReady()).isFalse();
        assertThat(response.downloadUrl()).isNull();
    }

    /**
     * 验证失败任务的响应中，恢复相关字段正确暴露且节点状态反映失败位置。
     */
    @Test
    void exposesResumeStateForFailedJob() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));
        job.startNode(PptJobNode.PROJECT_READY);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.startNode(PptJobNode.PLAN_CONFIRMED);
        job.failNode(PptJobNode.PLAN_CONFIRMED, "plan rejected by operator");

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.FAILED);
        assertThat(response.workflowMode()).isEqualTo("BASIC");
        assertThat(response.lastCompletedNode()).isEqualTo(PptJobNode.PROJECT_READY);
        assertThat(response.lastFailureNode()).isEqualTo(PptJobNode.PLAN_CONFIRMED);
        assertThat(response.resumeCount()).isEqualTo(0);
        assertThat(response.resumable()).isTrue();
        assertThat(response.currentNode()).isNull();
        assertThat(response.errorMessage()).contains("plan rejected by operator");
        assertThat(response.nodeStates()).containsKey("PROJECT_READY");
        assertThat(response.nodeStates().get("PROJECT_READY").status()).isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(response.nodeStates().get("PLAN_CONFIRMED").status()).isEqualTo(PptJobNodeStatus.FAILED);
    }

    /**
     * 验证等待确认任务的响应中，currentNode 与 confirmationPayload 正确映射。
     */
    @Test
    void mapsWaitingConfirmationState() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.IMAGE_ENHANCED,
                Path.of("var/ppt-master/jobs/demo"));
        job.startNode(PptJobNode.PLAN_CONFIRMED);
        job.waitNodeConfirmation(PptJobNode.PLAN_CONFIRMED);
        job.requireConfirmation("confirm-1", Map.of("stage", "image_retry_decision"));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.WAITING_CONFIRMATION);
        assertThat(response.workflowMode()).isEqualTo("IMAGE_ENHANCED");
        assertThat(response.currentNode()).isEqualTo(PptJobNode.PLAN_CONFIRMED);
        assertThat(response.currentConfirmationId()).isEqualTo("confirm-1");
        assertThat(response.confirmationPayload()).containsEntry("stage", "image_retry_decision");
        assertThat(response.resumable()).isFalse();
        assertThat(response.nodeStates()).containsKey("IMAGES_GENERATED");
        assertThat(response.nodeStates().get("PLAN_CONFIRMED").status())
                .isEqualTo(PptJobNodeStatus.WAITING_CONFIRMATION);
    }

    /**
     * 验证已完成任务的响应中，下载地址和 artifactReady 正确暴露。
     */
    @Test
    void exposesDownloadUrlForCompletedJob() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));
        Path exportPath = Path.of("var/ppt-master/jobs/demo/exports/demo.pptx");
        job.complete(exportPath);

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.artifactReady()).isTrue();
        assertThat(response.downloadUrl()).isEqualTo("/api/ppt-jobs/" + job.id() + "/download");
        assertThat(response.resumable()).isFalse();
        assertThat(response.lastCompletedNode()).isNull();
    }

    @Test
    void exposesOutlineReviewDiffAndImpactPreview() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));
        job.requireConfirmation("outline-1", Map.of(
                "stage", "outline_confirmation",
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "version", 2,
                        "locked", true,
                        "slides", java.util.List.of(Map.of("slideNo", 1)),
                        "diff", Map.of("fromVersion", 1, "toVersion", 2),
                        "impactPreview", Map.of("revisionImpactToken", "impact-token"))));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.outlineVersion()).isEqualTo(2);
        assertThat(response.outlineLocked()).isTrue();
        assertThat(response.outlineSlideCount()).isEqualTo(1);
        assertThat(response.outlineDiff()).containsEntry("toVersion", 2);
        assertThat(response.impactPreview()).containsEntry("revisionImpactToken", "impact-token");
    }

    @Test
    void exposesTemplateAndContentSourcesWithoutStoredPaths() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL,
                Path.of("/private/workspace/job"));
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 20L,
                Path.of("/private/workspace/job/uploads/template/0-brand.pptx")));
        job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", 10L,
                Path.of("/private/workspace/job/uploads/content/0-source.pptx")));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.template().originalName()).isEqualTo("brand.pptx");
        assertThat(response.contentSources()).singleElement()
                .extracting(SourceFileResponse::originalName).isEqualTo("source.pptx");
        assertThat(response.toString()).doesNotContain("/private/workspace");
    }

    @Test
    void exposesTemplateFillProgressWithoutAbsolutePaths() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL,
                Path.of("/private/workspace/job"));
        job.updateTemplateAnalysis(new domi.argenticpptmaster.domain.TemplateFillAnalysisSummary(
                10, 1920, 1080, "1920x1080", 3, 1, 0, "template_fill_pptx_library.v1"));
        job.updateFillPlanStatus(domi.argenticpptmaster.domain.FillPlanStatus.VALIDATED, 5, 2, 0);

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.templateAnalysisReady()).isTrue();
        assertThat(response.fillPlanStatus()).isEqualTo("VALIDATED");
        assertThat(response.templateFillProgress()).isNotNull();
        assertThat(response.templateFillProgress().templateSlideCount()).isEqualTo(10);
        assertThat(response.templateFillProgress().planSlideCount()).isEqualTo(5);
        assertThat(response.templateFillProgress().validationWarningCount()).isEqualTo(2);
        assertThat(response.toString()).doesNotContain("/private/workspace");
    }

    @Test
    void templateFillConfirmationPayloadKeepsSummariesWithoutAbsolutePaths() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL,
                Path.of("/private/workspace/job"));
        job.requireConfirmation("tf-1", Map.of(
                "stage", "template_fill_plan",
                "contextData", Map.of(
                        "type", "template_fill_plan",
                        "version", 2,
                        "digest", "abc",
                        "pages", java.util.List.of(Map.of(
                                "outputOrder", 1,
                                "templateSlideIndex", 1,
                                "preview", "bounded")))));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.confirmationPayload()).containsEntry("stage", "template_fill_plan");
        assertThat(response.toString()).doesNotContain("/private/workspace");
        assertThat(response.events()).isEmpty();
    }

    @Test
    void exposesStage4TemplateFillProgressAggregatesWithoutSensitiveText() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL,
                Path.of("var/ppt-master/jobs/demo"));
        job.updateNativePlanAggregates(2, 1, 1, 3, 0, "VALID");
        job.updateReadbackValidation("PASSED_WITH_WARNINGS", 1, 0);
        job.complete(Path.of("var/ppt-master/jobs/demo/exports/demo.pptx"));
        // Force completed status for download gate
        job.completeNode(PptJobNode.OUTPUT_VALIDATED, Map.of());

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.templateFillProgress()).isNotNull();
        assertThat(response.templateFillProgress().notesMappingCount()).isEqualTo(2);
        assertThat(response.templateFillProgress().tableMappingCount()).isEqualTo(1);
        assertThat(response.templateFillProgress().chartMappingCount()).isEqualTo(1);
        assertThat(response.templateFillProgress().capacityRiskCount()).isEqualTo(3);
        assertThat(response.templateFillProgress().constraintValidationStatus()).isEqualTo("VALID");
        assertThat(response.templateFillProgress().readbackValidationStatus()).isEqualTo("PASSED_WITH_WARNINGS");
        assertThat(response.toString()).doesNotContain("speaker notes secret");
    }

    @Test
    void basicJobHasNullTemplateFillFields() {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.templateAnalysisReady()).isFalse();
        assertThat(response.fillPlanStatus()).isNull();
        assertThat(response.templateFillProgress()).isNull();
    }
}
