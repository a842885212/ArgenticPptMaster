package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link PptJob} 节点 checkpoint 状态的单元测试。
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
class PptJobCheckpointTests {

    /**
     * 验证新任务初始化后，当前模式适用的节点均已创建 PENDING 执行记录。
     */
    @Test
    void newJobInitializesApplicableNodesAsPending() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        assertThat(job.nodeExecution(PptJobNode.PROJECT_READY).status()).isEqualTo(PptJobNodeStatus.PENDING);
        assertThat(job.nodeExecution(PptJobNode.PLAN_CONFIRMED).status()).isEqualTo(PptJobNodeStatus.PENDING);
        assertThat(job.nodeExecution(PptJobNode.DESIGN_SPEC_WRITTEN).status()).isEqualTo(PptJobNodeStatus.PENDING);
        assertThat(job.nodeExecution(PptJobNode.IMAGES_MANIFEST_WRITTEN)).isNull();
    }

    /**
     * 验证节点完成后 lastCompletedNode 会更新，且当前节点会被清空。
     */
    @Test
    void completingNodeUpdatesCheckpoint() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        job.startNode(PptJobNode.PROJECT_READY);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of("sourcesDir", "sources"));

        assertThat(job.lastCompletedNode()).contains(PptJobNode.PROJECT_READY);
        assertThat(job.currentNode()).isEmpty();
        assertThat(job.nodeExecution(PptJobNode.PROJECT_READY).status()).isEqualTo(PptJobNodeStatus.COMPLETED);
    }

    /**
     * 验证节点失败会标记 lastFailureNode 并切换任务状态为 FAILED。
     */
    @Test
    void failingNodeMarksJobFailed() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        job.startNode(PptJobNode.SVG_OUTPUT_VALIDATED);
        job.failNode(PptJobNode.SVG_OUTPUT_VALIDATED, "svg validation failed");

        assertThat(job.status()).isEqualTo(PptJobStatus.FAILED);
        assertThat(job.lastFailureNode()).contains(PptJobNode.SVG_OUTPUT_VALIDATED);
        assertThat(job.errorMessage()).contains("svg validation failed");
    }

    /**
     * 验证新恢复尝试会切换 activeAttemptSessionId 并更新 resumeCount。
     */
    @Test
    void newResumeAttemptUpdatesSessionAndCount() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        String initialSession = job.activeAttemptSessionId();
        job.failNode(PptJobNode.SVG_OUTPUT_VALIDATED, "svg validation failed");

        job.startNewResumeAttempt();

        assertThat(job.activeAttemptSessionId()).isNotEqualTo(initialSession);
        assertThat(job.resumeCount()).isEqualTo(1);
        assertThat(job.status()).isEqualTo(PptJobStatus.RUNNING_AGENT);
        assertThat(job.lastFailureNode()).isEmpty();
    }

    /**
     * 验证只有 FAILED 状态且存在 lastCompletedNode 时才可恢复。
     */
    @Test
    void resumableOnlyWhenFailedWithCompletedNode() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));

        assertThat(job.resumable()).isFalse();
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.failNode(PptJobNode.SVG_OUTPUT_VALIDATED, "failed");

        assertThat(job.resumable()).isTrue();
    }
}
