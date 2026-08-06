package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import domi.argenticpptmaster.config.BeautifyPptxProductionProperties;
import domi.argenticpptmaster.config.BeautifyPptxProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptBeautifyUnavailableException;
import domi.argenticpptmaster.repository.PptJobRepository;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class BeautifyPptxDiagnosticServiceTests {

    @TempDir
    Path tempDir;

    private PptJobRepository repository;
    private FixedPptAccessContextResolver accessResolver;
    private BeautifyPptxAuditSink auditSink;
    private BeautifyPptxLifecycleStore lifecycleStore;
    private BeautifyPptxDiagnosticBundleBuilder bundleBuilder;
    private BeautifyPptxDiagnosticService service;
    private ListAppender<ILoggingEvent> appender;
    private PptJob job;

    @BeforeEach
    void setUp() throws Exception {
        PptMasterProperties properties = enabledDiagnosticsProperties();
        repository = org.mockito.Mockito.mock(PptJobRepository.class);
        accessResolver = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-1", "tenant-a", Set.of()));
        auditSink = new BeautifyPptxAuditSink();
        lifecycleStore = new BeautifyPptxLifecycleStore(properties);
        bundleBuilder = new BeautifyPptxDiagnosticBundleBuilder(lifecycleStore);
        service = new BeautifyPptxDiagnosticService(
                properties,
                repository,
                new PptJobAccessAuthorizer(properties, accessResolver),
                accessResolver,
                bundleBuilder,
                lifecycleStore,
                auditSink);

        Path workspace = tempDir.resolve("job");
        Files.createDirectories(workspace);
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "beautify",
                PptWorkflowMode.BEAUTIFY_PPTX, workspace);
        job.assignOwnership("user-1", "tenant-a");
        lifecycleStore.initialize(job);
        when(repository.findById(job.id())).thenReturn(Optional.of(job));

        Logger logger = (Logger) LoggerFactory.getLogger("beautify-pptx-audit");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger("beautify-pptx-audit");
        logger.detachAppender(appender);
    }

    @Test
    void exportsDiagnosticsForAuthorizedOwnerAndAuditsSuccess() throws Exception {
        var bundle = service.exportDiagnostics(job.id());

        assertThat(bundle.path()).exists();
        assertThat(lifecycleStore.read(job).orElseThrow().artifactCount("diagnostic")).isEqualTo(1);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("diagnostics", job.id().toString(), "SUCCESS", "OK");
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("user-1", "tenant-a");
    }

    @Test
    void rejectsWhenDiagnosticsDisabled() {
        BeautifyPptxProductionProperties production = BeautifyPptxProductionProperties.forTestEnabled("tenant-a");
        PptMasterProperties disabled = new PptMasterProperties(
                tempDir,
                tempDir,
                "python3",
                java.time.Duration.ofSeconds(30),
                null,
                1_048_576L,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                new BeautifyPptxProperties(0, null, null, null, null, 0, 0, null, null, null,
                        new BeautifyPptxProductionProperties(
                                production.enabled(),
                                production.allowedTenants(),
                                production.adminRole(),
                                production.retentionCompleted(),
                                production.retentionFailed(),
                                production.retentionDiagnostic(),
                                production.retentionMin(),
                                production.retentionMax(),
                                production.cleanupDryRunEnabled(),
                                production.cleanupDeletionEnabled(),
                                false,
                                production.executionStopEnabled())));
        service = new BeautifyPptxDiagnosticService(
                disabled,
                repository,
                new PptJobAccessAuthorizer(disabled, accessResolver),
                accessResolver,
                bundleBuilder,
                lifecycleStore,
                auditSink);

        assertThatThrownBy(() -> service.exportDiagnostics(job.id()))
                .isInstanceOf(PptBeautifyUnavailableException.class);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("DENIED", "DISABLED");
    }

    @Test
    void rejectsUnauthorizedAccessAndAuditsDenial() {
        accessResolver.set(PptAccessContext.user("other-user", "tenant-a", Set.of()));

        assertThatThrownBy(() -> service.exportDiagnostics(job.id()))
                .isInstanceOf(PptBeautifyAccessException.class);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("DENIED", "ACCESS_DENIED");
    }

    private static PptMasterProperties enabledDiagnosticsProperties() {
        BeautifyPptxProductionProperties production = new BeautifyPptxProductionProperties(
                true,
                java.util.List.of("tenant-a"),
                "ADMIN",
                BeautifyPptxProductionProperties.defaults().retentionCompleted(),
                BeautifyPptxProductionProperties.defaults().retentionFailed(),
                BeautifyPptxProductionProperties.defaults().retentionDiagnostic(),
                BeautifyPptxProductionProperties.defaults().retentionMin(),
                BeautifyPptxProductionProperties.defaults().retentionMax(),
                true,
                false,
                true,
                false);
        return new PptMasterProperties(
                Path.of("repo"),
                Path.of("workspace"),
                "python3",
                java.time.Duration.ofSeconds(30),
                null,
                1_048_576L,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                new BeautifyPptxProperties(0, null, null, null, null, 0, 0, null, null, null, production));
    }
}
