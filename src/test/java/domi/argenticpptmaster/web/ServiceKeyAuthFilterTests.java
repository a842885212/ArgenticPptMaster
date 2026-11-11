package domi.argenticpptmaster.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import domi.argenticpptmaster.config.WebConfig;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.security.ServiceKeyAuthFilter;
import domi.argenticpptmaster.security.ServiceKeyAuthenticator;
import domi.argenticpptmaster.service.BeautifyPptxDiagnosticService;
import domi.argenticpptmaster.service.NativeEnhancePptxDiagnosticService;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptTemplateFillService;
import domi.argenticpptmaster.service.PptWorkflowService;
import domi.argenticpptmaster.service.TemplateFillDiagnosticService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import domi.argenticpptmaster.config.ServiceAuthProperties;

@WebMvcTest(controllers = PptJobController.class)
@Import({WebConfig.class, ServiceKeyAuthFilter.class, ServiceKeyAuthenticator.class})
@EnableConfigurationProperties(ServiceAuthProperties.class)
@TestPropertySource(properties = {
        "ppt.web.cors.allowed-origin-patterns=http://localhost:*",
        "ppt-master.service-auth.enabled=true",
        "ppt-master.service-auth.clients[0].id=studio",
        "ppt-master.service-auth.clients[0].key=studio-secret"
})
class ServiceKeyAuthFilterTests {

    private static final UUID JOB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PptWorkflowService workflowService;

    @MockitoBean
    private PptTemplateFillService templateFillService;

    @MockitoBean
    private TemplateFillDiagnosticService diagnosticService;

    @MockitoBean
    private BeautifyPptxDiagnosticService beautifyDiagnosticService;

    @MockitoBean
    private NativeEnhancePptxDiagnosticService nativeEnhanceDiagnosticService;

    @MockitoBean
    private PptJobEventPublisher eventPublisher;

    @Test
    void rejectsMissingAndInvalidServiceKeyWith401() throws Exception {
        mockMvc.perform(get("/api/ppt-jobs/{jobId}", JOB_ID))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/ppt-jobs/{jobId}", JOB_ID)
                        .header("X-PPT-Service-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsValidKeyToReachController() throws Exception {
        PptJob job = new PptJob(JOB_ID, "demo", "ppt169", null, PptWorkflowMode.BASIC, Path.of("workspace"));
        given(workflowService.getAccessibleJob(JOB_ID)).willReturn(job);

        mockMvc.perform(get("/api/ppt-jobs/{jobId}", JOB_ID)
                        .header("X-PPT-Service-Key", "studio-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void protectsTemplateFillExecuteWithoutServiceKey() throws Exception {
        mockMvc.perform(post("/api/ppt-jobs/{jobId}/template-fill/execute", JOB_ID)
                        .header("X-Template-Fill-Debug-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsCreateWithValidKey() throws Exception {
        PptJob job = new PptJob(JOB_ID, "demo", "ppt169", null, PptWorkflowMode.BASIC, Path.of("workspace"));
        given(workflowService.createJob(any())).willReturn(job);

        MockMultipartFile file = new MockMultipartFile(
                "files", "a.md", "text/markdown", "# hi".getBytes());
        mockMvc.perform(multipart("/api/ppt-jobs")
                        .file(file)
                        .header("X-PPT-Service-Key", "studio-secret")
                        .param("workflowMode", "basic"))
                .andExpect(status().isAccepted());
    }
}
