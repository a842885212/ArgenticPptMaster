package domi.argenticpptmaster.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PPT 生成任务的领域聚合根。
 * <p>
 * 封装了从任务接受到最终导出的完整生命周期状态转换，
 * 包括源文件管理、事件记录、确认流程、以及项目路径追踪。
 * 所有状态变更方法均为线程安全的（synchronized）。
 * </p>
 *
 * <h3>状态流转</h3>
 * <pre>
 * ACCEPTED → PREPARING → WAITING_CONFIRMATION → RUNNING_AGENT → EXPORTING → COMPLETED
 *                                                                    ↘ FAILED
 * </pre>
 */
public class PptJob {

    private final UUID id;
    private final String projectName;
    private final String format;
    private final String instruction;
    private final PptWorkflowMode workflowMode;
    private final Path workspacePath;
    private final Instant createdAt;
    private final List<PptSourceFile> sourceFiles = new ArrayList<>();
    private final List<PptJobEvent> events = new ArrayList<>();
    private PptTemplateFile template;
    private PptJobStatus status;
    private Instant updatedAt;
    private Path projectPath;
    private Path exportPath;
    private String currentConfirmationId;
    private Map<String, Object> confirmationPayload = Map.of();
    private PptConfirmation confirmation;
    private String errorMessage;

    // 节点级 checkpoint 状态（无数据库版，仅内存内有效）
    private final Map<PptJobNode, PptNodeExecution> nodeExecutions = new EnumMap<>(PptJobNode.class);
    private PptJobNode currentNode;
    private PptJobNode lastCompletedNode;
    private PptJobNode lastFailureNode;
    private String activeAttemptSessionId;
    private int resumeCount;

    // 模板填充分析与计划状态（安全摘要，不含路径或槽位全文）
    private TemplateFillAnalysisSummary templateAnalysisSummary;
    private FillPlanStatus fillPlanStatus = FillPlanStatus.NONE;
    private int planSlideCount;
    private int validationWarningCount;
    private int validationErrorCount;
    private String templateFillRevisionFeedback;
    private boolean templateFillExecutionClaimed;
    private TemplateFillConstraints templateConstraints = TemplateFillConstraints.empty();
    private int notesMappingCount;
    private int tableMappingCount;
    private int chartMappingCount;
    private int capacityRiskCount;
    private int fontAdjustmentCount;
    private String constraintValidationStatus;
    private String readbackValidationStatus;
    private int readbackWarningCount;
    private int readbackErrorCount;
    private String ownerSubjectId;
    private String ownerTenantId;
    private Instant terminalAt;

    /**
     * 创建一个新的 PPT 任务实例。
     *
     * @param id            任务唯一标识
     * @param projectName   项目名称
     * @param format        画布格式（如 ppt169、wechat 等）
     * @param instruction   用户提供的生成指令
     * @param workflowMode  工作流模式
     * @param workspacePath 任务工作区路径
     */
    public PptJob(UUID id, String projectName, String format, String instruction, PptWorkflowMode workflowMode, Path workspacePath) {
        this.id = id;
        this.projectName = projectName;
        this.format = format;
        this.instruction = instruction;
        this.workflowMode = workflowMode == null ? PptWorkflowMode.BASIC : workflowMode;
        this.workspacePath = workspacePath;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.status = PptJobStatus.ACCEPTED;
        this.activeAttemptSessionId = buildAttemptSessionId(0);
        initializeNodeExecutions();
    }

    private void initializeNodeExecutions() {
        for (PptJobNode node : PptJobNode.values()) {
            if (node.applicableTo(workflowMode)) {
                nodeExecutions.put(node, PptNodeExecution.pending(node));
            }
        }
    }

    private static String buildAttemptSessionId(int attempt) {
        return "attempt-" + attempt;
    }

    public synchronized UUID id() {
        return id;
    }

    public synchronized String projectName() {
        return projectName;
    }

    public synchronized String format() {
        return format;
    }

    public synchronized String instruction() {
        return instruction;
    }

    public synchronized PptWorkflowMode workflowMode() {
        return workflowMode;
    }

    public synchronized Path workspacePath() {
        return workspacePath;
    }

    public synchronized Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    public synchronized PptJobStatus status() {
        return status;
    }

    public synchronized List<PptSourceFile> sourceFiles() {
        return List.copyOf(sourceFiles);
    }

    public synchronized Optional<PptTemplateFile> template() {
        return Optional.ofNullable(template);
    }

    public synchronized List<PptJobEvent> events() {
        return List.copyOf(events);
    }

    public synchronized Optional<Path> projectPath() {
        return Optional.ofNullable(projectPath);
    }

    public synchronized Optional<Path> exportPath() {
        return Optional.ofNullable(exportPath);
    }

    public synchronized Optional<String> currentConfirmationId() {
        return Optional.ofNullable(currentConfirmationId);
    }

    public synchronized Map<String, Object> confirmationPayload() {
        return confirmationPayload;
    }

    /** 更新当前确认载荷中的版本化大纲快照。 */
    public synchronized void updateConfirmationPayload(Map<String, Object> payload) {
        this.confirmationPayload = payload == null ? Map.of() : Map.copyOf(payload);
        touch();
    }

    public synchronized Optional<PptConfirmation> confirmation() {
        return Optional.ofNullable(confirmation);
    }

    public synchronized Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public synchronized Optional<PptJobNode> currentNode() {
        return Optional.ofNullable(currentNode);
    }

    public synchronized Optional<PptJobNode> lastCompletedNode() {
        return Optional.ofNullable(lastCompletedNode);
    }

    public synchronized Optional<PptJobNode> lastFailureNode() {
        return Optional.ofNullable(lastFailureNode);
    }

    public synchronized int resumeCount() {
        return resumeCount;
    }

    public synchronized boolean templateAnalysisReady() {
        return templateAnalysisSummary != null;
    }

    public synchronized Optional<TemplateFillAnalysisSummary> templateAnalysisSummary() {
        return Optional.ofNullable(templateAnalysisSummary);
    }

    public synchronized FillPlanStatus fillPlanStatus() {
        return fillPlanStatus;
    }

    public synchronized int planSlideCount() {
        return planSlideCount;
    }

    public synchronized int validationWarningCount() {
        return validationWarningCount;
    }

    public synchronized int validationErrorCount() {
        return validationErrorCount;
    }

    public synchronized Optional<String> templateFillRevisionFeedback() {
        return Optional.ofNullable(templateFillRevisionFeedback);
    }

    public synchronized void updateTemplateAnalysis(TemplateFillAnalysisSummary summary) {
        this.templateAnalysisSummary = summary;
        touch();
    }

    public synchronized void updateFillPlanStatus(FillPlanStatus status, int planSlides, int warnings, int errors) {
        this.fillPlanStatus = status == null ? FillPlanStatus.NONE : status;
        this.planSlideCount = planSlides;
        this.validationWarningCount = warnings;
        this.validationErrorCount = errors;
        touch();
    }

    public synchronized TemplateFillConstraints templateConstraints() {
        return templateConstraints == null ? TemplateFillConstraints.empty() : templateConstraints;
    }

    public synchronized void assignTemplateConstraints(TemplateFillConstraints constraints) {
        this.templateConstraints = constraints == null ? TemplateFillConstraints.empty() : constraints;
        touch();
    }

    /**
     * 绑定不可变的任务归属。仅允许设置一次。
     */
    public synchronized void assignOwnership(String subjectId, String tenantId) {
        if (ownerSubjectId != null || ownerTenantId != null) {
            throw new IllegalStateException("ownership already assigned");
        }
        if (subjectId == null || subjectId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("subjectId and tenantId are required");
        }
        this.ownerSubjectId = subjectId.trim();
        this.ownerTenantId = tenantId.trim();
        touch();
    }

    public synchronized Optional<String> ownerSubjectId() {
        return Optional.ofNullable(ownerSubjectId);
    }

    public synchronized Optional<String> ownerTenantId() {
        return Optional.ofNullable(ownerTenantId);
    }

    public synchronized Optional<Instant> terminalAt() {
        return Optional.ofNullable(terminalAt);
    }

    public synchronized boolean hasOwnership() {
        return ownerSubjectId != null && ownerTenantId != null;
    }

    public synchronized int notesMappingCount() {
        return notesMappingCount;
    }

    public synchronized int tableMappingCount() {
        return tableMappingCount;
    }

    public synchronized int chartMappingCount() {
        return chartMappingCount;
    }

    public synchronized int capacityRiskCount() {
        return capacityRiskCount;
    }

    public synchronized int fontAdjustmentCount() {
        return fontAdjustmentCount;
    }

    public synchronized String constraintValidationStatus() {
        return constraintValidationStatus;
    }

    public synchronized String readbackValidationStatus() {
        return readbackValidationStatus;
    }

    public synchronized int readbackWarningCount() {
        return readbackWarningCount;
    }

    public synchronized int readbackErrorCount() {
        return readbackErrorCount;
    }

    public synchronized void updateNativePlanAggregates(
            int notesMappings,
            int tableMappings,
            int chartMappings,
            int capacityRisks,
            int fontAdjustments,
            String constraintStatus) {
        this.notesMappingCount = Math.max(0, notesMappings);
        this.tableMappingCount = Math.max(0, tableMappings);
        this.chartMappingCount = Math.max(0, chartMappings);
        this.capacityRiskCount = Math.max(0, capacityRisks);
        this.fontAdjustmentCount = Math.max(0, fontAdjustments);
        this.constraintValidationStatus = constraintStatus;
        touch();
    }

    public synchronized void updateReadbackValidation(String status, int warnings, int errors) {
        this.readbackValidationStatus = status;
        this.readbackWarningCount = Math.max(0, warnings);
        this.readbackErrorCount = Math.max(0, errors);
        touch();
    }

    public synchronized String activeAttemptSessionId() {
        return activeAttemptSessionId;
    }

    public synchronized Map<PptJobNode, PptNodeExecution> nodeExecutions() {
        return Map.copyOf(nodeExecutions);
    }

    public synchronized PptNodeExecution nodeExecution(PptJobNode node) {
        return nodeExecutions.get(node);
    }

    /**
     * 添加源文件到任务，同时更新时间戳。
     *
     * @param sourceFile 上传的源文件信息
     */
    public synchronized void addSource(PptSourceFile sourceFile) {
        sourceFiles.add(sourceFile);
        touch();
    }

    /**
     * 设置模板填充任务的唯一模板。
     *
     * @param templateFile 模板文件
     * @throws IllegalStateException 如果任务已有模板
     */
    public synchronized void setTemplate(PptTemplateFile templateFile) {
        if (template != null) {
            throw new IllegalStateException("template file is already set");
        }
        if (templateFile == null) {
            throw new IllegalArgumentException("template file is required");
        }
        template = templateFile;
        touch();
    }

    /**
     * 记录任务事件并更新时间戳。
     *
     * @param event 任务事件
     */
    public synchronized void addEvent(PptJobEvent event) {
        events.add(event);
        touch();
    }

    /**
     * 进入项目准备阶段，记录项目路径。
     *
     * @param preparedProjectPath 准备好的项目目录路径
     */
    public synchronized void prepareProject(Path preparedProjectPath) {
        this.status = PptJobStatus.PREPARING;
        this.projectPath = preparedProjectPath;
        touch();
    }

    /**
     * 原子地领取一次模板填充执行，避免同一任务被并发提交多次。
     *
     * @return 仅当任务仍处于 ACCEPTED 且为模板填充模式时返回 true
     */
    public synchronized boolean tryStartTemplateFill() {
        if (workflowMode != PptWorkflowMode.TEMPLATE_FILL || status != PptJobStatus.ACCEPTED) {
            return false;
        }
        status = PptJobStatus.PREPARING;
        touch();
        return true;
    }

    public synchronized boolean tryStartPrepare() {
        if (workflowMode != PptWorkflowMode.TEMPLATE_FILL) {
            return false;
        }
        if (status == PptJobStatus.ACCEPTED) {
            return tryStartTemplateFill();
        }
        if (status == PptJobStatus.FAILED) {
            status = PptJobStatus.PREPARING;
            lastFailureNode = null;
            touch();
            return true;
        }
        return false;
    }

    /**
     * 在模板分析完成后启动计划 Agent。
     */
    public synchronized boolean tryStartTemplateFillPlanning() {
        if (workflowMode != PptWorkflowMode.TEMPLATE_FILL) {
            return false;
        }
        if (status != PptJobStatus.PREPARING && status != PptJobStatus.ACCEPTED) {
            return false;
        }
        PptNodeExecution analyzed = nodeExecutions.get(PptJobNode.TEMPLATE_ANALYZED);
        if (analyzed == null || analyzed.status() != PptJobNodeStatus.COMPLETED) {
            return false;
        }
        if (fillPlanStatus == FillPlanStatus.CONFIRMED || fillPlanStatus == FillPlanStatus.VALIDATED) {
            return false;
        }
        status = PptJobStatus.RUNNING_AGENT;
        currentNode = PptJobNode.FILL_PLAN_DRAFTED;
        touch();
        return true;
    }

    /** 人工批准 confirmed plan 后领取原生执行。 */
    public synchronized boolean markTemplateFillExecutionStarted() {
        if (workflowMode != PptWorkflowMode.TEMPLATE_FILL) {
            return false;
        }
        if (fillPlanStatus != FillPlanStatus.CONFIRMED) {
            return false;
        }
        if (templateFillExecutionClaimed
                || status == PptJobStatus.EXPORTING
                || status == PptJobStatus.COMPLETED) {
            return false;
        }
        templateFillExecutionClaimed = true;
        status = PptJobStatus.PREPARING;
        touch();
        return true;
    }

    /** 用户要求修订计划时重置草拟节点并保留有界反馈。 */
    public synchronized void resetFillPlanDrafting(String feedback) {
        if (workflowMode != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        nodeExecutions.put(PptJobNode.FILL_PLAN_DRAFTED, PptNodeExecution.pending(PptJobNode.FILL_PLAN_DRAFTED));
        nodeExecutions.put(PptJobNode.FILL_PLAN_CONFIRMED, PptNodeExecution.pending(PptJobNode.FILL_PLAN_CONFIRMED));
        fillPlanStatus = FillPlanStatus.NONE;
        planSlideCount = 0;
        templateFillRevisionFeedback = boundedFeedback(feedback);
        currentConfirmationId = null;
        confirmationPayload = Map.of();
        confirmation = null;
        currentNode = PptJobNode.FILL_PLAN_DRAFTED;
        status = PptJobStatus.PREPARING;
        touch();
    }

    private static String boundedFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return null;
        }
        String trimmed = feedback.trim();
        return trimmed.length() <= 2_000 ? trimmed : trimmed.substring(0, 2_000);
    }

    /**
     * 进入等待人工确认状态，记录确认 ID 和上下文载荷。
     *
     * @param confirmationId 确认请求的唯一标识
     * @param payload        确认上下文字段（包含待确认的工具调用信息）
     */
    public synchronized void requireConfirmation(String confirmationId, Map<String, Object> payload) {
        this.status = PptJobStatus.WAITING_CONFIRMATION;
        this.currentConfirmationId = confirmationId;
        this.confirmationPayload = payload == null ? Map.of() : Map.copyOf(payload);
        touch();
    }

    /**
     * 收到人工确认后，清除待确认状态并进入代理运行阶段。
     *
     * @param receivedConfirmation 用户提交的确认结果
     */
    public synchronized void receiveConfirmation(PptConfirmation receivedConfirmation) {
        this.confirmation = receivedConfirmation;
        this.currentConfirmationId = null;
        this.status = PptJobStatus.RUNNING_AGENT;
        touch();
    }

    /**
     * 标记代理开始运行。
     */
    public synchronized void startAgent() {
        this.status = PptJobStatus.RUNNING_AGENT;
        if (this.currentNode == null) {
            this.currentNode = resolveFirstNode();
        }
        touch();
    }

    private PptJobNode resolveFirstNode() {
        return PptJobNode.PROJECT_READY;
    }

    /**
     * 进入导出阶段。
     */
    public synchronized void startExport() {
        this.status = PptJobStatus.EXPORTING;
        touch();
    }

    /**
     * 完成导出，标记任务为已完成。
     *
     * @param completedExportPath 最终导出的文件路径
     */
    public synchronized void complete(Path completedExportPath) {
        this.status = PptJobStatus.COMPLETED;
        this.exportPath = completedExportPath;
        markTerminal();
        touch();
    }

    /**
     * 任务失败，记录错误信息。
     * <p>
     * 如果当前存在正在执行的 {@code currentNode}，则同时将该节点标记为失败，
     * 确保 {@code lastFailureNode} 与 {@code nodeStates} 能反映真实失败位置。
     * </p>
     *
     * @param message 失败原因描述
     */
    public synchronized void fail(String message) {
        this.status = PptJobStatus.FAILED;
        this.errorMessage = message;
        if (this.currentNode != null) {
            updateNode(this.currentNode, currentExecution(this.currentNode).fail(message));
            this.lastFailureNode = this.currentNode;
            this.currentNode = null;
        }
        markTerminal();
        touch();
    }

    /**
     * 标记指定节点开始执行。
     *
     * @param node 业务节点
     */
    public synchronized void startNode(PptJobNode node) {
        this.currentNode = node;
        updateNode(node, currentExecution(node).start());
        touch();
    }

    /**
     * 标记指定节点已完成，并可附带结构化摘要。
     *
     * @param node    业务节点
     * @param summary 完成摘要
     */
    public synchronized void completeNode(PptJobNode node, Map<String, Object> summary) {
        updateNode(node, currentExecution(node).complete(summary));
        this.lastCompletedNode = node;
        if (this.currentNode == node) {
            this.currentNode = null;
        }
        touch();
    }

    /**
     * 标记指定节点失败。
     *
     * @param node    业务节点
     * @param message 失败原因
     */
    public synchronized void failNode(PptJobNode node, String message) {
        updateNode(node, currentExecution(node).fail(message));
        this.lastFailureNode = node;
        this.status = PptJobStatus.FAILED;
        this.errorMessage = message;
        if (this.currentNode == node) {
            this.currentNode = null;
        }
        markTerminal();
        touch();
    }

    /**
     * 标记指定节点进入等待人工确认状态。
     *
     * @param node 业务节点
     */
    public synchronized void waitNodeConfirmation(PptJobNode node) {
        updateNode(node, currentExecution(node).waitForConfirmation());
        this.currentNode = node;
        touch();
    }

    /**
     * 在收到人工确认后，推进确认类节点到已完成状态。
     *
     * @param node 业务节点
     */
    public synchronized void confirmNode(PptJobNode node) {
        completeNode(node, Map.of());
    }

    /** 锁定大纲启动再修订时，使大纲确认之后的节点重新进入待执行状态。 */
    public synchronized void invalidateAfterOutlineRevision() {
        for (PptJobNode node : PptJobNode.values()) {
            if (node.ordinal() >= PptJobNode.OUTLINE_CONFIRMED.ordinal()
                    && node.applicableTo(workflowMode)) {
                nodeExecutions.put(node, PptNodeExecution.pending(node));
            }
        }
        currentNode = PptJobNode.OUTLINE_DRAFTED;
        lastCompletedNode = PptJobNode.OUTLINE_DRAFTED;
        status = PptJobStatus.WAITING_CONFIRMATION;
        touch();
    }

    /**
     * 启动一次新的恢复尝试。
     * <p>
     * 失败后的恢复会启用新的 attempt session，以避免旧失败上下文污染新执行。
     * </p>
     */
    public synchronized void startNewResumeAttempt() {
        this.resumeCount++;
        this.activeAttemptSessionId = buildAttemptSessionId(resumeCount);
        this.status = workflowMode == PptWorkflowMode.TEMPLATE_FILL
                ? PptJobStatus.PREPARING
                : PptJobStatus.RUNNING_AGENT;
        this.lastFailureNode = null;
        touch();
    }

    /**
     * 原子地尝试启动一次新的恢复尝试。
     * <p>
     * 在同一个 synchronized 块内完成所有恢复前置校验与状态迁移，避免并发调用
     * {@code /resume} 时出现多次恢复竞态。
     * </p>
     *
     * @param maxAttempts 允许的最大恢复次数
     * @return 恢复失败原因；若成功则返回 null
     */
    public synchronized String tryStartResumeAttempt(int maxAttempts) {
        if (status == PptJobStatus.WAITING_CONFIRMATION) {
            return "job is waiting for confirmation; use /confirm instead";
        }
        if (status != PptJobStatus.FAILED) {
            return "job is not in failed state: " + status;
        }
        if (lastCompletedNode == null) {
            return "job has no completed node to resume from";
        }
        if (resumeCount >= maxAttempts) {
            return "maximum resume attempts reached: " + maxAttempts;
        }
        startNewResumeAttempt();
        return null;
    }

    /**
     * 判断当前任务是否可以从失败中恢复。
     * <p>
     * 仅当任务处于 {@link PptJobStatus#FAILED}，且存在至少一个适用于当前模式的成功节点时返回 true。
     * </p>
     *
     * @return true 表示可恢复
     */
    public synchronized boolean resumable() {
        if (status != PptJobStatus.FAILED) {
            return false;
        }
        return lastCompletedNode != null;
    }

    private PptNodeExecution currentExecution(PptJobNode node) {
        PptNodeExecution execution = nodeExecutions.get(node);
        if (execution == null) {
            execution = PptNodeExecution.pending(node);
            nodeExecutions.put(node, execution);
        }
        return execution;
    }

    private void updateNode(PptJobNode node, PptNodeExecution execution) {
        nodeExecutions.put(node, execution);
    }

    private void markTerminal() {
        if (this.terminalAt == null) {
            this.terminalAt = Instant.now();
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
