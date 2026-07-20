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
 *   <li>{@link #TEMPLATE_FILL} —— 使用原生 PPTX 模板和已确认填充计划直接生成可编辑 PPTX。</li>
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
    IMAGE_ENHANCED,

    /**
     * 原生 PPTX 模板填充流程：不经过 Agent 或 SVG 导出链路。
     */
    TEMPLATE_FILL;

    /**
     * 将字符串规范化为工作流模式枚举。
     * <p>
     * 空值默认返回 {@link #BASIC}，无法识别的非空值会被拒绝，避免请求意图被静默改变。
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
            case "template-fill", "template_fill" -> TEMPLATE_FILL;
            case "basic", "default" -> BASIC;
            default -> throw new IllegalArgumentException("unsupported workflow mode: " + value);
        };
    }
}
