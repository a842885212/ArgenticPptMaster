package domi.argenticpptmaster.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptRevisionImpactPreview;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import domi.argenticpptmaster.web.dto.PptSlideEditRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

/**
 * {@link PptJobController} 的 WebMvc 单元测试。
 * <p>
 * 验证任务恢复等控制层接口的 HTTP 行为，包括成功响应、状态转换和错误码映射。
 * </p>
 */
@WebMvcTest(PptJobController.class)
@Import(domi.argenticpptmaster.config.WebConfig.class)
@TestPropertySource(properties = {
        "ppt.web.cors.allowed-origin-patterns=http://localhost:*,http://127.0.0.1:*,http://[::1]:*"
})
class PptJobControllerTests {

    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PptWorkflowService workflowService;

    @MockitoBean
    private PptJobEventPublisher eventPublisher;

    /**
     * 验证恢复失败任务成功时返回 200 并暴露恢复后的任务状态。
     */
    @Test
    void resumeFailedJobReturnsOkWithResumeState() throws Exception {
        PptJob job = new PptJob(
                JOB_ID, "demo", "ppt169", "make a deck",
                PptWorkflowMode.BASIC, Path.of("var/ppt-master/jobs/demo"));
        job.startNode(PptJobNode.PROJECT_READY);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of());
        job.startNode(PptJobNode.PLAN_CONFIRMED);
        job.failNode(PptJobNode.PLAN_CONFIRMED, "previous failure");
        // 模拟 resumeJob 接受恢复后的状态：状态切回 RUNNING_AGENT，resumeCount 增加，lastFailureNode 被清空
        job.tryStartResumeAttempt(5);
        given(workflowService.resumeJob(JOB_ID)).willReturn(job);

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/resume", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING_AGENT"))
                .andExpect(jsonPath("$.workflowMode").value("BASIC"))
                .andExpect(jsonPath("$.resumable").value(false))
                .andExpect(jsonPath("$.resumeCount").value(1))
                .andExpect(jsonPath("$.lastCompletedNode").value("PROJECT_READY"))
                .andExpect(jsonPath("$.lastFailureNode").isEmpty());
    }

    /**
     * 验证任务不可恢复时返回 409 Conflict。
     */
    @Test
    void resumeUnresumableJobReturnsConflict() throws Exception {
        given(workflowService.resumeJob(JOB_ID))
                .willThrow(new PptJobResumeException(JOB_ID, "job has no completed node to resume from"));

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/resume", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("job has no completed node")));
    }

    /**
     * 验证任务不存在时返回 404 Not Found。
     */
    @Test
    void resumeMissingJobReturnsNotFound() throws Exception {
        given(workflowService.resumeJob(JOB_ID))
                .willThrow(new PptJobNotFoundException(JOB_ID));

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/resume", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmAcceptsStructuredOutlineRevisionPayload() throws Exception {
        PptJob job = new PptJob(
                JOB_ID, "demo", "ppt169", "make a deck",
                PptWorkflowMode.BASIC, Path.of("var/ppt-master/jobs/demo"));
        job.requireConfirmation("c-1", Map.of(
                "stage", "plan_confirmation",
                "contextData", Map.of("type", "ppt_outline")));
        given(workflowService.submitConfirmation(
                eq(JOB_ID), eq("c-1"), eq(true), any(), eq("旧备注"),
                eq(PptConfirmationAction.REQUEST_REVISION), eq("整体修改"), any()))
                .willReturn(job);

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/confirm", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmationId":"c-1",
                                  "approved":true,
                                  "comment":"旧备注",
                                  "action":"REQUEST_REVISION",
                                  "overallComment":"整体修改",
                                  "slideEdits":[{"slideNo":2,"comment":"修改第2页"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentConfirmationId").value("c-1"));

        verify(workflowService).submitConfirmation(
                eq(JOB_ID), eq("c-1"), eq(true), any(), eq("旧备注"),
                eq(PptConfirmationAction.REQUEST_REVISION), eq("整体修改"),
                eq(List.of(new PptSlideEditRequest(2, "修改第2页"))));
    }

    @Test
    void previewLockedOutlineRevisionReturnsImpactToken() throws Exception {
        PptRevisionImpactPreview preview = new PptRevisionImpactPreview(
                "impact-token", 3, List.of("svg_output/slide_01.svg"), java.time.Instant.parse("2026-07-16T06:00:00Z"));
        given(workflowService.previewOutlineRevision(JOB_ID, 3)).willReturn(preview);

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/outline-revision/preview", JOB_ID)
                        .param("outlineVersion", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionImpactToken").value("impact-token"))
                .andExpect(jsonPath("$.outlineVersion").value(3))
                .andExpect(jsonPath("$.affectedArtifacts[0]").value("svg_output/slide_01.svg"));

        verify(workflowService).previewOutlineRevision(JOB_ID, 3);
    }

    @Test
    void confirmPassesStructuredEditVersionAndImpactToken() throws Exception {
        PptJob job = new PptJob(
                JOB_ID, "demo", "ppt169", "make a deck",
                PptWorkflowMode.BASIC, Path.of("var/ppt-master/jobs/demo"));
        job.requireConfirmation("c-1", Map.of(
                "stage", "outline_confirmation",
                "contextData", Map.of("type", "ppt_outline", "version", 1)));
        given(workflowService.submitConfirmation(
                eq(JOB_ID), eq("c-1"), eq(true), any(), eq("更新标题"),
                eq(PptConfirmationAction.REQUEST_REVISION), eq("更新标题"), any(), eq(1), any(), eq("impact-token")))
                .willReturn(job);

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/confirm", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmationId":"c-1",
                                  "approved":true,
                                  "comment":"更新标题",
                                  "action":"REQUEST_REVISION",
                                  "overallComment":"更新标题",
                                  "outlineVersion":1,
                                  "outlineEdits":[{
                                    "operation":"UPDATE",
                                    "slideNo":1,
                                    "slide":{"slideNo":1,"title":"新标题","keyMessage":"新结论",
                                      "bullets":["新要点"],"visualSuggestion":"新视觉方案"}
                                  }],
                                  "revisionImpactToken":"impact-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outlineVersion").value(1));

        verify(workflowService).submitConfirmation(
                eq(JOB_ID), eq("c-1"), eq(true), any(), eq("更新标题"),
                eq(PptConfirmationAction.REQUEST_REVISION), eq("更新标题"), any(), eq(1), any(), eq("impact-token"));
    }

    @Test
    void previewRejectsMismatchedOutlineVersion() throws Exception {
        given(workflowService.previewOutlineRevision(JOB_ID, 2))
                .willThrow(new PptJobStateException("outline version does not match active outline"));

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/outline-revision/preview", JOB_ID)
                        .param("outlineVersion", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("outline version does not match")));
    }

    @Test
    void confirmRejectsInvalidImpactToken() throws Exception {
        given(workflowService.submitConfirmation(
                eq(JOB_ID), eq("c-1"), eq(true), any(), eq("更新标题"),
                eq(PptConfirmationAction.REQUEST_REVISION), eq("更新标题"), any(), eq(1), any(), eq("invalid-token")))
                .willThrow(new PptJobStateException("locked outline revision requires a valid revisionImpactToken"));

        mockMvc.perform(post("/api/ppt-jobs/{jobId}/confirm", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmationId":"c-1",
                                  "approved":true,
                                  "comment":"更新标题",
                                  "action":"REQUEST_REVISION",
                                  "overallComment":"更新标题",
                                  "outlineVersion":1,
                                  "outlineEdits":[{
                                    "operation":"UPDATE",
                                    "slideNo":1,
                                    "slide":{"slideNo":1,"title":"新标题","keyMessage":"新结论",
                                      "bullets":["新要点"],"visualSuggestion":"新视觉方案"}
                                  }],
                                  "revisionImpactToken":"invalid-token"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("valid revisionImpactToken")));
    }
}
