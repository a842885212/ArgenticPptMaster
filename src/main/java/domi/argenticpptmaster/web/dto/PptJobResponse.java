package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PPT 任务响应 DTO。
 * <p>
 * 用于向客户端返回 PPT 生成任务的完整状态和详细信息，
 * 包含任务标识、项目名称、格式、状态、源文件列表、下载信息、确认状态、
 * 节点级 checkpoint 状态以及事件列表。
 * </p>
 *
 * @param id                    任务唯一标识符
 * @param projectName           项目名称
 * @param format                PPT 输出格式
 * @param status                任务当前状态
 * @param workflowMode          工作流模式
 * @param currentNode           当前正在执行或等待确认的节点
 * @param lastCompletedNode     最近成功完成的节点
 * @param lastFailureNode       最近失败的节点
 * @param resumeCount           已恢复次数
 * @param resumable             是否可以从失败中恢复
 * @param nodeStates            每个业务节点的执行状态
 * @param createdAt             任务创建时间
 * @param updatedAt             任务最后更新时间
 * @param sources               上传的源文件列表
 * @param artifactReady         生成产物是否已就绪
 * @param downloadUrl           下载链接（就绪时非空）
 * @param currentConfirmationId 当前待确认项的 ID
 * @param confirmationPayload   确认相关的结构化负载数据
 * @param errorMessage          错误信息（任务失败时非空）
 * @param events                任务相关的事件列表
 * @author zhangtianhao
 * @since 2026-07-09
 */
public record PptJobResponse(
        UUID id,
        String projectName,
        String format,
        PptJobStatus status,
        String workflowMode,
        PptJobNode currentNode,
        PptJobNode lastCompletedNode,
        PptJobNode lastFailureNode,
        int resumeCount,
        boolean resumable,
        Map<String, NodeStateResponse> nodeStates,
        Instant createdAt,
        Instant updatedAt,
        List<SourceFileResponse> sources,
        boolean artifactReady,
        String downloadUrl,
        String currentConfirmationId,
        Map<String, Object> confirmationPayload,
        String errorMessage,
        List<PptJobEvent> events) {

    /**
     * 将领域模型 {@link PptJob} 转换为响应 DTO。
     *
     * @param job PPT 任务领域对象
     * @return 转换后的 PPT 任务响应 DTO
     */
    public static PptJobResponse from(PptJob job) {
        boolean downloadReady = job.status() == PptJobStatus.COMPLETED && job.exportPath().isPresent();
        return new PptJobResponse(
                job.id(),
                job.projectName(),
                job.format(),
                job.status(),
                job.workflowMode().name(),
                job.currentNode().orElse(null),
                job.lastCompletedNode().orElse(null),
                job.lastFailureNode().orElse(null),
                job.resumeCount(),
                job.resumable(),
                buildNodeStates(job),
                job.createdAt(),
                job.updatedAt(),
                job.sourceFiles().stream().map(SourceFileResponse::from).toList(),
                downloadReady,
                downloadReady ? "/api/ppt-jobs/" + job.id() + "/download" : null,
                job.currentConfirmationId().orElse(null),
                job.confirmationPayload(),
                job.errorMessage().orElse(null),
                job.events());
    }

    private static Map<String, NodeStateResponse> buildNodeStates(PptJob job) {
        Map<String, NodeStateResponse> result = new LinkedHashMap<>();
        for (PptJobNode node : PptJobNode.values()) {
            if (!node.applicableTo(job.workflowMode())) {
                continue;
            }
            PptNodeExecution execution = job.nodeExecution(node);
            PptJobNodeStatus status = execution == null ? PptJobNodeStatus.PENDING : execution.status();
            Instant startedAt = execution == null ? null : execution.startedAt();
            Instant completedAt = execution == null ? null : execution.completedAt();
            String error = execution == null ? null : execution.errorMessage();
            result.put(node.name(), new NodeStateResponse(
                    node.name(),
                    status,
                    startedAt,
                    completedAt,
                    error));
        }
        return result;
    }

    /**
     * 单个业务节点的响应状态。
     *
     * @param node         节点名
     * @param status       节点执行状态
     * @param startedAt    开始时间
     * @param completedAt  完成或失败时间
     * @param errorMessage 失败原因
     */
    public record NodeStateResponse(
            String node,
            PptJobNodeStatus status,
            Instant startedAt,
            Instant completedAt,
            String errorMessage) {
    }
}
