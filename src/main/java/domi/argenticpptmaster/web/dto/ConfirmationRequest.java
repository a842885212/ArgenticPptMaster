package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptConfirmationAction;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
 * @param comment        可选的用户评论或反馈意见（兼容旧客户端）
 * @param action         明确的确认操作；为空时由 approved 兼容推导
 * @param overallComment 逐页大纲修订时的整体意见
 * @param slideEdits     逐页大纲修订意见
 * @param outlineVersion 结构化大纲版本（可选）
 * @param outlineEdits 结构化页级编辑操作
 * @param revisionImpactToken 锁定大纲再修订的影响确认令牌
 */
public record ConfirmationRequest(
        @NotBlank String confirmationId,
        boolean approved,
        Map<String, Object> answers,
        String comment,
        PptConfirmationAction action,
        String overallComment,
        List<PptSlideEditRequest> slideEdits,
        Integer outlineVersion,
        List<PptOutlineEditRequest> outlineEdits,
        String revisionImpactToken) {

    public ConfirmationRequest(String confirmationId, boolean approved, Map<String, Object> answers, String comment) {
        this(confirmationId, approved, answers, comment, null, null, List.of(), null, List.of(), null);
    }

    public ConfirmationRequest(String confirmationId, boolean approved, Map<String, Object> answers, String comment,
            PptConfirmationAction action, String overallComment, List<PptSlideEditRequest> slideEdits) {
        this(confirmationId, approved, answers, comment, action, overallComment, slideEdits, null, List.of(), null);
    }

    public ConfirmationRequest {
        slideEdits = slideEdits == null ? List.of() : List.copyOf(slideEdits);
        outlineEdits = outlineEdits == null ? List.of() : List.copyOf(outlineEdits);
    }
}
