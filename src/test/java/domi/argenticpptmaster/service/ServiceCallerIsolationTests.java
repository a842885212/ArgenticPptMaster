package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptCallerAccessException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** Cross-client isolation for create / get / download. */
class ServiceCallerIsolationTests {

    @TempDir
    Path tempDir;

    @Test
    void sameClientCanReadAndCrossClientIsDenied() throws Exception {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("studio", "studio", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);
        PptWorkflowService workflow = new PptWorkflowService(
                properties,
                repository,
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                access,
                authorizer);

        PptJob created = workflow.createJob(new PptJobCreateCommand(
                List.of(md("a.md")), null, "demo", "ppt169", null, "basic"));
        assertThat(created.ownerSubjectId()).contains("studio");

        assertThat(workflow.getAccessibleJob(created.id()).id()).isEqualTo(created.id());

        access.set(PptAccessContext.user("script", "script", Set.of()));
        assertThatThrownBy(() -> workflow.getAccessibleJob(created.id()))
                .isInstanceOf(PptCallerAccessException.class);

        Path export = created.workspacePath().resolve("exports/out.pptx");
        Files.createDirectories(export.getParent());
        Files.writeString(export, "bytes");
        created.complete(export);
        repository.save(created);

        assertThatThrownBy(() -> workflow.exportPath(created.id()))
                .isInstanceOf(PptCallerAccessException.class);

        access.set(PptAccessContext.user("studio", "studio", Set.of()));
        assertThat(workflow.exportPath(created.id())).isEqualTo(export.toAbsolutePath().normalize());
        assertThat(created.status()).isEqualTo(PptJobStatus.COMPLETED);
    }

    @Test
    void unauthenticatedCreateIsRejected() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        PptWorkflowService workflow = new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser());

        assertThatThrownBy(() -> workflow.createJob(new PptJobCreateCommand(
                List.of(md("a.md")), null, "demo", "ppt169", null, "basic")))
                .hasMessageContaining("authenticated caller");
    }

    private static MockMultipartFile md(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# x".getBytes());
    }
}
