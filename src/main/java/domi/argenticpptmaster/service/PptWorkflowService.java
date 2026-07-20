package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptOutlineEdit;
import domi.argenticpptmaster.domain.PptRevisionImpactPreview;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptSlideEdit;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.util.PptConfirmationStageResolver;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptStorageException;
import domi.argenticpptmaster.repository.PptJobRepository;
import domi.argenticpptmaster.web.dto.PptSlideEditRequest;
import domi.argenticpptmaster.web.dto.PptOutlineEditRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final int MAX_RESUME_ATTEMPTS = 5;

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
    private final PptTemplateFillAsyncRunner templateFillAsyncRunner;
    private final PptOutlineStore outlineStore = new PptOutlineStore();
    private final PptArtifactRegistry artifactRegistry = new PptArtifactRegistry();
    private final Map<String, PptRevisionImpactPreview> revisionImpactPreviews = new ConcurrentHashMap<>();
    private final Map<String, UUID> revisionImpactOwners = new ConcurrentHashMap<>();

    public PptWorkflowService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptWorkflowAsyncRunner asyncRunner,
            PptTemplateFillAsyncRunner templateFillAsyncRunner) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.asyncRunner = asyncRunner;
        this.templateFillAsyncRunner = templateFillAsyncRunner;
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
    public PptJob createJob(List<MultipartFile> files, String projectName, String format, String instruction, String workflowMode) {
        return createJob(new PptJobCreateCommand(files, null, projectName, format, instruction, workflowMode));
    }

    /**
     * 根据显式模板/内容角色创建 PPT 任务。
     *
     * @param command 创建命令
     * @return 已创建任务
     */
    public PptJob createJob(PptJobCreateCommand command) {
        List<MultipartFile> files = command.files();
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new PptJobStateException("at least one source file is required");
        }
        UUID jobId = UUID.randomUUID();
        String normalizedProjectName = normalizeProjectName(command.projectName());
        String normalizedFormat = normalizeFormat(command.format());
        PptWorkflowMode mode;
        try {
            mode = PptWorkflowMode.from(command.workflowMode());
        } catch (IllegalArgumentException ex) {
            throw new PptJobStateException(ex.getMessage());
        }
        validateTemplateContract(mode, command.templateFile());
        if (mode == PptWorkflowMode.TEMPLATE_FILL) {
            validateTemplateFillUploadSizes(command.templateFile(), files);
        }
        Path jobWorkspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString()).toAbsolutePath().normalize();
        PptJob job = new PptJob(jobId, normalizedProjectName, normalizedFormat, command.instruction(), mode, jobWorkspace);
        log.info("ppt_job_create_started: jobId={}, projectName={}, format={}, workflowMode={}, fileCount={}",
                jobId, normalizedProjectName, normalizedFormat, mode, files.size());
        if (mode == PptWorkflowMode.TEMPLATE_FILL) {
            storeTemplate(job, command.templateFile());
            storeSources(job, files, job.workspacePath().resolve("uploads/content"));
        } else {
            storeSources(job, files, job.workspacePath().resolve("uploads"));
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_ACCEPTED, "job accepted",
                Map.of("jobId", job.id().toString())));
        if (mode != PptWorkflowMode.TEMPLATE_FILL) {
            asyncRunner.startAgent(job.id());
        }
        log.info("ppt_job_create_accepted: jobId={}, projectName={}, format={}, workflowMode={}, sourceCount={}",
                jobId, normalizedProjectName, normalizedFormat, mode, job.sourceFiles().size());
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
     * 提交人工确认或大纲修订。
     * <p>
     * 验证当前任务状态为 {@link PptJobStatus#WAITING_CONFIRMATION}，
     * 且传入的 confirmationId 与待确认的 ID 一致。
     * 旧客户端的 approved 字段会兼容映射为批准或终止；新客户端可显式请求大纲修订。
     * 只有批准和修订会异步恢复 AI 代理执行。
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
        return submitConfirmation(jobId, confirmationId, approved, answers, comment, null, null, List.of());
    }

    /**
     * 提交带有明确操作意图的人工确认。
     *
     * @param jobId          任务 ID
     * @param confirmationId 确认请求 ID
     * @param approved       旧客户端兼容字段；action 为空时决定批准或取消
     * @param answers        用户补充答案
     * @param comment        旧客户端评论字段
     * @param action         批准、要求修订或终止
     * @param overallComment 大纲整体修订意见
     * @param slideEdits     大纲逐页修订意见
     * @return 更新后的任务实例
     */
    public PptJob submitConfirmation(
            UUID jobId,
            String confirmationId,
            boolean approved,
            Map<String, Object> answers,
            String comment,
            PptConfirmationAction action,
            String overallComment,
            List<PptSlideEditRequest> slideEdits) {
        return submitConfirmation(jobId, confirmationId, approved, answers, comment, action, overallComment,
                slideEdits, null, List.of(), null);
    }

    /** 提交带版本和结构化页级操作的确认。 */
    public PptJob submitConfirmation(
            UUID jobId,
            String confirmationId,
            boolean approved,
            Map<String, Object> answers,
            String comment,
            PptConfirmationAction action,
            String overallComment,
            List<PptSlideEditRequest> slideEdits,
            Integer outlineVersion,
            List<PptOutlineEditRequest> outlineEdits) {
        return submitConfirmation(jobId, confirmationId, approved, answers, comment, action, overallComment,
                slideEdits, outlineVersion, outlineEdits, null);
    }

    public PptJob submitConfirmation(
            UUID jobId, String confirmationId, boolean approved, Map<String, Object> answers, String comment,
            PptConfirmationAction action, String overallComment, List<PptSlideEditRequest> slideEdits,
            Integer outlineVersion, List<PptOutlineEditRequest> outlineEdits, String revisionImpactToken) {
        PptJob job = getJob(jobId);
        PptConfirmationAction effectiveAction = action == null
                ? (approved ? PptConfirmationAction.APPROVE : PptConfirmationAction.CANCEL)
                : action;
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
        if (effectiveAction == PptConfirmationAction.CANCEL) {
            String rejectReason = "confirmation rejected: " + (comment == null ? "" : comment);
            log.warn("ppt_job_confirmation_rejected: jobId={}, confirmationId={}, commentPresent={}, commentLength={}",
                    jobId, confirmationId, comment != null && !comment.isBlank(),
                    comment == null ? 0 : comment.length());
            job.fail(rejectReason);
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "confirmation rejected"));
            return job;
        }
        validateImageManifestConfirmation(job, effectiveAction);
        validateImageReadyContinuation(job, effectiveAction);
        List<PptSlideEdit> validatedEdits = validateSlideEdits(job, effectiveAction, slideEdits);
        if (effectiveAction == PptConfirmationAction.REQUEST_REVISION && isLockedOutline(job)
                && (revisionImpactToken == null || revisionImpactToken.isBlank())) {
            throw new PptJobStateException("locked outline revision requires revisionImpactToken");
        }
        List<PptOutlineEdit> validatedOutlineEdits = validateOutlineEdits(job, effectiveAction, outlineVersion,
                outlineEdits, revisionImpactToken);
        String resolvedOverallComment = firstNonBlank(overallComment, comment);
        if (effectiveAction == PptConfirmationAction.REQUEST_REVISION
                && isBlank(resolvedOverallComment) && validatedEdits.isEmpty() && validatedOutlineEdits.isEmpty()) {
            throw new PptJobStateException("confirmation revision requires feedback");
        }
        PptConfirmation confirmation = new PptConfirmation(
                confirmationId,
                true,
                answers == null ? Map.of() : Map.copyOf(answers),
                comment,
                Instant.now(),
                effectiveAction,
                resolvedOverallComment,
                validatedEdits,
                outlineVersion,
                validatedOutlineEdits);
        PptJobNode confirmedNode = effectiveAction == PptConfirmationAction.APPROVE
                ? PptConfirmationStageResolver.resolveConfirmedNode(job.confirmationPayload())
                : null;
        if (confirmedNode != null) {
            lockApprovedOutline(job, outlineVersion);
            if (confirmedNode == PptJobNode.OUTLINE_CONFIRMED
                    && job.currentNode().orElse(null) == PptJobNode.OUTLINE_DRAFTED) {
                job.completeNode(PptJobNode.OUTLINE_DRAFTED, Map.of("confirmed", true));
            }
            job.confirmNode(confirmedNode);
        }
        job.receiveConfirmation(confirmation);
        events.record(job, PptJobEvent.of(PptJobEventType.CONFIRMATION_RECEIVED, "confirmation received",
                Map.of("confirmationId", confirmationId, "confirmedNode",
                        confirmedNode == null ? "unknown" : confirmedNode.name(),
                        "action", effectiveAction.name())));
        log.info("ppt_job_confirmation_submitted: jobId={}, confirmationId={}, action={}, confirmedNode={}, answersKeys={}",
                jobId, confirmationId,
                effectiveAction,
                confirmedNode == null ? "unknown" : confirmedNode.name(),
                confirmation.answers().keySet());
        asyncRunner.resumeAgent(job.id());
        return job;
    }

    /** 为锁定大纲生成不改变任务状态的影响预览。 */
    public PptRevisionImpactPreview previewOutlineRevision(UUID jobId, Integer outlineVersion) {
        PptJob job = getJob(jobId);
        if (!isOutlineConfirmation(job) || outlineVersion == null) {
            throw new PptJobStateException("outline revision preview is not available");
        }
        PptOutline outline = currentOutline(job, outlineVersion);
        if (!outline.locked()) {
            throw new PptJobStateException("impact preview is only required for a locked outline");
        }
        String token = UUID.randomUUID().toString();
        Path projectPath = job.projectPath().orElseThrow(() -> new PptJobStateException("project path is missing"));
        PptRevisionImpactPreview preview = new PptRevisionImpactPreview(token, outlineVersion,
                artifactRegistry.affected(projectPath, outlineVersion).stream().map(record -> record.path()).toList(),
                Instant.now().plusSeconds(600));
        revisionImpactPreviews.put(token, preview);
        revisionImpactOwners.put(token, jobId);
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        Object rawContext = job.confirmationPayload().get("contextData");
        if (rawContext instanceof Map<?, ?> map) {
            map.forEach((key, value) -> context.put(String.valueOf(key), value));
        }
        context.put("impactPreview", Map.of(
                "revisionImpactToken", preview.revisionImpactToken(),
                "outlineVersion", preview.outlineVersion(),
                "affectedArtifacts", preview.affectedArtifacts(),
                "expiresAt", preview.expiresAt().toString()));
        Map<String, Object> payload = new java.util.LinkedHashMap<>(job.confirmationPayload());
        payload.put("contextData", context);
        job.updateConfirmationPayload(payload);
        return preview;
    }

    private List<PptSlideEdit> validateSlideEdits(
            PptJob job,
            PptConfirmationAction action,
            List<PptSlideEditRequest> slideEdits) {
        List<PptSlideEditRequest> requests = slideEdits == null ? List.of() : slideEdits;
        if (action != PptConfirmationAction.REQUEST_REVISION) {
            return List.of();
        }
        if (isImageManifestConfirmation(job)) {
            if (!requests.isEmpty()) {
                throw new PptJobStateException("image manifest revision does not accept slide edits");
            }
            return List.of();
        }
        Object contextData = job.confirmationPayload().get("contextData");
        if (!(contextData instanceof Map<?, ?> contextMap)
                || !"ppt_outline".equals(contextMap.get("type"))) {
            throw new PptJobStateException("outline revision is not available for current confirmation");
        }
        Set<Integer> validSlideNumbers = outlineSlideNumbers(contextMap.get("slides"));
        Set<Integer> seen = new HashSet<>();
        List<PptSlideEdit> result = new ArrayList<>();
        for (PptSlideEditRequest request : requests) {
            if (request == null || request.slideNo() <= 0 || !validSlideNumbers.contains(request.slideNo())) {
                throw new PptJobStateException("slide edit references an invalid slide number");
            }
            if (!seen.add(request.slideNo())) {
                throw new PptJobStateException("slide edit contains duplicate slide number");
            }
            if (isBlank(request.comment())) {
                throw new PptJobStateException("slide edit comment is required");
            }
            result.add(new PptSlideEdit(request.slideNo(), request.comment().trim()));
        }
        return List.copyOf(result);
    }

    private Set<Integer> outlineSlideNumbers(Object slides) {
        if (!(slides instanceof List<?> slideList)) {
            throw new PptJobStateException("outline slides are missing");
        }
        Set<Integer> result = new HashSet<>();
        int previousSlideNo = 0;
        for (Object slide : slideList) {
            if (!(slide instanceof Map<?, ?> slideMap)) {
                throw new PptJobStateException("outline slide is invalid");
            }
            Object slideNo = slideMap.get("slideNo");
            if (!(slideNo instanceof Number number)
                    || number.doubleValue() != number.intValue()
                    || number.intValue() <= previousSlideNo) {
                throw new PptJobStateException("outline slide number is invalid");
            }
            result.add(number.intValue());
            previousSlideNo = number.intValue();
        }
        return result;
    }

    private List<PptOutlineEdit> validateOutlineEdits(
            PptJob job, PptConfirmationAction action, Integer outlineVersion,
            List<PptOutlineEditRequest> requests, String revisionImpactToken) {
        if (action != PptConfirmationAction.REQUEST_REVISION && (requests == null || requests.isEmpty())) {
            return List.of();
        }
        List<PptOutlineEditRequest> edits = requests == null ? List.of() : requests;
        if (edits.isEmpty()) {
            return List.of();
        }
        if (action != PptConfirmationAction.REQUEST_REVISION) {
            throw new PptJobStateException("outline edits are only allowed for REQUEST_REVISION");
        }
        if (!isOutlineConfirmation(job) || outlineVersion == null || outlineVersion <= 0) {
            throw new PptJobStateException("structured outline edits require a valid outline version");
        }
        PptOutline outline = currentOutline(job, outlineVersion);
        List<PptOutlineEdit> result = new ArrayList<>();
        for (PptOutlineEditRequest request : edits) {
            if (request == null) {
                throw new PptJobStateException("outline edit is required");
            }
            PptOutlineEdit edit = new PptOutlineEdit(request.operation(), request.slideNo(), request.slide());
            try {
                edit.validate();
            } catch (IllegalArgumentException ex) {
                throw new PptJobStateException(ex.getMessage());
            }
            result.add(edit);
        }
        try {
            PptOutline next;
            if (outline.locked()) {
                PptRevisionImpactPreview preview = revisionImpactPreviews.remove(revisionImpactToken);
                UUID owner = revisionImpactOwners.remove(revisionImpactToken);
                if (preview == null || !job.id().equals(owner) || preview.outlineVersion() != outline.version()
                        || preview.expiresAt().isBefore(Instant.now())) {
                    throw new PptJobStateException("locked outline revision requires a valid revisionImpactToken");
                }
                next = outline.reviseFromLocked(result);
                PptOutline revised = next;
                job.projectPath().ifPresent(path -> {
                    artifactRegistry.markStale(path, outline.version());
                    outlineStore.write(path, revised);
                });
                job.invalidateAfterOutlineRevision();
            } else {
                next = outline.apply(result);
                job.projectPath().ifPresent(path -> outlineStore.write(path, next));
            }
            updateOutlinePayload(job, next);
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new PptJobStateException(ex.getMessage());
        }
        return List.copyOf(result);
    }

    private void updateOutlinePayload(PptJob job, PptOutline outline) {
        Object contextData = job.confirmationPayload().get("contextData");
        if (!(contextData instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> updatedContext = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> updatedContext.put(String.valueOf(key), value));
        updatedContext.put("version", outline.version());
        updatedContext.put("locked", outline.locked());
        updatedContext.put("slides", outline.slides().stream().map(slide -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("slideNo", slide.slideNo());
            value.put("title", slide.title());
            value.put("keyMessage", slide.keyMessage());
            value.put("bullets", slide.bullets());
            value.put("visualSuggestion", slide.visualSuggestion());
            value.put("imageRequirement", slide.imageRequirement());
            return value;
        }).toList());
        job.projectPath().ifPresent(path -> new PptOutlineStore().snapshot(path, outline.version()).ifPresent(snapshot -> {
            if (snapshot.parentVersion() != null) updatedContext.put("parentVersion", snapshot.parentVersion());
            if (snapshot.diff() != null) updatedContext.put("diff", snapshot.diff());
        }));
        Map<String, Object> payload = new java.util.LinkedHashMap<>(job.confirmationPayload());
        payload.put("contextData", updatedContext);
        job.updateConfirmationPayload(payload);
    }

    private void lockApprovedOutline(PptJob job, Integer outlineVersion) {
        if (!isOutlineConfirmation(job)) {
            return;
        }
        Object contextData = job.confirmationPayload().get("contextData");
        Integer payloadVersion = outlineVersion != null ? outlineVersion : outlineVersionFrom(contextData);
        if (payloadVersion == null) {
            if (job.projectPath().isPresent()) {
                throw new PptJobStateException("outline confirmation requires a version");
            }
            return;
        }
        PptOutline outline = currentOutline(job, payloadVersion);
        if (outline.locked()) {
            return;
        }
        PptOutline locked = outline.lock();
        job.projectPath().ifPresent(path -> outlineStore.write(path, locked));
        if (contextData instanceof Map<?, ?> map) {
            Map<String, Object> updatedContext = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> updatedContext.put(String.valueOf(key), value));
            updatedContext.put("version", locked.version());
            updatedContext.put("locked", true);
            Map<String, Object> payload = new java.util.LinkedHashMap<>(job.confirmationPayload());
            payload.put("contextData", updatedContext);
            job.updateConfirmationPayload(payload);
        }
    }

    private PptOutline currentOutline(PptJob job, int expectedVersion) {
        PptOutline outline = job.projectPath().filter(path -> Files.isRegularFile(outlineStore.path(path)))
                .map(outlineStore::read)
                .orElseGet(() -> {
                    Object contextData = job.confirmationPayload().get("contextData");
                    return contextData instanceof Map<?, ?> map
                            ? PptOutline.fromPayload(expectedVersion, map.get("slides"))
                            : null;
                });
        if (outline == null || outline.version() != expectedVersion) {
            throw new PptJobStateException("outline version does not match active outline");
        }
        return outline;
    }

    private boolean isOutlineConfirmation(PptJob job) {
        return "outline_confirmation".equals(job.confirmationPayload().get("stage"));
    }

    private boolean isImageManifestConfirmation(PptJob job) {
        return "image_manifest_confirmation".equals(job.confirmationPayload().get("stage"));
    }

    private void validateImageManifestConfirmation(PptJob job, PptConfirmationAction action) {
        if (!isImageManifestConfirmation(job)) {
            return;
        }
        Object contextData = job.confirmationPayload().get("contextData");
        if (!(contextData instanceof Map<?, ?> context)
                || !"ppt_image_manifest".equals(context.get("type"))
                || !(context.get("outlineVersion") instanceof Number number)
                || number.intValue() <= 0) {
            throw new PptJobStateException("image manifest confirmation payload is invalid");
        }
        Path projectPath = job.projectPath().orElseThrow(() ->
                new PptJobStateException("image manifest confirmation requires a project path"));
        PptOutline outline = outlineStore.read(projectPath);
        if (!outline.locked() || outline.version() != number.intValue()) {
            throw new PptJobStateException("image manifest does not match current locked outline");
        }
        Map<String, Object> manifest = new PptImageManifestStore().readForOutlineVersion(projectPath, outline.version());
        if (action == PptConfirmationAction.REQUEST_REVISION
                && ((List<?>) manifest.get("items")).stream().anyMatch(item -> item instanceof Map<?, ?> map
                && "Generated".equals(map.get("status")))) {
            throw new PptJobStateException("generated image manifest cannot be revised");
        }
    }

    private void validateImageReadyContinuation(PptJob job, PptConfirmationAction action) {
        if (action != PptConfirmationAction.APPROVE
                || !"image_ready_continue_confirmation".equals(job.confirmationPayload().get("stage"))) {
            return;
        }
        Path projectPath = job.projectPath().orElse(null);
        if (projectPath == null || !Files.isRegularFile(outlineStore.path(projectPath))) {
            return; // 保持旧任务不含版本化大纲时的确认兼容性。
        }
        PptOutline outline = outlineStore.read(projectPath);
        Map<String, Object> manifest = new PptImageManifestStore().readForOutlineVersion(projectPath, outline.version());
        if (!artifactRegistry.isUsable(projectPath, "images/image_prompts.json", outline.version())) {
            throw new PptJobStateException("image manifest is stale or not registered for current outline");
        }
        for (Object item : (List<?>) manifest.get("items")) {
            if (!(item instanceof Map<?, ?> image)
                    || !(image.get("filename") instanceof String filename)
                    || !"Generated".equals(image.get("status"))
                    || !Files.isRegularFile(projectPath.resolve("images").resolve(filename))
                    || !artifactRegistry.isUsable(projectPath, "images/" + filename, outline.version())) {
                throw new PptJobStateException("current outline images are not ready to continue");
            }
        }
    }

    private boolean isLockedOutline(PptJob job) {
        if (!isOutlineConfirmation(job)) {
            return false;
        }
        Integer version = outlineVersionFrom(job.confirmationPayload().get("contextData"));
        return version != null && currentOutline(job, version).locked();
    }

    private Integer outlineVersionFrom(Object contextData) {
        if (contextData instanceof Map<?, ?> map && map.get("version") instanceof Number number
                && number.intValue() > 0) {
            return number.intValue();
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        Path exportPath = job.exportPath()
                .orElseThrow(() -> new PptJobStateException("job has no export artifact"))
                .toAbsolutePath().normalize();
        Path exportsDir = job.workspacePath().toAbsolutePath().normalize().resolve("exports").normalize();
        if (!exportPath.startsWith(exportsDir)) {
            throw new PptJobStateException("export path is outside job exports directory");
        }
        return exportPath;
    }

    /**
     * 从上一个成功节点继续执行失败的任务。
     * <p>
     * 仅当任务当前状态为 {@link PptJobStatus#FAILED}，且不在等待人工确认、
     * 存在可恢复的成功节点、且恢复次数未超过上限时才允许恢复。
     * </p>
     *
     * @param jobId 任务 ID
     * @return 更新后的任务实例
     * @throws PptJobResumeException 如果任务不可恢复
     */
    public PptJob resumeJob(UUID jobId) {
        PptJob job = getJob(jobId);
        log.info("ppt_job_resume_requested: jobId={}, currentStatus={}, lastCompletedNode={}",
                jobId, job.status(), job.lastCompletedNode().map(Enum::name).orElse("none"));
        String previousFailureNode = job.lastFailureNode().map(Enum::name).orElse("none");
        String rejection = job.tryStartResumeAttempt(MAX_RESUME_ATTEMPTS);
        if (rejection != null) {
            log.warn("ppt_job_resume_rejected: jobId={}, reason={}", jobId, rejection);
            throw new PptJobResumeException(jobId, rejection);
        }
        PptJobNode checkpoint = resolveEffectiveCheckpoint(job);
        log.info("ppt_job_resume_accepted: jobId={}, checkpoint={}, resumeCount={}",
                jobId, checkpoint.name(), job.resumeCount());
        events.record(job, PptJobEvent.of(
                PptJobEventType.JOB_RESUME_ACCEPTED,
                "job resume accepted",
                Map.of(
                        "checkpoint", checkpoint.name(),
                        "resumeCount", job.resumeCount(),
                        "previousFailureNode", previousFailureNode)));
        repository.save(job);
        if (job.workflowMode() == PptWorkflowMode.TEMPLATE_FILL) {
            templateFillAsyncRunner.resumeFromCheckpoint(job.id(), checkpoint);
        } else {
            asyncRunner.resumeFromCheckpoint(job.id(), checkpoint);
        }
        return job;
    }

    /**
     * 解析有效的恢复 checkpoint。
     * <p>
     * 默认返回任务记录中的 {@code lastCompletedNode}。未来可在此加入文件证据校验，
     * 若当前 checkpoint 的证据缺失则自动回退到上一个稳定节点。
     * </p>
     *
     * @param job PPT 任务
     * @return 有效的恢复 checkpoint
     */
    private PptJobNode resolveEffectiveCheckpoint(PptJob job) {
        PptJobNode checkpoint = job.lastCompletedNode().orElseThrow(() ->
                new PptJobResumeException(job.id(), "job has no completed node to resume from"));
        if (job.workflowMode() == PptWorkflowMode.TEMPLATE_FILL) {
            return checkpoint;
        }
        if (checkpoint.ordinal() > PptJobNode.OUTLINE_CONFIRMED.ordinal()) {
            job.projectPath().ifPresent(path -> {
                if (artifactRegistry.hasAnyStale(path)) {
                    throw new PptJobResumeException(job.id(), "downstream artifacts are stale after outline revision");
                }
            });
        }
        return checkpoint;
    }

    private void validateTemplateFillUploadSizes(MultipartFile templateFile, List<MultipartFile> files) {
        long templateSize = templateFile.getSize();
        if (templateSize > properties.templateFillTemplateMaxBytes()) {
            throw new PptJobStateException(
                    domi.argenticpptmaster.domain.TemplateFillErrorCode.TEMPLATE_FILL_UPLOAD_TOO_LARGE.code()
                            + ": template file exceeds size limit");
        }
        long total = templateSize;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > properties.templateFillContentMaxBytes()) {
                throw new PptJobStateException(
                        domi.argenticpptmaster.domain.TemplateFillErrorCode.TEMPLATE_FILL_UPLOAD_TOO_LARGE.code()
                                + ": content file exceeds size limit");
            }
            total += file.getSize();
        }
        if (total > properties.templateFillTotalUploadMaxBytes()) {
            throw new PptJobStateException(
                    domi.argenticpptmaster.domain.TemplateFillErrorCode.TEMPLATE_FILL_UPLOAD_TOO_LARGE.code()
                            + ": total upload exceeds size limit");
        }
    }

    /**
     * 记录节点开始执行。
     *
     * @param job  PPT 任务
     * @param node 业务节点
     */
    public void recordNodeStarted(PptJob job, PptJobNode node) {
        job.startNode(node);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_STARTED,
                "node started: " + node.name(),
                Map.of("node", node.name())));
    }

    /**
     * 记录节点已完成。
     *
     * @param job     PPT 任务
     * @param node    业务节点
     * @param summary 完成摘要
     */
    public void recordNodeCompleted(PptJob job, PptJobNode node, Map<String, Object> summary) {
        job.completeNode(node, summary);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_COMPLETED,
                "node completed: " + node.name(),
                Map.of("node", node.name(), "summary", summary)));
    }

    /**
     * 记录节点失败。
     *
     * @param job     PPT 任务
     * @param node    业务节点
     * @param message 失败原因
     */
    public void recordNodeFailed(PptJob job, PptJobNode node, String message) {
        job.failNode(node, message);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_FAILED,
                "node failed: " + node.name(),
                Map.of("node", node.name(), "error", message)));
    }

    /**
     * 记录节点进入等待人工确认状态。
     *
     * @param job  PPT 任务
     * @param node 业务节点
     */
    public void recordNodeWaitingConfirmation(PptJob job, PptJobNode node) {
        job.waitNodeConfirmation(node);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_STARTED,
                "node waiting confirmation: " + node.name(),
                Map.of("node", node.name())));
    }

    /**
     * 记录用户确认后推进确认类节点。
     *
     * @param job  PPT 任务
     * @param node 业务节点
     */
    public void recordNodeConfirmed(PptJob job, PptJobNode node) {
        job.confirmNode(node);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_COMPLETED,
                "node confirmed: " + node.name(),
                Map.of("node", node.name())));
    }

    private void storeSources(PptJob job, List<MultipartFile> files, Path uploadDir) {
        try {
            Files.createDirectories(uploadDir.toAbsolutePath().normalize());
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    continue;
                }
                String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null
                        ? "source"
                        : file.getOriginalFilename());
                validateExtension(originalName);
                Path safeUploadDir = uploadDir.toAbsolutePath().normalize();
                Path storedPath = resolveStoredPath(safeUploadDir, job.sourceFiles().size(), originalName);
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

    private void storeTemplate(PptJob job, MultipartFile templateFile) {
        Path uploadDir = job.workspacePath().resolve("uploads/template").toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
            String originalName = StringUtils.cleanPath(templateFile.getOriginalFilename() == null
                    ? "template.pptx"
                    : templateFile.getOriginalFilename());
            validateTemplateExtension(originalName);
            Path storedPath = resolveStoredPath(uploadDir, 0, originalName);
            try (InputStream inputStream = templateFile.getInputStream()) {
                Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
            }
            job.setTemplate(new PptTemplateFile(
                    originalName, templateFile.getContentType(), templateFile.getSize(), storedPath));
            events.record(job, PptJobEvent.of(PptJobEventType.SOURCE_STORED, "template file stored",
                    Map.of("fileName", originalName, "size", templateFile.getSize(), "role", "template")));
        } catch (IOException ex) {
            throw new PptStorageException("failed to store template file", ex);
        }
    }

    private Path resolveStoredPath(Path uploadDir, int index, String originalName) {
        Path namePath = Path.of(originalName);
        if (namePath.isAbsolute() || !namePath.equals(namePath.getFileName()) || originalName.contains("..")) {
            throw new PptJobStateException("invalid file name: " + originalName);
        }
        Path storedPath = uploadDir.resolve(index + "-" + originalName).normalize();
        if (!storedPath.startsWith(uploadDir)) {
            throw new PptJobStateException("invalid file name: " + originalName);
        }
        return storedPath;
    }

    private void validateTemplateContract(PptWorkflowMode mode, MultipartFile templateFile) {
        if (mode == PptWorkflowMode.TEMPLATE_FILL) {
            if (templateFile == null || templateFile.isEmpty()) {
                throw new PptJobStateException("template file is required for template-fill workflow");
            }
            String originalName = StringUtils.cleanPath(templateFile.getOriginalFilename() == null
                    ? ""
                    : templateFile.getOriginalFilename());
            validateTemplateExtension(originalName);
        } else if (templateFile != null) {
            throw new PptJobStateException("templateFile is only supported for template-fill workflow");
        }
    }

    private void validateTemplateExtension(String fileName) {
        if (!fileName.toLowerCase().endsWith(".pptx")) {
            throw new PptJobStateException("template file must have .pptx extension");
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
