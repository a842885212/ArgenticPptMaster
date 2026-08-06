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
        assertThat(PptJobNode.OUTLINE_DRAFTED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.OUTLINE_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.IMAGE_CONTINUE_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.DESIGN_SPEC_WRITTEN.requiresConfirmation()).isFalse();
        assertThat(PptJobNode.PPT_EXPORTED.requiresConfirmation()).isFalse();
    }

    @Test
    void outlineNodesApplyToBothWorkflowModes() {
        assertThat(PptJobNode.OUTLINE_DRAFTED.applicableTo(PptWorkflowMode.BASIC)).isTrue();
        assertThat(PptJobNode.OUTLINE_DRAFTED.applicableTo(PptWorkflowMode.IMAGE_ENHANCED)).isTrue();
        assertThat(PptJobNode.OUTLINE_CONFIRMED.applicableTo(PptWorkflowMode.BASIC)).isTrue();
        assertThat(PptJobNode.OUTLINE_CONFIRMED.applicableTo(PptWorkflowMode.IMAGE_ENHANCED)).isTrue();
    }

    @Test
    void beautifyNodesAreIsolatedFromOutlineImageNotesAndTemplateFillNodes() {
        assertThat(PptJobNode.PROJECT_READY.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.BEAUTIFY_ANALYZED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.BEAUTIFY_PLAN_DRAFTED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.BEAUTIFY_PLAN_CONFIRMED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.DESIGN_SPEC_WRITTEN.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.PPT_EXPORTED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isTrue();
        assertThat(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.requiresConfirmation()).isFalse();
        assertThat(PptJobNode.OUTLINE_DRAFTED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isFalse();
        assertThat(PptJobNode.IMAGES_GENERATED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isFalse();
        assertThat(PptJobNode.NOTES_TOTAL_WRITTEN.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isFalse();
        assertThat(PptJobNode.TEMPLATE_ANALYZED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isFalse();
        assertThat(PptJobNode.OUTPUT_VALIDATED.applicableTo(PptWorkflowMode.BEAUTIFY_PPTX)).isFalse();
    }

    @Test
    void nativeEnhanceNodesAreIsolatedFromOtherWorkflowNodes() {
        assertThat(PptJobNode.PROJECT_READY.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.PPT_EXPORTED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.NATIVE_ENHANCE_INITIALIZED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.ENHANCEMENT_PLAN_DRAFTED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.NOTES_GENERATED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.AUDIO_VOICE_CONFIRMED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.AUDIO_VOICE_CONFIRMED.requiresConfirmation()).isTrue();
        assertThat(PptJobNode.NATIVE_ENHANCE_VALIDATED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();
        assertThat(PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isTrue();

        assertThat(PptJobNode.OUTLINE_DRAFTED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isFalse();
        assertThat(PptJobNode.TEMPLATE_ANALYZED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isFalse();
        assertThat(PptJobNode.BEAUTIFY_ANALYZED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isFalse();
        assertThat(PptJobNode.SVG_OUTPUT_VALIDATED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isFalse();
        assertThat(PptJobNode.OUTPUT_VALIDATED.applicableTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX)).isFalse();
    }
}
