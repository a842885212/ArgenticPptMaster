package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.SlideOutline;
import domi.argenticpptmaster.service.PptImageManifestStore;
import domi.argenticpptmaster.service.PptOutlineStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptConfirmationStagePolicyTests {

    private final PptConfirmationStagePolicy policy = new PptConfirmationStagePolicy();

    @TempDir
    Path tempDir;

    @Test
    void waitsForFirstOutlineConfirmationAndAutoAcknowledgesLockedVersion() {
        PptJob job = job(PptWorkflowMode.BASIC);
        writeOutline(job, false);

        assertThat(policy.evaluate(job, "outline_confirmation", Map.of("version", 1)).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);

        new PptOutlineStore().write(job.projectPath().orElseThrow(), outline(true));
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);

        assertThat(policy.evaluate(job, "outline_confirmation", Map.of("version", 1)).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.AUTO_ACKNOWLEDGE);
    }

    @Test
    void rejectsRepeatedOutlineWithDifferentVersion() {
        PptJob job = job(PptWorkflowMode.BASIC);
        writeOutline(job, true);
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision =
                policy.evaluate(job, "outline_confirmation", Map.of("version", 2));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
        assertThat(decision.reason()).contains("version");
    }

    @Test
    void autoAcknowledgesCompletedLegacyPlanConfirmation() {
        PptJob job = job(PptWorkflowMode.BASIC);
        job.confirmNode(PptJobNode.PLAN_CONFIRMED);

        assertThat(policy.evaluate(job, "plan_confirmation", Map.of()).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.AUTO_ACKNOWLEDGE);
    }

    @Test
    void rejectsImageManifestConfirmationBeforeManifestIsWritten() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job,
                "image_manifest_confirmation",
                Map.of("type", "ppt_image_manifest", "outlineVersion", 1, "items", List.of()));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
        assertThat(decision.reason()).contains("IMAGES_MANIFEST_WRITTEN");
    }

    @Test
    void autoAcknowledgesConfirmedImageManifestForCurrentOutline() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        new PptImageManifestStore().writeFromLockedOutline(job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job,
                "image_manifest_confirmation",
                Map.of("type", "ppt_image_manifest", "outlineVersion", 1, "items", List.of()));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.AUTO_ACKNOWLEDGE);
    }

    @Test
    void rejectsImageManifestFromDifferentOutlineVersion() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        new PptImageManifestStore().writeFromLockedOutline(job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job, "image_manifest_confirmation", Map.of("outlineVersion", 2));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
        assertThat(decision.reason()).contains("outlineVersion");
    }

    @Test
    void rejectsImageReadyConfirmationBeforeGeneratedNodeCompletes() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        new PptImageManifestStore().writeFromLockedOutline(job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job, "image_ready_continue_confirmation", Map.of("outlineVersion", 1));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
        assertThat(decision.reason()).contains("IMAGES_GENERATED");
    }

    @Test
    void keepsImageRetryDecisionRepeatableOnlyWhileFailuresRemain() throws Exception {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        PptImageManifestStore manifestStore = new PptImageManifestStore();
        manifestStore.writeFromLockedOutline(job.projectPath().orElseThrow());
        markFirstManifestItemFailed(manifestStore, job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);

        assertThat(policy.evaluate(job, "image_retry_decision", Map.of("outlineVersion", 1)).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
    }

    @Test
    void waitsAgainAfterExplicitOutlineRevisionInvalidatesCompletedNode() {
        PptJob job = job(PptWorkflowMode.BASIC);
        writeOutline(job, true);
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.invalidateAfterOutlineRevision();
        new PptOutlineStore().write(job.projectPath().orElseThrow(), new PptOutline(2, false, outline(false).slides()));

        PptConfirmationStagePolicy.Decision decision =
                policy.evaluate(job, "outline_confirmation", Map.of("version", 2));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
    }

    @Test
    void autoAcknowledgesImageReadyContinuationOnlyForGeneratedCurrentManifest() throws Exception {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        PptImageManifestStore manifestStore = new PptImageManifestStore();
        manifestStore.writeFromLockedOutline(job.projectPath().orElseThrow());
        updateFirstManifestItemStatus(manifestStore, job.projectPath().orElseThrow(), "Generated");
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_GENERATED, Map.of());
        job.confirmNode(PptJobNode.IMAGE_CONTINUE_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job, "image_ready_continue_confirmation", Map.of("outlineVersion", 1));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.AUTO_ACKNOWLEDGE);
    }

    @Test
    void acceptsEmptyManifestAsReadyWhenGeneratedNodeIsCompleted() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        PptOutline outline = new PptOutline(1, true, List.of(new SlideOutline(
                1, "封面", "结论", List.of("要点"), "纯文字", null)));
        new PptOutlineStore().write(job.projectPath().orElseThrow(), outline);
        new PptImageManifestStore().writeFromLockedOutline(job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_GENERATED, Map.of());

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(
                job, "image_ready_continue_confirmation", Map.of("outlineVersion", 1));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
    }

    @Test
    void rejectsImageRetryWhenCurrentManifestHasNoFailures() {
        PptJob job = job(PptWorkflowMode.IMAGE_ENHANCED);
        writeOutline(job, true);
        new PptImageManifestStore().writeFromLockedOutline(job.projectPath().orElseThrow());
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);

        PptConfirmationStagePolicy.Decision decision =
                policy.evaluate(job, "image_retry_decision", Map.of("outlineVersion", 1));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
        assertThat(decision.reason()).contains("Failed");
    }

    private PptJob job(PptWorkflowMode mode) {
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", mode, tempDir.resolve(UUID.randomUUID().toString()));
        job.prepareProject(job.workspacePath());
        return job;
    }

    private void writeOutline(PptJob job, boolean locked) {
        new PptOutlineStore().write(job.projectPath().orElseThrow(), outline(locked));
    }

    private PptOutline outline(boolean locked) {
        return new PptOutline(1, locked, List.of(new SlideOutline(
                1,
                "封面",
                "结论",
                List.of("要点"),
                "插图",
                Map.of("purpose", "封面", "prompt", "blue abstract background"))));
    }

    private void markFirstManifestItemFailed(PptImageManifestStore store, Path projectPath) throws Exception {
        updateFirstManifestItemStatus(store, projectPath, "Failed");
    }

    private void updateFirstManifestItemStatus(PptImageManifestStore store, Path projectPath, String status)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> manifest = objectMapper.readValue(store.path(projectPath).toFile(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) manifest.get("items")).get(0);
        item.put("status", status);
        if ("Failed".equals(status)) {
            item.put("last_error", "generation failed");
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(store.path(projectPath).toFile(), manifest);
    }
}
