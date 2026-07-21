package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
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
 * @param template              模板填充任务的模板元数据
 * @param contentSources        模板填充任务的内容来源元数据
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
        TemplateFileResponse template,
        List<SourceFileResponse> contentSources,
        boolean artifactReady,
        String downloadUrl,
        String currentConfirmationId,
        Map<String, Object> confirmationPayload,
        Integer outlineVersion,
        boolean outlineLocked,
        int outlineSlideCount,
        Map<String, Object> outlineDiff,
        Map<String, Object> impactPreview,
        boolean templateAnalysisReady,
        String fillPlanStatus,
        TemplateFillProgressResponse templateFillProgress,
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
        TemplateFillProgressResponse templateProgress = buildTemplateFillProgress(job);
        boolean analysisReady = job.workflowMode() == PptWorkflowMode.TEMPLATE_FILL && job.templateAnalysisReady();
        String planStatus = job.workflowMode() == PptWorkflowMode.TEMPLATE_FILL
                ? job.fillPlanStatus().value()
                : null;
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
                job.template().map(TemplateFileResponse::from).orElse(null),
                job.sourceFiles().stream().map(SourceFileResponse::from).toList(),
                downloadReady,
                downloadReady ? "/api/ppt-jobs/" + job.id() + "/download" : null,
                job.currentConfirmationId().orElse(null),
                job.confirmationPayload(),
                outlineVersion(job.confirmationPayload()),
                outlineLocked(job.confirmationPayload()),
                outlineSlideCount(job.confirmationPayload()),
                outlineDiff(job.confirmationPayload()),
                impactPreview(job.confirmationPayload()),
                analysisReady,
                planStatus,
                templateProgress,
                job.errorMessage().orElse(null),
                job.events());
    }

    private static TemplateFillProgressResponse buildTemplateFillProgress(PptJob job) {
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return null;
        }
        Integer templateSlides = job.templateAnalysisSummary().map(s -> s.templateSlideCount()).orElse(null);
        Integer planSlides = job.planSlideCount() > 0 ? job.planSlideCount() : null;
        Integer warnings = job.validationWarningCount() > 0 ? job.validationWarningCount() : null;
        Integer errors = job.validationErrorCount() > 0 ? job.validationErrorCount() : null;
        String exportName = job.exportPath().map(path -> path.getFileName().toString()).orElse(null);
        Integer notes = job.notesMappingCount() > 0 ? job.notesMappingCount() : null;
        Integer tables = job.tableMappingCount() > 0 ? job.tableMappingCount() : null;
        Integer charts = job.chartMappingCount() > 0 ? job.chartMappingCount() : null;
        Integer capacity = job.capacityRiskCount() > 0 ? job.capacityRiskCount() : null;
        Integer fonts = job.fontAdjustmentCount() > 0 ? job.fontAdjustmentCount() : null;
        String constraintStatus = job.constraintValidationStatus();
        String readbackStatus = job.readbackValidationStatus();
        Integer readbackWarnings = job.readbackWarningCount() > 0 ? job.readbackWarningCount() : null;
        Integer readbackErrors = job.readbackErrorCount() > 0 ? job.readbackErrorCount() : null;
        if (templateSlides == null && planSlides == null && warnings == null && errors == null && exportName == null
                && notes == null && tables == null && charts == null && capacity == null && fonts == null
                && constraintStatus == null && readbackStatus == null) {
            return null;
        }
        return new TemplateFillProgressResponse(
                templateSlides, planSlides, errors, warnings, exportName,
                notes, tables, charts, capacity, fonts, constraintStatus, readbackStatus,
                readbackWarnings, readbackErrors);
    }

    private static Integer outlineVersion(Map<String, Object> payload) {
        Object contextData = payload.get("contextData");
        if (contextData instanceof Map<?, ?> map && map.get("version") instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static boolean outlineLocked(Map<String, Object> payload) {
        Object contextData = payload.get("contextData");
        return contextData instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("locked"));
    }

    private static int outlineSlideCount(Map<String, Object> payload) {
        Object contextData = payload.get("contextData");
        Object slides = contextData instanceof Map<?, ?> map ? map.get("slides") : null;
        return slides instanceof List<?> list ? list.size() : 0;
    }

    private static Map<String, Object> outlineDiff(Map<String, Object> payload) {
        Object contextData = payload.get("contextData");
        if (contextData instanceof Map<?, ?> map && map.get("diff") instanceof Map<?, ?> diff) {
            Map<String, Object> result = new LinkedHashMap<>();
            diff.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> impactPreview(Map<String, Object> payload) {
        Object contextData = payload.get("contextData");
        if (contextData instanceof Map<?, ?> map && map.get("impactPreview") instanceof Map<?, ?> preview) {
            Map<String, Object> result = new LinkedHashMap<>();
            preview.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
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
