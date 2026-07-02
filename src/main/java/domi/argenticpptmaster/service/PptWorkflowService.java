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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PptWorkflowService {

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

    public PptJob createJob(List<MultipartFile> files, String projectName, String format, String instruction) {
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new PptJobStateException("at least one source file is required");
        }
        UUID jobId = UUID.randomUUID();
        String normalizedProjectName = normalizeProjectName(projectName);
        String normalizedFormat = normalizeFormat(format);
        Path jobWorkspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString()).toAbsolutePath().normalize();
        PptJob job = new PptJob(jobId, normalizedProjectName, normalizedFormat, instruction, jobWorkspace);
        storeSources(job, files);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_ACCEPTED, "job accepted",
                Map.of("jobId", job.id().toString())));
        asyncRunner.startAgent(job.id());
        return job;
    }

    public PptJob getJob(UUID jobId) {
        return repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
    }

    public PptJob submitConfirmation(
            UUID jobId,
            String confirmationId,
            boolean approved,
            Map<String, Object> answers,
            String comment) {
        PptJob job = getJob(jobId);
        if (job.status() != PptJobStatus.WAITING_CONFIRMATION) {
            throw new PptJobStateException("job is not waiting for confirmation");
        }
        String expectedConfirmationId = job.currentConfirmationId()
                .orElseThrow(() -> new PptJobStateException("job has no active confirmation"));
        if (!expectedConfirmationId.equals(confirmationId)) {
            throw new PptJobStateException("confirmationId does not match active confirmation");
        }
        if (!approved) {
            job.fail("confirmation rejected: " + (comment == null ? "" : comment));
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
        asyncRunner.resumeAgent(job.id());
        return job;
    }

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
