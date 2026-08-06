package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhancePlanStoreTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void ensureDraftAddsVersionDigestAndConfirmKeepsDisabledModules() throws Exception {
        PptJob job = jobWithDraftPlan(false);
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.digest()).isNotBlank();
        assertThat(draft.status()).isEqualTo("draft");

        NativeEnhancePlanStore.PlanMetadata confirmed =
                NativeEnhancePlanStore.confirmDraft(job, draft.version(), draft.digest(), Map.of());
        assertThat(confirmed.status()).isEqualTo("confirmed");

        ObjectNode plan = NativeEnhancePlanStore.readPlan(job).orElseThrow();
        assertThat(plan.path("modules").path("notes").path("enabled").asBoolean()).isTrue();
        assertThat(plan.path("modules").path("audio").path("enabled").asBoolean()).isFalse();
        assertThat(plan.path("modules").path("timings").path("enabled").asBoolean()).isFalse();
        assertThat(plan.path("modules").path("transitions").path("enabled").asBoolean()).isFalse();
        assertThat(plan.has("modules")).isTrue();
        assertThat(plan.path("modules").has("audio")).isTrue();
    }

    @Test
    void rejectForbiddenConfirmAnswers() throws Exception {
        PptJob job = jobWithDraftPlan(false);
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
        assertThatThrownBy(() -> NativeEnhancePlanStore.confirmDraft(
                job, draft.version(), draft.digest(), Map.of("slide_count", 99)))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("slide_count");
    }

    @Test
    void rejectDigestMismatch() throws Exception {
        PptJob job = jobWithDraftPlan(false);
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
        assertThatThrownBy(() -> NativeEnhancePlanStore.confirmDraft(
                job, draft.version(), "deadbeef", Map.of()))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void writeProjectAudio() throws Exception {
        PptJob job = jobWithDraftPlan(true);
        NativeEnhancePlanStore.writeProjectAudio(job, Map.of(
                "provider", "edge",
                "voice", "zh-CN-XiaoxiaoNeural",
                "rate", "+5%"));
        String json = Files.readString(job.projectPath().orElseThrow().resolve("project.json"));
        assertThat(json).contains("zh-CN-XiaoxiaoNeural").contains("+5%");
    }

    private PptJob jobWithDraftPlan(boolean withProjectJson) throws Exception {
        Path project = tempDir.resolve("proj-" + UUID.randomUUID());
        Files.createDirectories(project.resolve("analysis"));
        if (withProjectJson) {
            Files.writeString(project.resolve("project.json"), """
                    {"schema":"native_pptx_enhancement_project.v1","audio":{"provider":"","voice":"","rate":""}}
                    """);
        } else {
            Files.writeString(project.resolve("project.json"), "{\"schema\":\"native_pptx_enhancement_project.v1\"}");
        }
        ObjectNode plan = mapper.createObjectNode();
        plan.put("schema", "native_pptx_enhancement_plan.v1");
        plan.put("status", "draft");
        plan.put("slide_count", 2);
        ObjectNode modules = plan.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", true);
        modules.putObject("timings").put("enabled", true);
        modules.putObject("transitions").put("enabled", true);
        mapper.writeValue(project.resolve("analysis/enhancement_plan.json").toFile(), plan);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null,
                PptWorkflowMode.NATIVE_ENHANCE_PPTX, tempDir);
        job.prepareProject(project);
        return job;
    }
}
