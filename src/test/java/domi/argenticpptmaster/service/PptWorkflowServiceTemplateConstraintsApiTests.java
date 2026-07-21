package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.security.FailClosedPptAccessContextResolver;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class PptWorkflowServiceTemplateConstraintsApiTests {

    @TempDir
    Path tempDir;

    @Test
    void acceptsValidTemplateConstraintsOnCreate() {
        PptWorkflowService service = eligibleService();
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", "pptx".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        PptJob job = service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill",
                "{\"allowedTemplateSlides\":[1,2],\"maxSlides\":3,\"preserveCover\":true}"));

        assertThat(job.templateConstraints().allowedTemplateSlides()).containsExactly(1, 2);
        assertThat(job.templateConstraints().maxSlides()).isEqualTo(3);
        assertThat(job.templateConstraints().preserveCover()).isTrue();
        assertThat(job.ownerSubjectId()).contains("user-1");
        assertThat(job.ownerTenantId()).contains("test-tenant");
    }

    @Test
    void rejectsInvalidAndConflictingConstraintsJson() {
        PptWorkflowService service = eligibleService();
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", "pptx".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill", "{bad")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("valid JSON");

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill",
                "{\"allowedTemplateSlides\":[1],\"excludedTemplateSlides\":[1]}")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("intersect");
    }

    @Test
    void rejectsTemplateConstraintsForNonTemplateWorkflow() {
        PptWorkflowService service = eligibleService();
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), null, "demo", "ppt169", null, "basic",
                "{\"maxSlides\":3}")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("only supported for template-fill");
    }

    @Test
    void rejectsTemplateFillWhenFeatureDisabledWithoutSideEffects() throws Exception {
        Path workspace = tempDir.resolve("ws-disabled");
        Files.createDirectories(workspace);
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, workspace);
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowService service = new PptWorkflowService(
                properties, repository, mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class), mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                new FixedPptAccessContextResolver(PptAccessContext.user("user-1", "test-tenant", Set.of())));
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", "pptx".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptTemplateFillUnavailableException.class);
        assertThat(Files.exists(workspace.resolve("jobs"))).isFalse();
    }

    @Test
    void rejectsIneligibleCallerAndMissingIdentity() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(
                tempDir, tempDir, "allowed-tenant");
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", "pptx".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        PptWorkflowService missingIdentity = new PptWorkflowService(
                properties, new InMemoryPptJobRepository(), mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class), mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                new FailClosedPptAccessContextResolver());
        assertThatThrownBy(() -> missingIdentity.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptTemplateFillAccessException.class);

        FixedPptAccessContextResolver otherTenant = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-2", "other-tenant", Set.of()));
        PptWorkflowService ineligible = new PptWorkflowService(
                properties, new InMemoryPptJobRepository(), mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class), mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                otherTenant);
        assertThatThrownBy(() -> ineligible.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void allowsAdministratorOutsideTenantAllowlist() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(
                tempDir, tempDir, "allowed-tenant");
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("admin-1", "ops-tenant", Set.of("ADMIN")));
        PptWorkflowService service = new PptWorkflowService(
                properties, new InMemoryPptJobRepository(), mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class), mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                access);
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", "pptx".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        PptJob job = service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill"));

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.TEMPLATE_FILL);
        assertThat(job.ownerTenantId()).contains("ops-tenant");
    }

    @Test
    void basicWorkflowStillWorksWhenTemplateFillDisabled() {
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir),
                new InMemoryPptJobRepository(), mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class), mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        PptJob job = service.createJob(new PptJobCreateCommand(
                List.of(content), null, "demo", "ppt169", null, "basic"));

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(job.hasOwnership()).isFalse();
    }

    private PptWorkflowService eligibleService() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(
                tempDir, tempDir, "test-tenant");
        return new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                new FixedPptAccessContextResolver(
                        PptAccessContext.user("user-1", "test-tenant", Set.of())));
    }
}
