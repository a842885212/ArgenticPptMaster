package domi.argenticpptmaster.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
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
}
