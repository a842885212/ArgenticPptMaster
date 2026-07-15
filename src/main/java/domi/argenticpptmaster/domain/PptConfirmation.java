package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 用户对 PPT 执行方案的人工确认结果。
 * <p>
 * 当 AI 代理请求确认时，用户提交此记录表示批准或拒绝，
 * 并可附带补充答案和备注。
 * </p>
 *
 * @param confirmationId 确认请求的唯一标识，与 {@link PptJob#requireConfirmation} 传入的 ID 对应
 * @param approved       是否批准执行方案
 * @param answers        用户对代理提出问题的补充回答
 * @param comment        用户备注
 * @param confirmedAt    确认时间
 * @param action         用户明确的操作意图
 * @param overallComment 逐页大纲整体修改意见
 * @param slideEdits     逐页大纲修改意见
 * @param outlineVersion 结构化大纲版本
 * @param outlineEdits   结构化页级编辑
 */
public record PptConfirmation(
        String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment,
        Instant confirmedAt,
        PptConfirmationAction action,
        String overallComment,
        List<PptSlideEdit> slideEdits,
        Integer outlineVersion,
        List<PptOutlineEdit> outlineEdits) {

    public PptConfirmation(
            String confirmationId,
            boolean approved,
            Map<String, Object> answers,
            String comment,
            Instant confirmedAt) {
        this(confirmationId, approved, answers, comment, confirmedAt, null, comment, List.of(), null, List.of());
    }

    public PptConfirmation(String confirmationId, boolean approved, Map<String, Object> answers, String comment,
            Instant confirmedAt, PptConfirmationAction action, String overallComment, List<PptSlideEdit> slideEdits) {
        this(confirmationId, approved, answers, comment, confirmedAt, action, overallComment, slideEdits, null, List.of());
    }

    public PptConfirmation {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        slideEdits = slideEdits == null ? List.of() : List.copyOf(slideEdits);
        outlineEdits = outlineEdits == null ? List.of() : List.copyOf(outlineEdits);
    }
}
