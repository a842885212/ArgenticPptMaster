package domi.argenticpptmaster.util;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJobNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PptConfirmationStageResolver} 的单元测试。
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
class PptConfirmationStageResolverTests {

    /**
     * 验证无 stage 时默认解析为 PLAN_CONFIRMED。
     */
    @Test
    void resolvesPlanConfirmedByDefault() {
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(Map.of()))
                .isEqualTo(PptJobNode.PLAN_CONFIRMED);
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(null))
                .isEqualTo(PptJobNode.PLAN_CONFIRMED);
    }

    /**
     * 验证图片继续确认 stage 解析为 IMAGE_CONTINUE_CONFIRMED。
     */
    @Test
    void resolvesImageContinueConfirmedStage() {
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(
                Map.of("stage", "image_ready_continue_confirmation")))
                .isEqualTo(PptJobNode.IMAGE_CONTINUE_CONFIRMED);
    }

    @Test
    void resolvesImageManifestConfirmationStage() {
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(
                Map.of("stage", "image_manifest_confirmation")))
                .isEqualTo(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
    }

    @Test
    void resolvesOutlineConfirmationStage() {
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(
                Map.of("stage", "outline_confirmation")))
                .isEqualTo(PptJobNode.OUTLINE_CONFIRMED);
    }

    /**
     * 验证图片重试决策 stage 不推进任何 checkpoint 节点，返回 null。
     */
    @Test
    void resolvesImageRetryDecisionAsNoCheckpointAdvance() {
        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(
                Map.of("stage", "image_retry_decision")))
                .isNull();
    }
}
