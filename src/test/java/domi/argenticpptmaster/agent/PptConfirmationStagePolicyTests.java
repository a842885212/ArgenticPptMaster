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
import domi.argenticpptmaster.service.BeautifyPptxPlanStore;
import domi.argenticpptmaster.service.NativeEnhancePlanStore;
import domi.argenticpptmaster.service.PptOutlineStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptConfirmationStagePolicyTests {

    private final PptConfirmationStagePolicy policy = new PptConfirmationStagePolicy();

    @Test
    void beautifyConfirmationRequiresModeAndCompletedAnalysisAndDraft() {
        Path projectPath = tempDir.resolve("beautify-project");
        PptJob job = new PptJob(java.util.UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, projectPath);
        job.prepareProject(projectPath);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.PlanMetadata plan = BeautifyPptxPlanStore.storeDraft(job, """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"theme",
                "fontCandidateId":"theme","bodySizeCandidateId":"theme","rationale":"theme"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """);
        job.completeNode(PptJobNode.BEAUTIFY_PLAN_DRAFTED, Map.of());

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(job, "beautify_plan_confirmation", Map.of(
                "type", "beautify_pptx_plan", "version", 1, "digest", plan.digest()));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
        assertThat(decision.confirmedNode()).isEqualTo(PptJobNode.BEAUTIFY_PLAN_CONFIRMED);
    }

    @Test
    void enhancementPlanConfirmationRequiresModeDraftAndDigest() throws Exception {
        Path projectPath = tempDir.resolve("native-enhance-project");
        Files.createDirectories(projectPath.resolve("analysis"));
        Files.writeString(projectPath.resolve("project.json"), "{\"schema\":\"native_pptx_enhancement_project.v1\"}");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode planJson = mapper.createObjectNode();
        planJson.put("schema", "native_pptx_enhancement_plan.v1");
        planJson.put("status", "draft");
        planJson.put("slide_count", 1);
        ObjectNode modules = planJson.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", false);
        modules.putObject("timings").put("enabled", false);
        modules.putObject("transitions").put("enabled", false);
        mapper.writeValue(projectPath.resolve("analysis/enhancement_plan.json").toFile(), planJson);

        PptJob job = new PptJob(UUID.randomUUID(), "ne", "ppt169", null,
                PptWorkflowMode.NATIVE_ENHANCE_PPTX, projectPath);
        job.prepareProject(projectPath);
        job.completeNode(PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of());
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of());
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(job, "enhancement_plan_confirmation", Map.of(
                "type", "native_pptx_enhancement_plan",
                "version", draft.version(),
                "digest", draft.digest()));
        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
        assertThat(decision.confirmedNode()).isEqualTo(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED);

        assertThat(policy.evaluate(job, "enhancement_plan_confirmation", Map.of(
                "type", "native_pptx_enhancement_plan",
                "version", draft.version(),
                "digest", "wrong")).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);
    }

    @Test
    void audioVoiceConfirmationMapsAfterPlanConfirmed() throws Exception {
        Path projectPath = tempDir.resolve("native-enhance-audio");
        Files.createDirectories(projectPath.resolve("analysis"));
        Files.writeString(projectPath.resolve("project.json"), "{\"schema\":\"native_pptx_enhancement_project.v1\"}");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode planJson = mapper.createObjectNode();
        planJson.put("schema", "native_pptx_enhancement_plan.v1");
        planJson.put("status", "draft");
        planJson.put("slide_count", 1);
        ObjectNode modules = planJson.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", true);
        modules.putObject("timings").put("enabled", false);
        modules.putObject("transitions").put("enabled", false);
        mapper.writeValue(projectPath.resolve("analysis/enhancement_plan.json").toFile(), planJson);
        PptJob job = new PptJob(UUID.randomUUID(), "ne", "ppt169", null,
                PptWorkflowMode.NATIVE_ENHANCE_PPTX, projectPath);
        job.prepareProject(projectPath);
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
        NativeEnhancePlanStore.confirmDraft(job, draft.version(), draft.digest(), Map.of(
                "notesEnabled", true, "audioEnabled", true, "timingsEnabled", false, "transitionsEnabled", false));
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED, Map.of());

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(job, "audio_voice_confirmation", Map.of(
                "type", "native_pptx_audio_voice", "provider", "edge", "voice", "zh-CN-YunjianNeural"));
        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
        assertThat(decision.confirmedNode()).isEqualTo(PptJobNode.AUDIO_VOICE_CONFIRMED);
    }

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

    @Test
    void waitsForTemplateFillPlanWhenDraftCompletedAndPayloadValid() {
        PptJob job = job(PptWorkflowMode.TEMPLATE_FILL);
        job.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of());

        PptConfirmationStagePolicy.Decision decision = policy.evaluate(job, "template_fill_plan", Map.of(
                "type", "template_fill_plan",
                "version", 1,
                "digest", "abc123",
                "pages", List.of(Map.of(
                        "outputOrder", 1,
                        "templateSlideIndex", 1,
                        "layoutNote", "cover",
                        "slotMappings", List.of(Map.of(
                                "slotId", "s01_sh1",
                                "sourceRef", "content:content.md",
                                "preview", "short")),
                        "omittedContent", List.of("附录"),
                        "capacityRisks", List.of("接近上限"),
                        "splitSuggestions", List.of("拆页"),
                        "tableChartHandling", List.of(Map.of("targetId", "t1", "strategy", "摘要")),
                        "acceptedWarnings", List.of("warn")))));

        assertThat(decision.disposition()).isEqualTo(PptConfirmationStagePolicy.Disposition.WAIT_FOR_USER);
        assertThat(decision.confirmedNode()).isEqualTo(PptJobNode.FILL_PLAN_CONFIRMED);
    }

    @Test
    void rejectsTemplateFillPlanForWrongModeMissingDraftOrInvalidPayload() {
        PptJob basic = job(PptWorkflowMode.BASIC);
        assertThat(policy.evaluate(basic, "template_fill_plan", Map.of(
                "type", "template_fill_plan", "version", 1, "digest", "x")).disposition())
                .isEqualTo(PptConfirmationStagePolicy.Disposition.REJECT);

        PptJob fill = job(PptWorkflowMode.TEMPLATE_FILL);
        assertThat(policy.evaluate(fill, "template_fill_plan", Map.of(
                "type", "template_fill_plan", "version", 1, "digest", "x")).reason())
                .contains("FILL_PLAN_DRAFTED");
        fill.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of());
        assertThat(policy.evaluate(fill, "template_fill_plan", Map.of(
                "type", "ppt_outline", "version", 1, "digest", "x")).reason())
                .contains("template_fill_plan");
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
