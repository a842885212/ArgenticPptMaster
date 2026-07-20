package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.service.PptImageManifestStore;
import domi.argenticpptmaster.service.PptOutlineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 根据已持久化的工作流证据决定确认阶段是否需要再次等待用户。
 *
 * <p>该策略不修改任务状态。调用方只有在得到 {@link Disposition#WAIT_FOR_USER}
 * 后才可以创建确认；已完成的一次性阶段返回 {@link Disposition#AUTO_ACKNOWLEDGE}，
 * 非法顺序或版本冲突返回 {@link Disposition#REJECT}。</p>
 */
final class PptConfirmationStagePolicy {

    enum Disposition {
        WAIT_FOR_USER,
        AUTO_ACKNOWLEDGE,
        REJECT
    }

    record Decision(Disposition disposition, PptJobNode confirmedNode, String reason) {
        private static Decision waitFor(PptJobNode node) {
            return new Decision(Disposition.WAIT_FOR_USER, node, "confirmation is required");
        }

        private static Decision autoAcknowledge(PptJobNode node) {
            return new Decision(Disposition.AUTO_ACKNOWLEDGE, node, "stage is already approved");
        }

        private static Decision reject(PptJobNode node, String reason) {
            return new Decision(Disposition.REJECT, node, reason);
        }
    }

    private final PptOutlineStore outlineStore = new PptOutlineStore();
    private final PptImageManifestStore manifestStore = new PptImageManifestStore();

    Decision evaluate(PptJob job, String stage, Map<String, Object> contextData) {
        return switch (stage) {
            case "outline_confirmation" -> evaluateOutline(job, contextData);
            case "plan_confirmation" -> evaluatePlan(job);
            case "image_manifest_confirmation" -> evaluateImageManifest(job, contextData);
            case "image_ready_continue_confirmation" -> evaluateImageReady(job, contextData);
            case "image_retry_decision" -> evaluateImageRetry(job, contextData);
            default -> Decision.waitFor(null);
        };
    }

    private Decision evaluateOutline(PptJob job, Map<String, Object> contextData) {
        PptJobNode node = PptJobNode.OUTLINE_CONFIRMED;
        Integer incomingVersion = positiveVersion(contextData.get("version"));
        if (incomingVersion == null) {
            return Decision.reject(node, "outline_confirmation requires a positive version");
        }
        PptOutline outline;
        try {
            outline = readOutline(job);
        } catch (IllegalStateException ex) {
            return Decision.reject(node, ex.getMessage());
        }
        if (outline.version() != incomingVersion) {
            return Decision.reject(node, "outline version does not match the current outline version");
        }
        if (isCompleted(job, node)) {
            return outline.locked()
                    ? Decision.autoAcknowledge(node)
                    : Decision.reject(node, "completed outline confirmation requires a locked outline");
        }
        if (outline.locked()) {
            return Decision.reject(node, "locked outline cannot re-enter confirmation before explicit revision");
        }
        return Decision.waitFor(node);
    }

    private Decision evaluatePlan(PptJob job) {
        PptJobNode node = PptJobNode.PLAN_CONFIRMED;
        return isCompleted(job, node) ? Decision.autoAcknowledge(node) : Decision.waitFor(node);
    }

    private Decision evaluateImageManifest(PptJob job, Map<String, Object> contextData) {
        PptJobNode node = PptJobNode.IMAGE_MANIFEST_CONFIRMED;
        if (!isCompleted(job, PptJobNode.IMAGES_MANIFEST_WRITTEN)) {
            return Decision.reject(node, "image_manifest_confirmation requires completed IMAGES_MANIFEST_WRITTEN");
        }
        VersionedImageEvidence evidence = imageEvidence(job, contextData, node);
        if (evidence.rejection() != null) {
            return evidence.rejection();
        }
        return isCompleted(job, node) ? Decision.autoAcknowledge(node) : Decision.waitFor(node);
    }

    private Decision evaluateImageReady(PptJob job, Map<String, Object> contextData) {
        PptJobNode node = PptJobNode.IMAGE_CONTINUE_CONFIRMED;
        VersionedImageEvidence evidence = imageEvidence(job, contextData, node);
        if (evidence.rejection() != null) {
            return evidence.rejection();
        }
        if (!isCompleted(job, PptJobNode.IMAGES_GENERATED)) {
            return Decision.reject(node, "image_ready_continue_confirmation requires completed IMAGES_GENERATED");
        }
        if (!allImagesGenerated(evidence.manifest())) {
            return Decision.reject(node, "image_ready_continue_confirmation requires all manifest items Generated");
        }
        return isCompleted(job, node) ? Decision.autoAcknowledge(node) : Decision.waitFor(node);
    }

    private Decision evaluateImageRetry(PptJob job, Map<String, Object> contextData) {
        VersionedImageEvidence evidence = imageEvidence(job, contextData, null);
        if (evidence.rejection() != null) {
            return evidence.rejection();
        }
        if (!isCompleted(job, PptJobNode.IMAGE_MANIFEST_CONFIRMED)) {
            return Decision.reject(null, "image_retry_decision requires completed IMAGE_MANIFEST_CONFIRMED");
        }
        return hasFailedImage(evidence.manifest())
                ? Decision.waitFor(null)
                : Decision.reject(null, "image_retry_decision requires at least one Failed manifest item");
    }

    private VersionedImageEvidence imageEvidence(
            PptJob job, Map<String, Object> contextData, PptJobNode confirmedNode) {
        if (!isCompleted(job, PptJobNode.OUTLINE_CONFIRMED)) {
            return VersionedImageEvidence.rejected(
                    Decision.reject(confirmedNode, "image confirmation requires completed OUTLINE_CONFIRMED"));
        }
        PptOutline outline;
        Path projectPath;
        try {
            projectPath = projectPath(job);
            outline = outlineStore.read(projectPath);
        } catch (IllegalStateException ex) {
            return VersionedImageEvidence.rejected(Decision.reject(confirmedNode, ex.getMessage()));
        }
        if (!outline.locked()) {
            return VersionedImageEvidence.rejected(
                    Decision.reject(confirmedNode, "image confirmation requires a locked outline"));
        }
        Integer incomingVersion = optionalPositiveVersion(contextData.get("outlineVersion"));
        if (incomingVersion == null && contextData.containsKey("outlineVersion")) {
            return VersionedImageEvidence.rejected(
                    Decision.reject(confirmedNode, "image confirmation outlineVersion must be positive"));
        }
        if (incomingVersion != null && incomingVersion != outline.version()) {
            return VersionedImageEvidence.rejected(
                    Decision.reject(confirmedNode, "image confirmation outlineVersion does not match current outline"));
        }
        try {
            Map<String, Object> manifest = manifestStore.readForOutlineVersion(projectPath, outline.version());
            return new VersionedImageEvidence(manifest, null);
        } catch (IllegalStateException ex) {
            return VersionedImageEvidence.rejected(Decision.reject(confirmedNode, ex.getMessage()));
        }
    }

    private PptOutline readOutline(PptJob job) {
        Path projectPath = projectPath(job);
        if (!Files.isRegularFile(outlineStore.path(projectPath))) {
            throw new IllegalStateException("outline evidence is missing");
        }
        return outlineStore.read(projectPath);
    }

    private Path projectPath(PptJob job) {
        return job.projectPath().orElseThrow(() -> new IllegalStateException("project path is missing"));
    }

    private boolean isCompleted(PptJob job, PptJobNode node) {
        PptNodeExecution execution = job.nodeExecution(node);
        return execution != null && execution.status() == PptJobNodeStatus.COMPLETED;
    }

    private Integer positiveVersion(Object value) {
        Integer version = optionalPositiveVersion(value);
        return version != null && version > 0 ? version : null;
    }

    private Integer optionalPositiveVersion(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() <= 0) {
            return null;
        }
        return number.intValue();
    }

    private boolean hasFailedImage(Map<String, Object> manifest) {
        return manifestItems(manifest).stream()
                .anyMatch(item -> item instanceof Map<?, ?> map && "Failed".equals(map.get("status")));
    }

    private boolean allImagesGenerated(Map<String, Object> manifest) {
        List<?> items = manifestItems(manifest);
        return items.stream()
                .allMatch(item -> item instanceof Map<?, ?> map && "Generated".equals(map.get("status")));
    }

    private List<?> manifestItems(Map<String, Object> manifest) {
        Object items = manifest.get("items");
        return items instanceof List<?> list ? list : List.of();
    }

    private record VersionedImageEvidence(Map<String, Object> manifest, Decision rejection) {
        private static VersionedImageEvidence rejected(Decision decision) {
            return new VersionedImageEvidence(Map.of(), decision);
        }
    }
}
