package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptNativeEnhanceUnavailableException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class NativeEnhancePptxProductionSecurityTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsCreateWhenFeatureFlagDisabledWithoutSideEffects() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        PptWorkflowService service = new PptWorkflowService(
                properties,
                mock(PptJobRepository.class),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser());

        MockMultipartFile source = new MockMultipartFile(
                "files", "source.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        assertThatThrownBy(() -> service.createJob(
                List.of(source), "demo", "ppt169", null, "native-enhance-pptx"))
                .isInstanceOf(PptNativeEnhanceUnavailableException.class);
        assertThat(tempDir.resolve("jobs").toFile().exists()).isFalse();
    }
}
