package domi.argenticpptmaster.web;

import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowService;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.web.dto.ConfirmationRequest;
import domi.argenticpptmaster.web.dto.PptJobResponse;
import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * PPT 任务 REST API 控制器。
 * <p>
 * 提供 PPT 生成任务的完整生命周期管理端点，包括：
 * <ul>
 *   <li>创建任务（上传源文件及指定项目名称、格式和指令）</li>
 *   <li>查询任务状态</li>
 *   <li>订阅任务事件（SSE 实时推送）</li>
 *   <li>确认任务结果</li>
 *   <li>下载生成的 PPT 文件</li>
 * </ul>
 * 所有端点均以 {@code /api/ppt-jobs} 为前缀。
 * </p>
 */
@RestController
@RequestMapping("/api/ppt-jobs")
public class PptJobController {

    private final PptWorkflowService workflowService;
    private final PptJobEventPublisher eventPublisher;

    public PptJobController(PptWorkflowService workflowService, PptJobEventPublisher eventPublisher) {
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建新的 PPT 生成任务。
     * <p>接收用户上传的多个源文件、项目名称、输出格式及可选指令，异步启动 PPT 生成工作流。</p>
     *
     * @param files       上传的源文件列表（支持 Markdown 等格式）
     * @param projectName 可选的项目名称
     * @param format      PPT 输出格式，默认为 {@code ppt169}
     * @param instruction 可选的生成指令，用于指导 PPT 内容生成
     * @return 包含已创建任务信息的 HTTP 202 响应
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PptJobResponse> create(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) String projectName,
            @RequestParam(defaultValue = "ppt169") String format,
            @RequestParam(required = false) String instruction,
            @RequestParam(defaultValue = "basic") String workflowMode) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PptJobResponse.from(workflowService.createJob(files, projectName, format, instruction, workflowMode)));
    }

    /**
     * 根据任务 ID 查询 PPT 任务的当前状态和详细信息。
     *
     * @param jobId 任务 UUID
     * @return 任务响应 DTO，包含任务状态、源文件、事件等信息
     */
    @GetMapping("/{jobId}")
    public PptJobResponse get(@PathVariable UUID jobId) {
        return PptJobResponse.from(workflowService.getJob(jobId));
    }

    /**
     * 订阅指定任务的 Server-Sent Events (SSE) 事件流。
     * <p>
     * 客户端可通过此端点实时接收 PPT 生成过程中的状态变更通知。
     * 服务端会在建立连接时立即回放任务已有的历史事件；
     * 若任务已处于终态（完成、失败或取消），则立即关闭连接，
     * 避免客户端在无新事件时长时间挂起。
     * </p>
     *
     * @param jobId 任务 UUID
     * @return SSE 发射器，用于推送事件流
     */
    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId) {
        PptJob job = workflowService.getJob(jobId);
        return eventPublisher.subscribe(job);
    }

    /**
     * 确认 PPT 计划或提交逐页大纲修订。
     * <p>用户可通过此端点提交确认 ID、操作意图、问答答案和大纲反馈。</p>
     *
     * @param jobId   任务 UUID
     * @param request 确认请求体，包含确认 ID、审批状态、答案和评论
     * @return 更新后的任务信息响应 DTO
     */
    @PostMapping("/{jobId}/confirm")
    public PptJobResponse confirm(@PathVariable UUID jobId, @Valid @RequestBody ConfirmationRequest request) {
        PptJob job = request.outlineVersion() == null && request.outlineEdits().isEmpty()
                ? workflowService.submitConfirmation(jobId, request.confirmationId(), request.approved(),
                        request.answers(), request.comment(), request.action(), request.overallComment(), request.slideEdits())
                : workflowService.submitConfirmation(jobId, request.confirmationId(), request.approved(),
                        request.answers(), request.comment(), request.action(), request.overallComment(), request.slideEdits(),
                        request.outlineVersion(), request.outlineEdits());
        return PptJobResponse.from(job);
    }

    /**
     * 从上一个成功节点恢复失败任务的执行。
     * <p>
     * 仅当任务当前处于失败状态且存在可恢复的成功节点时才允许调用。
     * 恢复请求被接受后，任务会进入新的 attempt 并异步从 checkpoint 继续。
     * </p>
     *
     * @param jobId 任务 UUID
     * @return 更新后的任务信息响应 DTO，包含新的节点恢复状态
     */
    @PostMapping("/{jobId}/resume")
    public PptJobResponse resume(@PathVariable UUID jobId) {
        return PptJobResponse.from(workflowService.resumeJob(jobId));
    }

    /**
     * 下载生成的 PPT 文件。
     * <p>根据任务 ID 获取导出文件路径，以附件形式返回给客户端。</p>
     *
     * @param jobId 任务 UUID
     * @return 包含 PPT 文件资源的 HTTP 200 响应
     */
    @GetMapping("/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        Path exportPath = workflowService.exportPath(jobId);
        Resource resource = new FileSystemResource(exportPath);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportPath.getFileName() + "\"")
                .body(resource);
    }
}
