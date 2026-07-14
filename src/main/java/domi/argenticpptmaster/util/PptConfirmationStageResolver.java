package domi.argenticpptmaster.util;

import domi.argenticpptmaster.domain.PptJobNode;
import java.util.Map;

/**
 * 根据确认载荷中的 stage 字段解析对应的业务节点。
 *
 * <p>
 * 用于在用户通过 {@code /confirm} 接口批准确认后，推进对应的确认类节点状态。
 * </p>
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
public final class PptConfirmationStageResolver {

    private PptConfirmationStageResolver() {
        // 工具类，禁止实例化
    }

    /**
     * 从确认载荷中解析被确认的节点。
     * <p>
     * 注意：{@code image_retry_decision} 只表示用户决定如何处理图片失败分支，
     * 并不推进任何 checkpoint 节点，因此返回 {@code null}，避免把 {@code lastCompletedNode}
     * 错误回退到 {@link PptJobNode#PLAN_CONFIRMED}。
     * </p>
     *
     * @param confirmationPayload 确认载荷
     * @return 对应的业务节点；若确认仅影响当前分支决策而不推进节点，则返回 null
     */
    public static PptJobNode resolveConfirmedNode(Map<String, Object> confirmationPayload) {
        if (confirmationPayload == null) {
            return PptJobNode.PLAN_CONFIRMED;
        }
        Object stage = confirmationPayload.get("stage");
        if (!(stage instanceof String stageStr)) {
            return PptJobNode.PLAN_CONFIRMED;
        }
        return switch (stageStr) {
            case "image_ready_continue_confirmation" -> PptJobNode.IMAGE_CONTINUE_CONFIRMED;
            case "image_retry_decision" -> null;
            case "outline_confirmation" -> PptJobNode.OUTLINE_CONFIRMED;
            case "plan_confirmation" -> PptJobNode.PLAN_CONFIRMED;
            default -> PptJobNode.PLAN_CONFIRMED;
        };
    }
}
