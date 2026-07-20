package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 在任务工作区内校验并原子保存 template-fill 计划及服务端元数据。
 */
@Component
public class PptTemplateFillPlanStore {

    static final String PLAN_FILE = "fill_plan.json";
    static final String META_FILE = "fill_plan.meta.json";
    static final String RATIONALE_FILE = "fill_plan_rationale.md";
    static final int MAX_RATIONALE_CHARS = 8_000;

    private final PptMasterProperties properties;
    private final ObjectMapper objectMapper;
    private final TemplateFillPlanValidator validator;

    @Autowired
    public PptTemplateFillPlanStore(
            PptMasterProperties properties,
            TemplateFillPlanValidator validator) {
        this(properties, validator, new ObjectMapper());
    }

    PptTemplateFillPlanStore(
            PptMasterProperties properties,
            TemplateFillPlanValidator validator,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /** Agent 保存 draft 计划并返回新元数据。 */
    public TemplateFillPlanMetadata storeDraftPlan(PptJob job, String jsonPlan, Path slideLibraryPath) {
        JsonNode validated = validator.parseAndValidateDraft(jsonPlan, slideLibraryPath);
        int nextVersion = readMetadata(job).map(meta -> meta.version() + 1).orElse(1);
        ObjectNode normalized = validator.normalizeDraft(validated, nextVersion);
        byte[] bytes = writeJsonBytes(normalized);
        enforceSize(bytes);
        String digest = TemplateFillPlanDigest.compute(bytes);
        Path planPath = planPath(job);
        atomicWrite(planPath, bytes);
        TemplateFillPlanMetadata metadata = TemplateFillPlanMetadata.draft(nextVersion, digest);
        writeMetadata(job, metadata);
        job.updateFillPlanStatus(FillPlanStatus.DRAFT, normalized.get("slides").size(), 0, 0);
        return metadata;
    }

    /** 保存固定名称的计划说明文件。 */
    public void storeRationale(PptJob job, String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return;
        }
        if (rationale.length() > MAX_RATIONALE_CHARS) {
            throw new PptJobStateException("fill plan rationale exceeds length limit");
        }
        Path rationalePath = analysisDir(job).resolve(RATIONALE_FILE).normalize();
        ensureInside(job, rationalePath);
        try {
            Files.createDirectories(analysisDir(job));
            Files.writeString(rationalePath, rationale, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to store fill plan rationale");
        }
    }

    /** 将当前 draft 原子提升为 confirmed。 */
    public TemplateFillPlanMetadata confirmCurrentDraft(
            PptJob job, String confirmationId, int expectedVersion, String expectedDigest) {
        TemplateFillPlanMetadata current = readMetadata(job)
                .orElseThrow(() -> new PptTemplateFillConflictException("fill plan metadata is missing"));
        if (!"draft".equalsIgnoreCase(current.status())) {
            throw new PptTemplateFillConflictException("fill plan is not awaiting confirmation");
        }
        if (current.version() != expectedVersion) {
            throw new PptTemplateFillConflictException("fill plan version mismatch");
        }
        Path planPath = planPath(job);
        if (!Files.isRegularFile(planPath)) {
            throw new PptTemplateFillConflictException("fill plan file is missing");
        }
        String actualDigest;
        try {
            actualDigest = TemplateFillPlanDigest.compute(Files.readAllBytes(planPath));
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read fill plan");
        }
        if (!actualDigest.equals(expectedDigest) || !actualDigest.equals(current.digest())) {
            throw new PptTemplateFillConflictException("fill plan digest mismatch");
        }
        JsonNode plan;
        try {
            plan = objectMapper.readTree(planPath.toFile());
        } catch (IOException ex) {
            throw new PptJobStateException("fill plan must be valid JSON");
        }
        ObjectNode confirmed = plan.deepCopy();
        confirmed.put("status", "confirmed");
        byte[] bytes = writeJsonBytes(confirmed);
        atomicWrite(planPath, bytes);
        TemplateFillPlanMetadata approved = current.confirmed(confirmationId, Instant.now());
        writeMetadata(job, approved);
        job.updateFillPlanStatus(FillPlanStatus.CONFIRMED, confirmed.get("slides").size(), 0, 0);
        return approved;
    }

    /**
     * 校验 confirmed 门禁并将计划原子写入任务 analysis 目录。
     *
     * @deprecated 生产路径应使用 {@link #confirmCurrentDraft}；此方法仅保留调试兼容。
     */
    public Path storeConfirmedPlan(PptJob job, String jsonPlan) {
        if (jsonPlan == null || jsonPlan.isBlank()) {
            throw new PptJobStateException("fill plan JSON is required");
        }
        byte[] bytes = jsonPlan.getBytes(StandardCharsets.UTF_8);
        enforceSize(bytes);
        JsonNode plan;
        try {
            plan = objectMapper.readTree(jsonPlan);
        } catch (IOException | RuntimeException ex) {
            throw new PptJobStateException("fill plan must be valid JSON");
        }
        if (plan == null || !"confirmed".equals(plan.path("status").asText(null))) {
            throw new PptJobStateException("fill plan status must be confirmed");
        }
        Path planPath = planPath(job);
        atomicWrite(planPath, writeJsonBytes(plan));
        TemplateFillPlanMetadata metadata = new TemplateFillPlanMetadata(
                plan.path("version").asInt(1),
                TemplateFillPlanDigest.compute(bytes),
                "confirmed",
                "debug",
                Instant.now());
        writeMetadata(job, metadata);
        return planPath;
    }

    public Optional<TemplateFillPlanMetadata> readMetadata(PptJob job) {
        Path metaPath = metaPath(job);
        if (!Files.isRegularFile(metaPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(metaPath.toFile());
            Instant approvedAt = root.hasNonNull("approvedAt")
                    ? Instant.parse(root.get("approvedAt").asText())
                    : null;
            return Optional.of(new TemplateFillPlanMetadata(
                    root.path("version").asInt(0),
                    root.path("digest").asText(null),
                    root.path("status").asText(null),
                    root.path("confirmationId").asText(null),
                    approvedAt));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** 查找任务工作区内已保存的 confirmed 计划路径。 */
    public Optional<Path> findConfirmedPlan(PptJob job) {
        Path planPath = planPath(job);
        if (!Files.isRegularFile(planPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(planPath.toFile());
            if ("confirmed".equalsIgnoreCase(root.path("status").asText(null))) {
                return Optional.of(planPath);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    public Optional<Path> findDraftPlan(PptJob job) {
        Path planPath = planPath(job);
        if (!Files.isRegularFile(planPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(planPath.toFile());
            if ("draft".equalsIgnoreCase(root.path("status").asText(null))) {
                return Optional.of(planPath);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    public boolean hasApprovedRecord(PptJob job) {
        return readMetadata(job)
                .filter(meta -> "confirmed".equalsIgnoreCase(meta.status()))
                .isPresent();
    }

    private void writeMetadata(PptJob job, TemplateFillPlanMetadata metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", metadata.version());
        root.put("digest", metadata.digest());
        root.put("status", metadata.status());
        if (metadata.confirmationId() != null) {
            root.put("confirmationId", metadata.confirmationId());
        }
        if (metadata.approvedAt() != null) {
            root.put("approvedAt", metadata.approvedAt().toString());
        }
        Path metaPath = metaPath(job);
        ensureInside(job, metaPath);
        try {
            Files.createDirectories(analysisDir(job));
            atomicWrite(metaPath, writeJsonBytes(root));
        } catch (IOException ex) {
            throw new PptJobStateException("failed to store fill plan metadata");
        }
    }

    private Path analysisDir(PptJob job) {
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        return workspace.resolve("analysis").normalize();
    }

    private Path planPath(PptJob job) {
        Path path = analysisDir(job).resolve(PLAN_FILE).normalize();
        ensureInside(job, path);
        return path;
    }

    private Path metaPath(PptJob job) {
        Path path = analysisDir(job).resolve(META_FILE).normalize();
        ensureInside(job, path);
        return path;
    }

    private void ensureInside(PptJob job, Path target) {
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        if (!target.startsWith(workspace)) {
            throw new PptJobStateException("fill plan path escapes job workspace");
        }
    }

    private void enforceSize(byte[] bytes) {
        if (bytes.length > properties.templateFillPlanMaxBytes()) {
            throw new PptJobStateException("fill plan exceeds configured size limit");
        }
    }

    private byte[] writeJsonBytes(JsonNode node) {
        try {
            return (objectMapper.writeValueAsString(node) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize fill plan");
        }
    }

    private void atomicWrite(Path target, byte[] bytes) {
        Path parent = target.getParent();
        Path temporaryPath = parent.resolve(target.getFileName() + ".tmp").normalize();
        try {
            Files.createDirectories(parent);
            Files.write(temporaryPath, bytes);
            try {
                Files.move(temporaryPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryPath, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // preserve original failure
            }
            throw new PptJobStateException("failed to store fill plan");
        }
    }
}
