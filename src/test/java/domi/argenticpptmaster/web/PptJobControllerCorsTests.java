package domi.argenticpptmaster.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import domi.argenticpptmaster.config.WebConfig;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowService;
import domi.argenticpptmaster.service.PptTemplateFillService;
import domi.argenticpptmaster.service.TemplateFillDiagnosticService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.SharedHttpSessionConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

/**
 * {@link PptJobController} 的跨域配置测试。
 * <p>
 * 本组测试聚焦于 {@link WebConfig} 中新增的 CORS 行为，验证：
 * </p>
 * <ul>
 *   <li>本地联调来源可以正常通过预检请求；</li>
 *   <li>SSE 事件订阅接口在允许来源下能够返回跨域响应头；</li>
 *   <li>下载接口会暴露 {@code Content-Disposition} 头，便于前端读取文件名；</li>
 *   <li>非受信任来源会被拒绝，避免默认放开所有来源。</li>
 * </ul>
 */
@WebMvcTest(PptJobController.class)
@Import(WebConfig.class)
@TestPropertySource(properties = {
        "ppt.web.cors.allowed-origin-patterns=http://localhost:*,http://127.0.0.1:*,http://[::1]:*"
})
class PptJobControllerCorsTests {

    private static final UUID JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String LOCALHOST_ORIGIN = "http://localhost:5173";
    private static final String LOOPBACK_ORIGIN = "http://127.0.0.1:3000";
    private static final String UNTRUSTED_ORIGIN = "http://evil.example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PptWorkflowService workflowService;

    @MockitoBean
    private PptTemplateFillService templateFillService;

    @MockitoBean
    private TemplateFillDiagnosticService diagnosticService;

    @MockitoBean
    private PptJobEventPublisher eventPublisher;

    @TempDir
    Path tempDir;

    /**
     * 验证允许的 localhost 来源能够通过 PPT 任务创建接口的预检请求。
     * <p>
     * 该测试同时覆盖 {@code allowCredentials(true)} 与允许请求头的配置，
     * 确保前端在联调阶段携带自定义头时不会被 CORS 预检拦截。
     * </p>
     *
     * @throws Exception 当 MockMvc 调用失败时抛出
     */
    @Test
    void allowsPreflightRequestForConfiguredLocalhostOrigin() throws Exception {
        performCreateJobPreflight(LOCALHOST_ORIGIN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCALHOST_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("content-type")));
    }

    /**
     * 验证未在允许列表中的来源会被预检请求直接拒绝。
     *
     * @throws Exception 当 MockMvc 调用失败时抛出
     */
    @Test
    void rejectsPreflightRequestForUntrustedOrigin() throws Exception {
        performCreateJobPreflight(UNTRUSTED_ORIGIN)
                .andExpect(status().isForbidden());
    }

    /**
     * 验证 SSE 事件订阅接口在允许来源下会返回正确的跨域头。
     * <p>
     * 该测试覆盖 {@code text/event-stream} 场景，避免联调时出现
     * “普通 REST 可用但 SSE 被浏览器拦截”的回归问题。
     * </p>
     *
     * @throws Exception 当 MockMvc 调用失败时抛出
     */
    @Test
    void allowsSseSubscriptionForLoopbackOrigin() throws Exception {
        PptJob job = new PptJob(JOB_ID, "demo", "ppt169", "", domi.argenticpptmaster.domain.PptWorkflowMode.BASIC, tempDir.resolve("workspace"));
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        given(workflowService.getAccessibleJob(JOB_ID)).willReturn(job);
        given(eventPublisher.subscribe(job)).willReturn(emitter);

        mockMvc.perform(get("/api/ppt-jobs/{jobId}/events", JOB_ID)
                        .header(HttpHeaders.ORIGIN, LOOPBACK_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOOPBACK_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.TEXT_EVENT_STREAM_VALUE)));
    }

    /**
     * 验证下载接口会暴露 {@code Content-Disposition}，以便前端读取文件名。
     *
     * @throws Exception 当 MockMvc 调用失败时抛出
     */
    @Test
    void exposesContentDispositionForDownloadResponse() throws Exception {
        Path exportPath = tempDir.resolve("demo-deck.pptx");
        Files.writeString(exportPath, "ppt");
        given(workflowService.exportPath(JOB_ID)).willReturn(exportPath);

        mockMvc.perform(get("/api/ppt-jobs/{jobId}/download", JOB_ID)
                        .header(HttpHeaders.ORIGIN, LOCALHOST_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCALHOST_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString(HttpHeaders.CONTENT_DISPOSITION)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename=\"demo-deck.pptx\"")));
    }

    /**
     * 构造 PPT 任务创建接口的预检请求。
     *
     * @param origin 发起请求的来源
     * @return 预检请求的执行结果
     * @throws Exception 当 MockMvc 调用失败时抛出
     */
    private ResultActions performCreateJobPreflight(String origin) throws Exception {
        return mockMvc.perform(options("/api/ppt-jobs")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization"));
    }
}
