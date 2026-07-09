package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PptJobNode} 的单元测试。
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
class PptJobNodeTests {

    /**
     * 验证基础流程节点适用于 BASIC 模式，但不适用于 IMAGE_ENHANCED 模式中的图片节点。
     */
    @Test
    void basicModeNodesApplicableOnlyToBasic() {
        assertThat(PptJobNode.PROJECT_READY.applicableTo(PptWorkflowMode.BASIC)).isTrue();
        assertThat(PptJobNode.DESIGN_SPEC_WRITTEN.applicableTo(PptWorkflowMode.BASIC)).isTrue();
        assertThat(PptJobNode.IMAGES_MANIFEST_WRITTEN.applicableTo(PptWorkflowMode.BASIC)).isFalse();
        assertThat(PptJobNode.IMAGES_GENERATED.applicableTo(PptWorkflowMode.BASIC)).isFalse();
    }

    /**
     * 验证文生图专用节点仅适用于 IMAGE_ENHANCED 模式。
     */
    @Test
    void imageEnhancedNodesApplicableOnlyToEnhanced() {
        assertThat(PptJobNode.IMAGES_MANIFEST_WRITTEN.applicableTo(PptWorkflowMode.IMAGE_ENHANCED)).isTrue();
        assertThat(PptJobNode.IMAGES_GENERATED.applicableTo(PptWorkflowMode.IMAGE_ENHANCED)).isTrue();
        assertThat(PptJobNode.IMAGE_CONTINUE_CONFIRMED.applicableTo(PptWorkflowMode.IMAGE_ENHANCED)).isTrue();
        assertThat(PptJobNode.IMAGES_MANIFEST_WRITTEN.applicableTo(PptWorkflowMode.BASIC)).isFalse();
    }

    /**
     * 验证需要人工确认的节点识别正确。
     */
    @Test
    void confirmationNodesIdentifiedCorrectly() {
        assertThat(PptJobNode.PLAN_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.IMAGE_CONTINUE_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.DESIGN_SPEC_WRITTEN.requiresConfirmation()).isFalse();
        assertThat(PptJobNode.PPT_EXPORTED.requiresConfirmation()).isFalse();
    }
}
