package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link PptWorkflowMode} 的单元测试。
 */
class PptWorkflowModeTests {

    @Test
    void blankOrNullDefaultsToBasic() {
        assertThat(PptWorkflowMode.from(null)).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(PptWorkflowMode.from("")).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(PptWorkflowMode.from("   ")).isEqualTo(PptWorkflowMode.BASIC);
    }

    @Test
    void basicVariantsResolveToBasic() {
        assertThat(PptWorkflowMode.from("basic")).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(PptWorkflowMode.from("BASIC")).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(PptWorkflowMode.from("default")).isEqualTo(PptWorkflowMode.BASIC);
    }

    @Test
    void imageEnhancedVariantsResolveToImageEnhanced() {
        assertThat(PptWorkflowMode.from("image-enhanced")).isEqualTo(PptWorkflowMode.IMAGE_ENHANCED);
        assertThat(PptWorkflowMode.from("image_enhanced")).isEqualTo(PptWorkflowMode.IMAGE_ENHANCED);
        assertThat(PptWorkflowMode.from("IMAGE-ENHANCED")).isEqualTo(PptWorkflowMode.IMAGE_ENHANCED);
        assertThat(PptWorkflowMode.from("enhanced")).isEqualTo(PptWorkflowMode.IMAGE_ENHANCED);
    }

    @Test
    void templateFillVariantsResolveToTemplateFill() {
        assertThat(PptWorkflowMode.from("template-fill")).isEqualTo(PptWorkflowMode.TEMPLATE_FILL);
        assertThat(PptWorkflowMode.from("TEMPLATE_FILL")).isEqualTo(PptWorkflowMode.TEMPLATE_FILL);
    }

    @Test
    void beautifyVariantsResolveToBeautifyPptx() {
        assertThat(PptWorkflowMode.from("beautify-pptx")).isEqualTo(PptWorkflowMode.BEAUTIFY_PPTX);
        assertThat(PptWorkflowMode.from("BEAUTIFY_PPTX")).isEqualTo(PptWorkflowMode.BEAUTIFY_PPTX);
    }

    @Test
    void nativeEnhanceVariantsResolveToNativeEnhancePptx() {
        assertThat(PptWorkflowMode.from("native-enhance-pptx")).isEqualTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX);
        assertThat(PptWorkflowMode.from("NATIVE_ENHANCE_PPTX")).isEqualTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX);
        assertThat(PptWorkflowMode.from("native_enhance_pptx")).isEqualTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX);
    }

    @Test
    void unknownNonBlankModeIsRejected() {
        assertThatThrownBy(() -> PptWorkflowMode.from("unsupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }
}
