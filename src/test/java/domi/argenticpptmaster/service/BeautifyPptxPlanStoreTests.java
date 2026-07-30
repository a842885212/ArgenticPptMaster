package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxPlanStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void persistsCanonicalDraftAndConfirmsMatchingDigestWithIdentityLock() {
        PptJob job = job();
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));

        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, draftJson());

        assertThat(BeautifyPptxPlanStore.readDraft(job)).contains(draft);
        BeautifyPptxPlanStore.confirmDraft(job, draft.version(), draft.digest(),
                Map.of("identitySource", "observed"));
        assertThat(BeautifyPptxPlanStore.read(job)).contains(new BeautifyPptxPlanStore.PlanMetadata(
                draft.version(), draft.digest(), "confirmed"));
        assertThat(BeautifyPptxPlanStore.hasIdentityLock(job)).isTrue();
        assertThat(BeautifyPptxPlanStore.readPlan(job).orElseThrow().path("identityLock").path("source").asText())
                .isEqualTo("observed");
    }

    @Test
    void rejectsMissingIdentityWeakenedConstraintsForgedConfirmedAndPageMismatch() {
        PptJob job = job();
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));

        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job,
                draftJson().replace("\"source\":\"theme\"", "\"source\":\"custom\"")))
                .isInstanceOf(PptJobStateException.class);

        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job,
                draftJson().replace(",\"identityRecommendation\":" + identityBlock(), "")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("identity");

        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job, draftJson().replace("true", "false")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("preserve text");

        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job,
                draftJson().replace("\"sourcePageCount\":1", "\"sourcePageCount\":2")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("sourcePageCount");

        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job,
                draftJson().replace("\"status\":\"draft\"", "\"status\":\"confirmed\"")))
                .isInstanceOf(PptJobStateException.class);

        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, draftJson());
        assertThatThrownBy(() -> BeautifyPptxPlanStore.confirmDraft(job, draft.version(), "forged"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("digest mismatch");

        assertThatThrownBy(() -> BeautifyPptxPlanStore.confirmDraft(
                job, draft.version(), draft.digest(), Map.of("pageMapping", "flexible")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("hard constraints");
    }

    @Test
    void confirmsMediaResolutionsAndRejectsInvalidStrategies() {
        PptJob job = job();
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        String mediaDraft = """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":%s,
                "ignored":[{"page":1,"type":"image_crop","reason":"crop lost"}],
                "needsConfirmation":[{"page":1,"type":"combo_chart","reason":"dual axis"}],
                "validationStrictness":"strict"}
                """.formatted(identityBlock());
        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, mediaDraft);

        BeautifyPptxPlanStore.confirmDraft(job, draft.version(), draft.digest(), Map.of(
                "validationStrictness", "standard",
                "needsConfirmationResolutions", Map.of("combo_chart", "accept_degradation"),
                "acceptedIgnoredIds", java.util.List.of("image_crop")));

        var plan = BeautifyPptxPlanStore.readPlan(job).orElseThrow();
        assertThat(plan.path("validationStrictness").asText()).isEqualTo("standard");
        assertThat(plan.path("needsConfirmationResolutions").path("combo_chart").asText())
                .isEqualTo("accept_degradation");

        PptJob other = job();
        other.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.PlanMetadata next = BeautifyPptxPlanStore.storeDraft(other, mediaDraft);
        assertThatThrownBy(() -> BeautifyPptxPlanStore.confirmDraft(
                other, next.version(), next.digest(), Map.of("needsConfirmationResolutions", Map.of("x", "rewrite_data"))))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("hard constraints");
    }

    private PptJob job() {
        Path project = tempDir.resolve("project-" + UUID.randomUUID());
        PptJob job = new PptJob(UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.prepareProject(project);
        return job;
    }

    private String draftJson() {
        return """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":%s,
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """.formatted(identityBlock());
    }

    private String identityBlock() {
        return """
                {"source":"theme","candidates":[{"id":"theme"},{"id":"observed"}],
                "paletteCandidateId":"theme","fontCandidateId":"theme","bodySizeCandidateId":"theme",
                "rationale":"theme-driven"}
                """.replace("\n", "");
    }
}
