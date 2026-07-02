package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PptJobResponse(
        UUID id,
        String projectName,
        String format,
        PptJobStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<SourceFileResponse> sources,
        boolean artifactReady,
        String downloadUrl,
        String currentConfirmationId,
        Map<String, Object> confirmationPayload,
        String errorMessage,
        List<PptJobEvent> events) {

    public static PptJobResponse from(PptJob job) {
        return new PptJobResponse(
                job.id(),
                job.projectName(),
                job.format(),
                job.status(),
                job.createdAt(),
                job.updatedAt(),
                job.sourceFiles().stream().map(SourceFileResponse::from).toList(),
                job.exportPath().isPresent(),
                job.exportPath().map(ignored -> "/api/ppt-jobs/" + job.id() + "/download").orElse(null),
                job.currentConfirmationId().orElse(null),
                job.confirmationPayload(),
                job.errorMessage().orElse(null),
                job.events());
    }
}
