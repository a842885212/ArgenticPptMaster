package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptConfirmationMode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 将 AUTO 模式下的一次性确认门禁应用为与人工 APPROVE 等价的服务端副作用。
 */
@Component
public class PptAutoConfirmationApplier {

    private final PptOutlineStore outlineStore = new PptOutlineStore();
    private final PptTemplateFillPlanStore templateFillPlanStore;
    private final PptTemplateFillAsyncRunner templateFillAsyncRunner;
    private final PptWorkflowEvents events;

    public PptAutoConfirmationApplier(
            PptTemplateFillPlanStore templateFillPlanStore,
            @Lazy PptTemplateFillAsyncRunner templateFillAsyncRunner,
            PptWorkflowEvents events) {
        this.templateFillPlanStore = templateFillPlanStore;
        this.templateFillAsyncRunner = templateFillAsyncRunner;
        this.events = events;
    }

    /**
     * @return true 表示已交接给 template-fill 执行器，调用方不应再把工具结果回填给 Agent
     */
    public boolean applyOneShotApproval(PptJob job, String stage, Map<String, Object> contextData) {
        if (job.confirmationMode() != PptConfirmationMode.AUTO) {
            throw new IllegalStateException("auto approval requires confirmationMode=AUTO");
        }
        String confirmationId = "auto-" + UUID.randomUUID();
        return switch (stage) {
            case "outline_confirmation", "plan_confirmation" -> {
                applyOutlineApproval(job, stage, contextData, confirmationId);
                yield false;
            }
            case "image_manifest_confirmation" -> {
                completeConfirmedNode(job, PptJobNode.IMAGE_MANIFEST_CONFIRMED, confirmationId, stage);
                yield false;
            }
            case "image_ready_continue_confirmation" -> {
                completeConfirmedNode(job, PptJobNode.IMAGE_CONTINUE_CONFIRMED, confirmationId, stage);
                yield false;
            }
            case "template_fill_plan" -> {
                applyTemplateFillApproval(job, contextData, confirmationId);
                yield true;
            }
            default -> throw new IllegalStateException("unsupported AUTO one-shot stage: " + stage);
        };
    }

    private void applyOutlineApproval(
            PptJob job, String stage, Map<String, Object> contextData, String confirmationId) {
        Integer version = positiveInt(contextData.get("version"));
        if (version == null) {
            throw new IllegalStateException("outline auto approval requires positive version");
        }
        PptOneShotApprovalEffects.lockOutlineAtVersion(outlineStore, job, version);
        PptOneShotApprovalEffects.completeOutlineDraftedIfNeeded(
                job, Map.of("confirmed", true, "actor", "system"));
        PptJobNode node = "plan_confirmation".equals(stage)
                ? PptJobNode.PLAN_CONFIRMED
                : PptJobNode.OUTLINE_CONFIRMED;
        completeConfirmedNode(job, node, confirmationId, stage);
    }

    private void applyTemplateFillApproval(PptJob job, Map<String, Object> contextData, String confirmationId) {
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new IllegalStateException("template_fill_plan auto approval requires TEMPLATE_FILL");
        }
        Integer version = positiveInt(contextData.get("version"));
        if (version == null) {
            throw new IllegalStateException("template fill plan version is invalid");
        }
        Object digestValue = contextData.get("digest");
        if (!(digestValue instanceof String digest) || digest.isBlank()) {
            throw new IllegalStateException("template fill plan digest is invalid");
        }
        Path planPath = PptOneShotApprovalEffects.confirmTemplateFillDraft(
                templateFillPlanStore, job, confirmationId, version, digest);
        PptConfirmation confirmation = new PptConfirmation(
                confirmationId,
                true,
                Map.of("actor", "system", "confirmationMode", "AUTO"),
                null,
                Instant.now(),
                PptConfirmationAction.APPROVE,
                null,
                List.of(),
                null,
                List.of());
        job.receiveConfirmation(confirmation);
        events.record(job, PptJobEvent.of(
                PptJobEventType.CONFIRMATION_RECEIVED,
                "auto confirmation approved",
                Map.of(
                        "confirmationId", confirmationId,
                        "confirmedNode", PptJobNode.FILL_PLAN_CONFIRMED.name(),
                        "action", PptConfirmationAction.APPROVE.name(),
                        "actor", "system",
                        "confirmationMode", "AUTO",
                        "stage", "template_fill_plan")));
        PptOneShotApprovalEffects.claimTemplateFillExecution(job);
        events.record(job, PptJobEvent.of(
                PptJobEventType.TEMPLATE_FILL_PLAN_ACCEPTED,
                "template fill plan auto-approved",
                Map.of("planVersion", version, "actor", "system", "confirmationMode", "AUTO")));
        templateFillAsyncRunner.start(job.id(), planPath);
    }

    private void completeConfirmedNode(PptJob job, PptJobNode node, String confirmationId, String stage) {
        job.confirmNode(node);
        PptConfirmation confirmation = new PptConfirmation(
                confirmationId,
                true,
                Map.of("actor", "system", "confirmationMode", "AUTO"),
                null,
                Instant.now(),
                PptConfirmationAction.APPROVE,
                null,
                List.of(),
                null,
                List.of());
        job.receiveConfirmation(confirmation);
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("confirmationId", confirmationId);
        eventData.put("confirmedNode", node.name());
        eventData.put("action", PptConfirmationAction.APPROVE.name());
        eventData.put("actor", "system");
        eventData.put("confirmationMode", "AUTO");
        eventData.put("stage", stage);
        events.record(job, PptJobEvent.of(
                PptJobEventType.CONFIRMATION_RECEIVED, "auto confirmation approved", eventData));
    }

    private static Integer positiveInt(Object value) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() <= 0) {
            return null;
        }
        return number.intValue();
    }
}
