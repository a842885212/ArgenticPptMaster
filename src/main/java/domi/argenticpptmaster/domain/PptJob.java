package domi.argenticpptmaster.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    public synchronized void addSource(PptSourceFile sourceFile) {
        sourceFiles.add(sourceFile);
        touch();
    }

    public synchronized void addEvent(PptJobEvent event) {
        events.add(event);
        touch();
    }

    public synchronized void prepareProject(Path preparedProjectPath) {
        this.status = PptJobStatus.PREPARING;
        this.projectPath = preparedProjectPath;
        touch();
    }

    public synchronized void requireConfirmation(String confirmationId, Map<String, Object> payload) {
        this.status = PptJobStatus.WAITING_CONFIRMATION;
        this.currentConfirmationId = confirmationId;
        this.confirmationPayload = payload == null ? Map.of() : Map.copyOf(payload);
        touch();
    }

    public synchronized void receiveConfirmation(PptConfirmation receivedConfirmation) {
        this.confirmation = receivedConfirmation;
        this.currentConfirmationId = null;
        this.status = PptJobStatus.RUNNING_AGENT;
        touch();
    }

    public synchronized void startAgent() {
        this.status = PptJobStatus.RUNNING_AGENT;
        touch();
    }

    public synchronized void startExport() {
        this.status = PptJobStatus.EXPORTING;
        touch();
    }

    public synchronized void complete(Path completedExportPath) {
        this.status = PptJobStatus.COMPLETED;
        this.exportPath = completedExportPath;
        touch();
    }

    public synchronized void fail(String message) {
        this.status = PptJobStatus.FAILED;
        this.errorMessage = message;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
