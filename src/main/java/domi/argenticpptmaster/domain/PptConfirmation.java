package domi.argenticpptmaster.domain;

import java.time.Instant;
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
 */
public record PptConfirmation(
        String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment,
        Instant confirmedAt) {
}
