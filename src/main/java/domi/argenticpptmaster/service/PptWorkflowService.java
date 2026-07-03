package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptStorageException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * PPT 任务工作流核心服务。
 * <p>
 * 编排 PPT 生成任务的完整生命周期：
 * <ol>
 *   <li>创建任务——接收上传文件、校验格式、持久化源文件</li>
 *   <li>查询任务状态</li>
 *   <li>提交人工确认——批准或拒绝执行方案</li>
 *   <li>获取导出文件路径</li>
 * </ol>
 * 所有操作委派给 {@link PptWorkflowAsyncRunner} 异步执行 AI 代理工作，
 * 并通过 {@link PptWorkflowEvents} 记录和推送事件。
 * </p>
 */
@Service
public class PptWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PptWorkflowService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "md", "markdown", "pdf", "ppt", "pptx", "doc", "docx", "xls", "xlsx", "xlsm", "csv", "tsv", "html");
    private static final Map<String, String> CANVAS_FORMATS = Map.ofEntries(
            Map.entry("ppt169", "ppt169"),
            Map.entry("ppt43", "ppt43"),
            Map.entry("wechat", "wechat"),
            Map.entry("xiaohongshu", "xiaohongshu"),
            Map.entry("xhs", "xiaohongshu"),
            Map.entry("小红书", "xiaohongshu"),
            Map.entry("moments", "moments"),
            Map.entry("story", "story"),
            Map.entry("banner", "banner"),
            Map.entry("a4", "a4"));

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;
    private final PptWorkflowAsyncRunner asyncRunner;

    public PptWorkflowService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptWorkflowAsyncRunner asyncRunner) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.asyncRunner = asyncRunner;
    }

    /**
     * 创建 PPT 生成任务。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>校验至少包含一个非空源文件</li>
     *   <li>规范化项目名称和画布格式</li>
     *   <li>创建工作区目录</li>
     *   <li>持久化上传的源文件到磁盘</li>
     *   <li>异步启动 AI 代理</li>
     * </ol>
     * </p>
     *
     * @param files       上传的源文件列表，至少需要一个非空文件
     * @param projectName 项目名称，为空时自动生成为 "ppt_project"
     * @param format      画布格式，支持 ppt169、wechat、xiaohongshu 等
     * @param instruction 用户提供的生成指令
     * @return 已创建的任务实例
     * @throws PptJobStateException 如果文件或格式校验不通过
     * @throws PptStorageException  如果文件存储失败
     */
    public PptJob createJob(List<MultipartFile> files, String projectName, String format, String instruction) {
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new PptJobStateException("at least one source file is required");
        }
        UUID jobId = UUID.randomUUID();
        String normalizedProjectName = normalizeProjectName(projectName);
        String normalizedFormat = normalizeFormat(format);
        Path jobWorkspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString()).toAbsolutePath().normalize();
        PptJob job = new PptJob(jobId, normalizedProjectName, normalizedFormat, instruction, jobWorkspace);
        log.info("ppt_job_create_started: jobId={}, projectName={}, format={}, fileCount={}",
                jobId, normalizedProjectName, normalizedFormat, files.size());
        storeSources(job, files);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_ACCEPTED, "job accepted",
                Map.of("jobId", job.id().toString())));
        asyncRunner.startAgent(job.id());
        log.info("ppt_job_create_accepted: jobId={}, projectName={}, format={}, sourceCount={}",
                jobId, normalizedProjectName, normalizedFormat, job.sourceFiles().size());
        return job;
    }

    /**
     * 根据 ID 查询任务。
     *
     * @param jobId 任务 ID
     * @return 任务实例
     * @throws PptJobNotFoundException 如果任务不存在
     */
    public PptJob getJob(UUID jobId) {
        return repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
    }

    /**
     * 提交人工确认。
     * <p>
     * 验证当前任务状态为 {@link PptJobStatus#WAITING_CONFIRMATION}，
     * 且传入的 confirmationId 与待确认的 ID 一致。
     * 如果用户拒绝确认，任务直接标记为失败。
     * 如果用户批准，则异步恢复 AI 代理执行。
     * </p>
     *
     * @param jobId          任务 ID
     * @param confirmationId 确认请求 ID，需与任务当前的确认 ID 一致
     * @param approved       是否批准
     * @param answers        用户补充答案
     * @param comment        用户备注
     * @return 更新后的任务实例
     * @throws PptJobStateException 如果状态不合法或 confirmationId 不匹配
     */
    public PptJob submitConfirmation(
            UUID jobId,
            String confirmationId,
            boolean approved,
            Map<String, Object> answers,
            String comment) {
        PptJob job = getJob(jobId);
        log.info("ppt_job_confirmation_submit_received: jobId={}, confirmationId={}, currentStatus={}, approved={}",
                jobId, confirmationId, job.status(), approved);
        if (job.status() != PptJobStatus.WAITING_CONFIRMATION) {
            log.warn("ppt_job_confirmation_status_mismatch: jobId={}, currentStatus={}", jobId, job.status());
            throw new PptJobStateException("job is not waiting for confirmation");
        }
        String expectedConfirmationId = job.currentConfirmationId()
                .orElseThrow(() -> new PptJobStateException("job has no active confirmation"));
        if (!expectedConfirmationId.equals(confirmationId)) {
            log.warn("ppt_job_confirmation_id_mismatch: jobId={}, expectedConfirmationId={}, receivedConfirmationId={}",
                    jobId, expectedConfirmationId, confirmationId);
            throw new PptJobStateException("confirmationId does not match active confirmation");
        }
        if (!approved) {
            String rejectReason = "confirmation rejected: " + (comment == null ? "" : comment);
            log.warn("ppt_job_confirmation_rejected: jobId={}, confirmationId={}, commentPresent={}, commentLength={}",
                    jobId, confirmationId, comment != null && !comment.isBlank(),
                    comment == null ? 0 : comment.length());
            job.fail(rejectReason);
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "confirmation rejected"));
            return job;
        }
        PptConfirmation confirmation = new PptConfirmation(
                confirmationId,
                true,
                answers == null ? Map.of() : Map.copyOf(answers),
                comment,
                Instant.now());
        job.receiveConfirmation(confirmation);
        events.record(job, PptJobEvent.of(PptJobEventType.CONFIRMATION_RECEIVED, "confirmation received",
                Map.of("confirmationId", confirmationId)));
        log.info("ppt_job_confirmation_approved: jobId={}, confirmationId={}, answersKeys={}",
                jobId, confirmationId, confirmation.answers().keySet());
        asyncRunner.resumeAgent(job.id());
        return job;
    }

    /**
     * 获取已完成任务的导出文件路径。
     *
     * @param jobId 任务 ID
     * @return 导出文件的绝对路径
     * @throws PptJobStateException 如果任务未完成或无导出产物
     */
    public Path exportPath(UUID jobId) {
        PptJob job = getJob(jobId);
        if (job.status() != PptJobStatus.COMPLETED) {
            throw new PptJobStateException("job is not completed");
        }
        return job.exportPath().orElseThrow(() -> new PptJobStateException("job has no export artifact"));
    }

    private void storeSources(PptJob job, List<MultipartFile> files) {
        Path uploadDir = job.workspacePath().resolve("uploads");
        try {
            Files.createDirectories(uploadDir);
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    continue;
                }
                String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null
                        ? "source"
                        : file.getOriginalFilename());
                validateExtension(originalName);
                Path storedPath = uploadDir.resolve(job.sourceFiles().size() + "-" + originalName).normalize();
                if (!storedPath.startsWith(uploadDir)) {
                    throw new PptJobStateException("invalid file name: " + originalName);
                }
                try (InputStream inputStream = file.getInputStream()) {
                    Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                PptSourceFile sourceFile = new PptSourceFile(
                        originalName,
                        file.getContentType(),
                        file.getSize(),
                        storedPath);
                job.addSource(sourceFile);
                events.record(job, PptJobEvent.of(PptJobEventType.SOURCE_STORED, "source file stored",
                        Map.of("fileName", originalName, "size", file.getSize())));
            }
        } catch (IOException ex) {
            throw new PptStorageException("failed to store source files", ex);
        }
    }

    private void validateExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            throw new PptJobStateException("source file must have an extension");
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new PptJobStateException("unsupported source file extension: " + extension);
        }
    }

    private String normalizeProjectName(String projectName) {
        String candidate = projectName == null || projectName.isBlank() ? "ppt_project" : projectName.trim();
        return candidate.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]", "_");
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "ppt169";
        }
        String normalized = CANVAS_FORMATS.get(format.trim().toLowerCase());
        if (normalized == null) {
            throw new PptJobStateException("unsupported canvas format: " + format);
        }
        return normalized;
    }
}
