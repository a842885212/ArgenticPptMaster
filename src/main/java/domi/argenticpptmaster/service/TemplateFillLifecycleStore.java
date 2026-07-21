package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest.CleanupState;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 持久化模板填充生命周期清单 {@code lifecycle/manifest.json}。
 */
@Component
public class TemplateFillLifecycleStore {

    static final String MANIFEST_FILE = "manifest.json";
    static final String LIFECYCLE_DIR = "lifecycle";

    private final PptMasterProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public TemplateFillLifecycleStore(PptMasterProperties properties) {
        this(properties, new ObjectMapper());
    }

    TemplateFillLifecycleStore(PptMasterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 工作区创建后写入初始清单。 */
    public void initialize(PptJob job) {
        String ownershipDigest = digestOwnership(
                job.ownerSubjectId().orElseThrow(() -> new PptJobStateException("template-fill ownership is missing")),
                job.ownerTenantId().orElseThrow(() -> new PptJobStateException("template-fill ownership is missing")));
        TemplateFillLifecycleManifest manifest = new TemplateFillLifecycleManifest(
                job.id(),
                ownershipDigest,
                job.createdAt(),
                null,
                null,
                null,
                CleanupState.ACTIVE,
                countArtifacts(job),
                TemplateFillLifecycleManifest.SCHEMA_VERSION);
        writeManifest(job.workspacePath(), manifest);
    }

    /** 任务进入终态时写入保留截止时间。 */
    public void markTerminal(PptJob job, Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        TemplateFillLifecycleManifest current = read(job)
                .orElseThrow(() -> new PptJobStateException("lifecycle manifest is missing"));
        Instant terminalAt = job.terminalAt().orElse(Instant.now());
        Instant deadline = terminalAt.plus(retention);
        TemplateFillLifecycleManifest updated = new TemplateFillLifecycleManifest(
                current.jobId(),
                current.ownershipDigest(),
                current.createdAt(),
                terminalAt,
                deadline,
                current.lastDownloadedAt(),
                current.cleanupState(),
                countArtifacts(job),
                current.schemaVersion());
        writeManifest(job.workspacePath(), updated);
    }

    /** 诊断包生成成功后刷新 artifact 计数，不延长保留期。 */
    public void recordDiagnostic(PptJob job) {
        TemplateFillLifecycleManifest current = read(job)
                .orElseThrow(() -> new PptJobStateException("lifecycle manifest is missing"));
        TemplateFillLifecycleManifest updated = new TemplateFillLifecycleManifest(
                current.jobId(),
                current.ownershipDigest(),
                current.createdAt(),
                current.terminalAt(),
                current.retentionDeadline(),
                current.lastDownloadedAt(),
                current.cleanupState(),
                countArtifacts(job),
                current.schemaVersion());
        writeManifest(job.workspacePath(), updated);
    }

    /** 授权下载成功后更新最近下载时间，不延长保留期。 */
    public void recordDownload(PptJob job) {
        TemplateFillLifecycleManifest current = read(job)
                .orElseThrow(() -> new PptJobStateException("lifecycle manifest is missing"));
        TemplateFillLifecycleManifest updated = new TemplateFillLifecycleManifest(
                current.jobId(),
                current.ownershipDigest(),
                current.createdAt(),
                current.terminalAt(),
                current.retentionDeadline(),
                Instant.now(),
                current.cleanupState(),
                current.artifactCounts(),
                current.schemaVersion());
        writeManifest(job.workspacePath(), updated);
    }

    public void markTombstoned(PptJob job) {
        markTombstoned(job.workspacePath());
    }

    public void markTombstoned(Path workspace) {
        updateCleanupState(workspace, CleanupState.TOMBSTONED);
    }

    public void markIsolated(PptJob job) {
        markIsolated(job.workspacePath());
    }

    public void markIsolated(Path workspace) {
        updateCleanupState(workspace, CleanupState.ISOLATED);
    }

    public Optional<TemplateFillLifecycleManifest> read(PptJob job) {
        return read(job.workspacePath());
    }

    public Optional<TemplateFillLifecycleManifest> read(Path workspace) {
        Path manifestPath = manifestPath(workspace);
        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }
        rejectSymlink(manifestPath, "lifecycle manifest");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            return Optional.of(parseManifest(root));
        } catch (IOException | RuntimeException ex) {
            throw new PptJobStateException("failed to read lifecycle manifest");
        }
    }

    public Duration retentionForTerminalStatus(PptJobStatus status) {
        var production = properties.templateFillProduction();
        return status == PptJobStatus.COMPLETED
                ? production.retentionCompleted()
                : production.retentionFailed();
    }

    public static String digestOwnership(String subjectId, String tenantId) {
        return sha256Hex(subjectId.trim() + "|" + tenantId.trim());
    }

    public static String digestSubject(String subjectId) {
        return sha256Hex(subjectId == null ? "" : subjectId.trim());
    }

    public static String digestTenant(String tenantId) {
        return sha256Hex(tenantId == null ? "" : tenantId.trim());
    }

    static Path manifestPath(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(LIFECYCLE_DIR).resolve(MANIFEST_FILE).normalize();
    }

    private void updateCleanupState(Path workspace, CleanupState state) {
        TemplateFillLifecycleManifest current = read(workspace)
                .orElseThrow(() -> new PptJobStateException("lifecycle manifest is missing"));
        TemplateFillLifecycleManifest updated = new TemplateFillLifecycleManifest(
                current.jobId(),
                current.ownershipDigest(),
                current.createdAt(),
                current.terminalAt(),
                current.retentionDeadline(),
                current.lastDownloadedAt(),
                state,
                current.artifactCounts(),
                current.schemaVersion());
        writeManifest(workspace, updated);
    }

    private TemplateFillLifecycleManifest parseManifest(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new PptJobStateException("lifecycle manifest is malformed");
        }
        String schemaVersion = textOrNull(root, "schemaVersion");
        if (!TemplateFillLifecycleManifest.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new PptJobStateException("lifecycle manifest schema is unsupported");
        }
        UUID jobId = UUID.fromString(requiredText(root, "jobId"));
        String ownershipDigest = requiredText(root, "ownershipDigest");
        Instant createdAt = Instant.parse(requiredText(root, "createdAt"));
        Instant terminalAt = instantOrNull(root, "terminalAt");
        Instant retentionDeadline = instantOrNull(root, "retentionDeadline");
        Instant lastDownloadedAt = instantOrNull(root, "lastDownloadedAt");
        CleanupState cleanupState = CleanupState.valueOf(requiredText(root, "cleanupState"));
        Map<String, Integer> artifactCounts = parseArtifactCounts(root.get("artifactCounts"));
        return new TemplateFillLifecycleManifest(
                jobId, ownershipDigest, createdAt, terminalAt, retentionDeadline,
                lastDownloadedAt, cleanupState, artifactCounts, schemaVersion);
    }

    private static Map<String, Integer> parseArtifactCounts(JsonNode node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("template", 0);
        counts.put("content", 0);
        counts.put("exports", 0);
        counts.put("diagnostics", 0);
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().canConvertToInt()) {
                    counts.put(entry.getKey(), entry.getValue().intValue());
                }
            });
        }
        return counts;
    }

    private Map<String, Integer> countArtifacts(PptJob job) {
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("template", countRegularFiles(workspace.resolve("uploads/template")));
        counts.put("content", countRegularFiles(workspace.resolve("uploads/content")));
        counts.put("exports", countRegularFiles(workspace.resolve("exports")));
        counts.put("diagnostics", countRegularFiles(workspace.resolve("diagnostics")));
        return counts;
    }

    private static int countRegularFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var stream = Files.list(directory)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    private void writeManifest(Path workspace, TemplateFillLifecycleManifest manifest) {
        Path manifestPath = manifestPath(workspace);
        rejectSymlink(manifestPath.getParent(), "lifecycle directory");
        rejectSymlink(manifestPath, "lifecycle manifest");
        ensureInsideWorkspace(workspace, manifestPath);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("jobId", manifest.jobId().toString());
        root.put("ownershipDigest", manifest.ownershipDigest());
        root.put("createdAt", manifest.createdAt().toString());
        if (manifest.terminalAt() != null) {
            root.put("terminalAt", manifest.terminalAt().toString());
        }
        if (manifest.retentionDeadline() != null) {
            root.put("retentionDeadline", manifest.retentionDeadline().toString());
        }
        if (manifest.lastDownloadedAt() != null) {
            root.put("lastDownloadedAt", manifest.lastDownloadedAt().toString());
        }
        root.put("cleanupState", manifest.cleanupState().name());
        ObjectNode counts = root.putObject("artifactCounts");
        manifest.artifactCounts().forEach(counts::put);
        byte[] bytes;
        try {
            bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize lifecycle manifest");
        }
        atomicWrite(manifestPath, bytes);
    }

    private static void ensureInsideWorkspace(Path workspace, Path target) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedWorkspace)) {
            throw new PptJobStateException("lifecycle manifest path escapes job workspace");
        }
    }

    private static void rejectSymlink(Path path, String label) {
        if (Files.exists(path) && Files.isSymbolicLink(path)) {
            throw new PptJobStateException(label + " must not be a symbolic link");
        }
    }

    private void atomicWrite(Path target, byte[] bytes) {
        Path parent = target.getParent();
        Path temporaryPath = parent.resolve(target.getFileName() + ".tmp").normalize();
        try {
            Files.createDirectories(parent);
            Files.write(temporaryPath, bytes);
            try {
                Files.move(temporaryPath, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // preserve original failure
            }
            throw new PptJobStateException("failed to store lifecycle manifest");
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.asText().isBlank()) {
            throw new PptJobStateException("lifecycle manifest field is missing: " + field);
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Instant instantOrNull(JsonNode root, String field) {
        String value = textOrNull(root, field);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
