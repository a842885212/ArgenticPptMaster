package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptOutline;
import java.nio.file.Path;
import java.util.Map;

/**
 * 大纲/图片/模板填充一次性门禁 APPROVE 的共享副作用。
 * <p>
 * 供人工 {@code /confirm} 与 AUTO 自动批准共用，避免两套锁定/确认逻辑漂移。
 * </p>
 */
public final class PptOneShotApprovalEffects {

    private PptOneShotApprovalEffects() {
    }

    /** 按版本锁定未锁定大纲；已锁定则幂等跳过。 */
    public static void lockOutlineAtVersion(PptOutlineStore outlineStore, PptJob job, int version) {
        Path projectPath = job.projectPath()
                .orElseThrow(() -> new IllegalStateException("project path is missing"));
        PptOutline outline = outlineStore.read(projectPath);
        if (outline.version() != version) {
            throw new IllegalStateException("outline version does not match the current outline version");
        }
        if (!outline.locked()) {
            outlineStore.write(projectPath, outline.lock());
        }
    }

    /** 若当前节点仍是大纲草稿，则将其标记完成。 */
    public static void completeOutlineDraftedIfNeeded(PptJob job, Map<String, Object> summary) {
        if (job.currentNode().orElse(null) == PptJobNode.OUTLINE_DRAFTED) {
            job.completeNode(PptJobNode.OUTLINE_DRAFTED, summary == null ? Map.of() : summary);
        }
    }

    /**
     * 确认 template-fill draft 并完成 {@link PptJobNode#FILL_PLAN_CONFIRMED}。
     * <p>
     * 调用方应在 {@code receiveConfirmation} 之后再调用 {@link #claimTemplateFillExecution(PptJob)}。
     * </p>
     *
     * @return 已确认计划路径，供调用方启动异步执行
     */
    public static Path confirmTemplateFillDraft(
            PptTemplateFillPlanStore planStore,
            PptJob job,
            String confirmationId,
            int expectedVersion,
            String expectedDigest) {
        planStore.confirmCurrentDraft(job, confirmationId, expectedVersion, expectedDigest);
        job.confirmNode(PptJobNode.FILL_PLAN_CONFIRMED);
        return planStore.findConfirmedPlan(job)
                .orElseThrow(() -> new IllegalStateException("confirmed fill plan is missing"));
    }

    /** 领取 template-fill 原生执行权（须在确认接收之后调用）。 */
    public static void claimTemplateFillExecution(PptJob job) {
        if (!job.markTemplateFillExecutionStarted()) {
            throw new IllegalStateException("template-fill execution has already started");
        }
    }
}
