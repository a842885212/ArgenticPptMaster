package domi.argenticpptmaster.web;

import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ppt-jobs")
public class PptJobController {

    private final PptWorkflowService workflowService;
    private final PptJobEventPublisher eventPublisher;

    public PptJobController(PptWorkflowService workflowService, PptJobEventPublisher eventPublisher) {
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PptJobResponse> create(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) String projectName,
            @RequestParam(defaultValue = "ppt169") String format,
            @RequestParam(required = false) String instruction) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PptJobResponse.from(workflowService.createJob(files, projectName, format, instruction)));
    }

    @GetMapping("/{jobId}")
    public PptJobResponse get(@PathVariable UUID jobId) {
        return PptJobResponse.from(workflowService.getJob(jobId));
    }

    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId) {
        workflowService.getJob(jobId);
        return eventPublisher.subscribe(jobId);
    }

    @PostMapping("/{jobId}/confirm")
    public PptJobResponse confirm(@PathVariable UUID jobId, @Valid @org.springframework.web.bind.annotation.RequestBody ConfirmationRequest request) {
        return PptJobResponse.from(workflowService.submitConfirmation(
                jobId,
                request.confirmationId(),
                request.approved(),
                request.answers(),
                request.comment()));
    }

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
