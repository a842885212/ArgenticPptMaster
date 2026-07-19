package domi.argenticpptmaster.domain;

import java.util.List;

/**
 * PPT 生成任务中的可恢复业务节点。
 * <p>
 * 每个节点代表一个稳定、可判定完成条件的业务里程碑，用于在任务异常失败时
 * 从最近一个成功节点继续执行，而不是从头重跑整个流程。
 * </p>
 * <p>
 * 节点按执行顺序排列，不同 {@link PptWorkflowMode} 适用不同节点集合：
 * </p>
 * <ul>
 *   <li>{@link PptWorkflowMode#BASIC} 使用通用节点</li>
 *   <li>{@link PptWorkflowMode#IMAGE_ENHANCED} 在 SPEC_LOCK_WRITTEN 与 NOTES_TOTAL_WRITTEN 之间插入图片阶段节点</li>
 * </ul>
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
public enum PptJobNode {

    /**
     * 项目已初始化且源文件已导入。
     * <p>
     * 证据：项目目录存在且 {@code sources/} 目录下已存在导入的文件。
     * </p>
     */
    PROJECT_READY(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 逐页大纲已生成并进入人工确认，但尚未锁定。
     */
    OUTLINE_DRAFTED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, true),

    /**
     * 逐页大纲已获得人工批准，可作为后续产物的依据。
     */
    OUTLINE_CONFIRMED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, true),

    /**
     * 历史整体执行计划已获得人工确认。
     * <p>
     * 仅用于兼容历史 {@code plan_confirmation} 载荷；新的逐页大纲流程使用
     * {@link #OUTLINE_CONFIRMED}。
     * </p>
     */
    PLAN_CONFIRMED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, true),

    /**
     * 设计规格文档已写入。
     * <p>
     * 证据：项目根目录下 {@code design_spec.md} 存在且非空。
     * </p>
     */
    DESIGN_SPEC_WRITTEN(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 规格锁定文档已写入。
     * <p>
     * 证据：项目根目录下 {@code spec_lock.md} 存在且非空。
     * </p>
     */
    SPEC_LOCK_WRITTEN(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 图片生成清单已写入（仅文生图流程）。
     * <p>
     * 证据：项目根目录下 {@code images/image_prompts.json} 存在且非空。
     * </p>
     */
    IMAGES_MANIFEST_WRITTEN(PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 图片生成清单已由用户确认，允许执行图片生成（仅文生图流程）。
     */
    IMAGE_MANIFEST_CONFIRMED(PptWorkflowMode.IMAGE_ENHANCED, true),

    /**
     * 图片已全部生成且状态满足继续条件（仅文生图流程）。
     * <p>
     * 第一版要求所有图片 item 的状态均为 {@code Generated}，无 Failed。
     * </p>
     */
    IMAGES_GENERATED(PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 图片阶段完成后，用户已确认继续后续 PPT 制作（仅文生图流程）。
     * <p>
     * 通过 {@code request_plan_confirmation} 工具（stage 为 image_ready_continue_confirmation）暂停，
     * 用户批准后进入此节点。
     * </p>
     */
    IMAGE_CONTINUE_CONFIRMED(PptWorkflowMode.IMAGE_ENHANCED, true),

    /**
     * 演讲者备注总文件已写入。
     * <p>
     * 证据：项目根目录下 {@code notes/total.md} 存在且非空。
     * </p>
     */
    NOTES_TOTAL_WRITTEN(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * SVG 输出已生成并通过质量校验。
     * <p>
     * 证据：{@code validate_svg_output} 工具成功执行，且 {@code svg_output/} 目录非空。
     * </p>
     */
    SVG_OUTPUT_VALIDATED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * 演讲者备注已拆分为每页独立文件。
     * <p>
     * 证据：{@code split_speaker_notes} 工具成功执行。
     * </p>
     */
    SPEAKER_NOTES_SPLIT(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * SVG 已最终处理为 svg_final。
     * <p>
     * 证据：{@code finalize_project_svg} 工具成功执行，且 {@code svg_final/} 目录非空。
     * </p>
     */
    SVG_FINALIZED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false),

    /**
     * PPTX 成品已导出。
     * <p>
     * 证据：{@code export_project_pptx} 工具成功执行，且导出文件真实存在。
     * </p>
     */
    PPT_EXPORTED(PptWorkflowMode.BASIC, PptWorkflowMode.IMAGE_ENHANCED, false);

    private final List<PptWorkflowMode> applicableModes;
    private final boolean confirmation;

    PptJobNode(PptWorkflowMode singleMode, boolean confirmation) {
        this(List.of(singleMode), confirmation);
    }

    PptJobNode(PptWorkflowMode first, PptWorkflowMode second, boolean confirmation) {
        this(List.of(first, second), confirmation);
    }

    PptJobNode(List<PptWorkflowMode> applicableModes, boolean confirmation) {
        this.applicableModes = applicableModes;
        this.confirmation = confirmation;
    }

    /**
     * 判断该节点是否适用于指定工作流模式。
     *
     * @param mode 工作流模式
     * @return true 表示该节点适用于此模式
     */
    public boolean applicableTo(PptWorkflowMode mode) {
        return applicableModes.contains(mode);
    }

    /**
     * 判断该节点是否需要人工确认。
     * <p>
     * 需要人工确认的节点不能通过普通工具调用推进，必须由用户通过 {@code /confirm} 接口批准后推进。
     * </p>
     *
     * @return true 表示需要人工确认
     */
    public boolean requiresConfirmation() {
        return confirmation;
    }
}
