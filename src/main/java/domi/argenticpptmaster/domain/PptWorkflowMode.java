package domi.argenticpptmaster.domain;

/**
 * PPT 生成任务的工作流模式。
 * <p>
 * 用于显式区分用户选择的生成流程：
 * </p>
 * <ul>
 *   <li>{@link #BASIC} —— 基础流程，仅使用 markdown-first 路线生成 PPT，不引入 AI 图片生成。</li>
 *   <li>{@link #IMAGE_ENHANCED} —— 进阶流程，在基础流程之上增加图片阶段：
 *        生成 {@code images/image_prompts.json}，调用 {@code image_gen.py --manifest} 生成图片，
 *        再由 SVG 页面引用已生成的图片。</li>
 * </ul>
 */
public enum PptWorkflowMode {

    /**
     * 基础流程：不启用文生图，保持现有 markdown-first 行为。
     */
    BASIC,

    /**
     * 文生图进阶流程：在 Strategist 阶段之后插入图片生成阶段，再进入 SVG 生成与导出。
     */
    IMAGE_ENHANCED;

    /**
     * 将字符串规范化为工作流模式枚举。
     * <p>
     * 空值或无法识别时默认返回 {@link #BASIC}，保证现有接口兼容性。
     * </p>
     *
     * @param value 用户传入的模式字符串
     * @return 规范化后的工作流模式
     */
    public static PptWorkflowMode from(String value) {
        if (value == null || value.isBlank()) {
            return BASIC;
        }
        String normalized = value.trim().toLowerCase().replace("_", "-");
        return switch (normalized) {
            case "image-enhanced", "image_enhanced", "enhanced" -> IMAGE_ENHANCED;
            default -> BASIC;
        };
    }
}
