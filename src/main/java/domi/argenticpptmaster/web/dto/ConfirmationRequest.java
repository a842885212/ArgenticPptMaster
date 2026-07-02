package domi.argenticpptmaster.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * PPT 生成结果确认请求 DTO。
 * <p>
 * 用户通过此请求体确认或拒绝 Agent 生成的 PPT 结果，
 * 并可附带问答答案和评论信息。
 * </p>
 *
 * @param confirmationId 确认 ID，用于标识本次确认操作对应的确认项（不能为空）
 * @param approved       是否批准生成的 PPT 结果；{@code true} 表示批准，{@code false} 表示拒绝
 * @param answers        可选的结构化问答答案，键为问题标识，值为用户回答
 * @param comment        可选的用户评论或反馈意见
 */
public record ConfirmationRequest(
        @NotBlank String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment) {
}
