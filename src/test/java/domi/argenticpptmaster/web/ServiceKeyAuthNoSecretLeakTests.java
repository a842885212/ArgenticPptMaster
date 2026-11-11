package domi.argenticpptmaster.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import domi.argenticpptmaster.config.ServiceAuthProperties;
import domi.argenticpptmaster.config.WebConfig;
import domi.argenticpptmaster.security.ServiceKeyAuthFilter;
import domi.argenticpptmaster.security.ServiceKeyAuthenticator;
import domi.argenticpptmaster.service.BeautifyPptxDiagnosticService;
import domi.argenticpptmaster.service.NativeEnhancePptxDiagnosticService;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptTemplateFillService;
import domi.argenticpptmaster.service.PptWorkflowService;
import domi.argenticpptmaster.service.TemplateFillDiagnosticService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = PptJobController.class)
@Import({WebConfig.class, ServiceKeyAuthFilter.class, ServiceKeyAuthenticator.class})
@EnableConfigurationProperties(ServiceAuthProperties.class)
@TestPropertySource(properties = {
        "ppt-master.service-auth.enabled=true",
        "ppt-master.service-auth.clients[0].id=studio",
        "ppt-master.service-auth.clients[0].key=super-secret-value"
})
class ServiceKeyAuthNoSecretLeakTests {

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
    void unauthorizedResponseDoesNotEchoSubmittedSecret() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ppt-jobs/{id}", UUID.randomUUID())
                        .header("X-PPT-Service-Key", "super-secret-value-WRONG"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("super-secret-value");
        assertThat(body).doesNotContain("super-secret-value-WRONG");
        assertThat(body).contains("service authentication required");
    }
}
