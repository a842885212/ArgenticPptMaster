package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptOutlineEditOperation;
import domi.argenticpptmaster.domain.PptRevisionImpactPreview;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.SlideOutline;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.web.dto.PptOutlineEditRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptWorkflowServiceRevisionTests {

    @TempDir
    Path tempDir;

    @Test
    void previewThenConfirmedRevisionCreatesDraftAndInvalidatesArtifacts() {
        TestContext context = testContext(PptWorkflowMode.BASIC);
        PptArtifactRegistry registry = new PptArtifactRegistry();
        registry.register(context.projectPath, "svg_output/slide_01.svg", 1);

        PptRevisionImpactPreview preview = context.service.previewOutlineRevision(context.job.id(), 1);

        assertThat(preview.affectedArtifacts()).containsExactly("svg_output/slide_01.svg");
        assertThat(context.job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(new PptOutlineStore().read(context.projectPath).locked()).isTrue();

        PptJob revised = context.service.submitConfirmation(
                context.job.id(), "outline-1", true, Map.of(), null,
                PptConfirmationAction.REQUEST_REVISION, "更新标题", List.of(), 1,
                List.of(new PptOutlineEditRequest(PptOutlineEditOperation.UPDATE, 1, slide("修订标题"))),
                preview.revisionImpactToken());

        assertThat(revised.status()).isEqualTo(PptJobStatus.RUNNING_AGENT);
        assertThat(revised.currentNode()).contains(PptJobNode.OUTLINE_DRAFTED);
        assertThat(new PptOutlineStore().read(context.projectPath))
                .extracting(PptOutline::version, PptOutline::locked)
                .containsExactly(2, false);
        assertThat(registry.isUsable(context.projectPath, "svg_output/slide_01.svg", 1)).isFalse();
        verify(context.asyncRunner).resumeAgent(context.job.id());
    }

    @Test
    void rejectsLockedRevisionWithMissingOrMismatchedImpactTokenWithoutMutatingOutline() {
        TestContext context = testContext(PptWorkflowMode.IMAGE_ENHANCED);

        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "outline-1", true, Map.of(), null,
                PptConfirmationAction.REQUEST_REVISION, "更新标题", List.of(), 1,
                List.of(new PptOutlineEditRequest(PptOutlineEditOperation.UPDATE, 1, slide("修订标题"))), null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("revisionImpactToken");

        assertThatThrownBy(() -> context.service.previewOutlineRevision(context.job.id(), 2))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("outline version does not match active outline");
        assertThat(new PptOutlineStore().read(context.projectPath))
                .extracting(PptOutline::version, PptOutline::locked)
                .containsExactly(1, true);
        assertThat(context.job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
    }

    @Test
    void blocksCheckpointResumeForStaleArtifactsInBothWorkflowModes() {
        assertStaleCheckpointBlocksResume(PptWorkflowMode.BASIC, PptJobNode.SVG_OUTPUT_VALIDATED);
        assertStaleCheckpointBlocksResume(PptWorkflowMode.IMAGE_ENHANCED, PptJobNode.IMAGES_GENERATED);
    }

    @Test
    void approvesOnlyCurrentLockedOutlineImageManifestAndResumesAgent() throws Exception {
        TestContext context = testContext(PptWorkflowMode.IMAGE_ENHANCED);
        PptImageManifestStore manifestStore = new PptImageManifestStore();
        manifestStore.writeFromLockedOutline(context.projectPath);
        context.job.waitNodeConfirmation(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        context.job.requireConfirmation("manifest-1", Map.of(
                "stage", "image_manifest_confirmation",
                "contextData", Map.of("type", "ppt_image_manifest", "outlineVersion", 1, "items", List.of())));

        PptJob confirmed = context.service.submitConfirmation(
                context.job.id(), "manifest-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);

        assertThat(confirmed.nodeExecution(PptJobNode.IMAGE_MANIFEST_CONFIRMED).status())
                .isEqualTo(domi.argenticpptmaster.domain.PptJobNodeStatus.COMPLETED);
        verify(context.asyncRunner).resumeAgent(context.job.id());
    }

    @Test
    void rejectsImageManifestRevisionAfterAnImageWasGenerated() throws Exception {
        TestContext context = testContext(PptWorkflowMode.IMAGE_ENHANCED);
        java.nio.file.Files.createDirectories(context.projectPath.resolve("images"));
        java.nio.file.Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {"outlineVersion": 1, "items": [{"outlineVersion": 1, "slideNo": 1,
                  "filename": "slide-01-image.png", "prompt": "cover", "aspect_ratio": "16:9", "status": "Generated"}]}
                """);
        context.job.waitNodeConfirmation(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        context.job.requireConfirmation("manifest-1", Map.of(
                "stage", "image_manifest_confirmation",
                "contextData", Map.of("type", "ppt_image_manifest", "outlineVersion", 1, "items", List.of())));

        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "manifest-1", true, Map.of(), "修改提示词",
                PptConfirmationAction.REQUEST_REVISION, "修改提示词", List.of(), null, List.of(), null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("cannot be revised");
    }

    private void assertStaleCheckpointBlocksResume(PptWorkflowMode mode, PptJobNode failedNode) {
        TestContext context = testContext(mode);
        PptArtifactRegistry registry = new PptArtifactRegistry();
        registry.register(context.projectPath, "notes/slide_01.md", 1);
        registry.markStale(context.projectPath, 1);
        context.job.completeNode(PptJobNode.DESIGN_SPEC_WRITTEN, Map.of());
        context.job.failNode(failedNode, "simulated failure");

        assertThatThrownBy(() -> context.service.resumeJob(context.job.id()))
                .isInstanceOf(PptJobResumeException.class)
                .hasMessageContaining("stale");
    }

    private TestContext testContext(PptWorkflowMode mode) {
        Path projectPath = tempDir.resolve(mode.name().toLowerCase());
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner asyncRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillAsyncRunner templateFillAsyncRunner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir), repository,
                mock(PptWorkflowEvents.class), asyncRunner, templateFillAsyncRunner);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "make a deck", mode, projectPath);
        job.prepareProject(projectPath);
        job.waitNodeConfirmation(PptJobNode.OUTLINE_DRAFTED);
        job.requireConfirmation("outline-1", outlinePayload());
        repository.save(job);
        new PptOutlineStore().write(projectPath, new PptOutline(1, true, List.of(slide("原始标题"))));
        return new TestContext(service, asyncRunner, job, projectPath);
    }

    private Map<String, Object> outlinePayload() {
        return Map.of(
                "stage", "outline_confirmation",
                "contextData", Map.of("type", "ppt_outline", "version", 1, "locked", true,
                        "slides", List.of(Map.of(
                                "slideNo", 1,
                                "title", "原始标题",
                                "keyMessage", "原始结论",
                                "bullets", List.of("原始要点"),
                                "visualSuggestion", "原始视觉方案"))));
    }

    private SlideOutline slide(String title) {
        return new SlideOutline(1, title, "修订结论", List.of("修订要点"), "修订视觉方案", null);
    }

    private record TestContext(
            PptWorkflowService service, PptWorkflowAsyncRunner asyncRunner, PptJob job, Path projectPath) {
    }
}
