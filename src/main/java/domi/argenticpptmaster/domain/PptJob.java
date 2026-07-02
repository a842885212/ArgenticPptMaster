package domi.argenticpptmaster.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private final Path workspacePath;
    private final Instant createdAt;
    private final List<PptSourceFile> sourceFiles = new ArrayList<>();
    private final List<PptJobEvent> events = new ArrayList<>();
    private PptJobStatus status;
    private Instant updatedAt;
    private Path projectPath;
    private Path exportPath;
    private String currentConfirmationId;
    private Map<String, Object> confirmationPayload = Map.of();
    private PptConfirmation confirmation;
    private String errorMessage;

    /**
     * 创建一个新的 PPT 任务实例。
     *
     * @param id            任务唯一标识
     * @param projectName   项目名称
     * @param format        画布格式（如 ppt169、wechat 等）
     * @param instruction   用户提供的生成指令
     * @param workspacePath 任务工作区路径
     */
    public PptJob(UUID id, String projectName, String format, String instruction, Path workspacePath) {
        this.id = id;
        this.projectName = projectName;
        this.format = format;
        this.instruction = instruction;
        this.workspacePath = workspacePath;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.status = PptJobStatus.ACCEPTED;
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

    public synchronized Optional<PptConfirmation> confirmation() {
        return Optional.ofNullable(confirmation);
    }

    public synchronized Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
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
        touch();
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
        touch();
    }

    /**
     * 任务失败，记录错误信息。
     *
     * @param message 失败原因描述
     */
    public synchronized void fail(String message) {
        this.status = PptJobStatus.FAILED;
        this.errorMessage = message;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
