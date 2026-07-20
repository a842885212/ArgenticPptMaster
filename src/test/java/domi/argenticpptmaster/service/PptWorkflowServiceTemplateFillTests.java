package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PptWorkflowServiceTemplateFillTests {

    @Test
    void rejectsOversizedTemplateUpload() {
        PptWorkflowService service = serviceWithLimits(100, 100, 200);
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "big.pptx", "application/octet-stream", new byte[150]);
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .hasMessageContaining("TEMPLATE_FILL_UPLOAD_TOO_LARGE");
    }

    @Test
    void rejectsTotalUploadOverflow() {
        PptWorkflowService service = serviceWithLimits(100, 100, 150);
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", new byte[80]);
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", new byte[80]);

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .hasMessageContaining("TEMPLATE_FILL_UPLOAD_TOO_LARGE");
    }

    @Test
    void routesTemplateFillResumeToDedicatedRunner() {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner agentRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillAsyncRunner templateFillRunner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(Path.of("repo"), Path.of("workspace")),
                repository, mock(PptWorkflowEvents.class), agentRunner, templateFillRunner);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace/job"));
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, java.util.Map.of());
        job.failNode(PptJobNode.FILL_PLAN_VALIDATED, "check failed");
        repository.save(job);

        PptJob resumed = service.resumeJob(job.id());

        verify(templateFillRunner).resumeFromCheckpoint(job.id(), PptJobNode.TEMPLATE_ANALYZED);
        verify(agentRunner, never()).resumeFromCheckpoint(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(resumed.status()).isEqualTo(PptJobStatus.PREPARING);
    }

    private static PptWorkflowService serviceWithLimits(long templateMax, long contentMax, long totalMax) {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), null, 1024,
                templateMax, contentMax, totalMax, 2, null);
        return new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class));
    }
}
