package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
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
 * Persists draft/confirmed fill plans and bound service metadata.
 */
@Component
public class PptTemplateFillPlanStore {

    static final String PLAN_FILE = "fill_plan.json";
    static final String META_FILE = "fill_plan.meta.json";
    static final String SERVICE_META_FILE = "fill_plan.service-meta.json";
    static final String RATIONALE_FILE = "fill_plan_rationale.md";
    static final int MAX_RATIONALE_CHARS = 8_000;
    static final String SERVICE_META_SCHEMA = "template_fill_service_meta.v1";

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
        return storeDraftPlan(job, jsonPlan, slideLibraryPath, null);
    }

    public TemplateFillPlanMetadata storeDraftPlan(
            PptJob job, String jsonPlan, Path slideLibraryPath, String serviceMetaJson) {
        TemplateFillConstraints constraints = job.templateConstraints();
        JsonNode validated = validator.parseAndValidateDraft(jsonPlan, slideLibraryPath, constraints);
        int nextVersion = readMetadata(job).map(meta -> meta.version() + 1).orElse(1);
        ObjectNode normalized = validator.normalizeDraft(validated, nextVersion);
        ObjectNode serviceMeta = normalizeServiceMeta(serviceMetaJson, nextVersion, constraints);
        validator.validateCapacityDecisions(serviceMeta.get("capacityDecisions"));
        validator.validateServiceMetaFontAdjustments(serviceMeta.get("fontAdjustments"));

        byte[] planBytes = writeJsonBytes(normalized);
        byte[] serviceMetaBytes = writeJsonBytes(serviceMeta);
        enforceSize(planBytes);
        enforceSize(serviceMetaBytes);
        String digest = TemplateFillPlanDigest.computeCombined(planBytes, serviceMetaBytes);

        Path planPath = planPath(job);
        Path serviceMetaPath = serviceMetaPath(job);
        atomicWrite(planPath, planBytes);
        atomicWrite(serviceMetaPath, serviceMetaBytes);
        TemplateFillPlanMetadata metadata = TemplateFillPlanMetadata.draft(nextVersion, digest);
        writeMetadata(job, metadata);
        job.updateFillPlanStatus(FillPlanStatus.DRAFT, normalized.get("slides").size(), 0, 0);
        updateNativeAggregates(job, normalized, serviceMeta, "VALID");
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
        Path serviceMetaPath = serviceMetaPath(job);
        if (!Files.isRegularFile(planPath)) {
            throw new PptTemplateFillConflictException("fill plan file is missing");
        }
        String actualDigest;
        try {
            byte[] planBytes = Files.readAllBytes(planPath);
            byte[] serviceMetaBytes = Files.isRegularFile(serviceMetaPath)
                    ? Files.readAllBytes(serviceMetaPath)
                    : new byte[0];
            actualDigest = TemplateFillPlanDigest.computeCombined(planBytes, serviceMetaBytes);
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
        byte[] confirmedPlanBytes = writeJsonBytes(confirmed);
        atomicWrite(planPath, confirmedPlanBytes);
        byte[] serviceMetaBytes;
        try {
            serviceMetaBytes = Files.isRegularFile(serviceMetaPath)
                    ? Files.readAllBytes(serviceMetaPath)
                    : new byte[0];
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read fill plan service meta");
        }
        String confirmedDigest = TemplateFillPlanDigest.computeCombined(confirmedPlanBytes, serviceMetaBytes);
        TemplateFillPlanMetadata approved = new TemplateFillPlanMetadata(
                current.version(), confirmedDigest, "confirmed", confirmationId, Instant.now());
        writeMetadata(job, approved);
        job.updateFillPlanStatus(FillPlanStatus.CONFIRMED, confirmed.get("slides").size(), 0, 0);
        return approved;
    }

    /**
     * 校验 confirmed 门禁并将计划原子写入任务 analysis 目录。
     *
     * @deprecated 生产路径应使用 {@link #confirmCurrentDraft}；此方法仅保留调试兼容。
     */
    @Deprecated
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
                plan.path("service_version").asInt(plan.path("version").asInt(1)),
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

    public Optional<JsonNode> readServiceMeta(PptJob job) {
        Path path = serviceMetaPath(job);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(path.toFile()));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public void assertPlanIntegrity(PptJob job) {
        TemplateFillPlanMetadata metadata = readMetadata(job)
                .orElseThrow(() -> new PptTemplateFillConflictException("fill plan metadata is missing"));
        Path planPath = planPath(job);
        Path serviceMetaPath = serviceMetaPath(job);
        try {
            byte[] planBytes = Files.readAllBytes(planPath);
            byte[] serviceMetaBytes = Files.isRegularFile(serviceMetaPath)
                    ? Files.readAllBytes(serviceMetaPath)
                    : new byte[0];
            String digest = TemplateFillPlanDigest.computeCombined(planBytes, serviceMetaBytes);
            if (!digest.equals(metadata.digest())) {
                throw new PptTemplateFillConflictException("fill plan digest mismatch");
            }
        } catch (IOException ex) {
            throw new PptJobStateException("failed to verify fill plan integrity");
        }
    }

    private ObjectNode normalizeServiceMeta(
            String serviceMetaJson, int version, TemplateFillConstraints constraints) {
        ObjectNode root;
        if (serviceMetaJson == null || serviceMetaJson.isBlank()) {
            root = objectMapper.createObjectNode();
        } else {
            try {
                JsonNode parsed = objectMapper.readTree(serviceMetaJson);
                if (parsed == null || !parsed.isObject()) {
                    throw new PptJobStateException("service meta must be a JSON object");
                }
                root = parsed.deepCopy();
            } catch (IOException ex) {
                throw new PptJobStateException("service meta must be valid JSON");
            }
        }
        root.put("schema", SERVICE_META_SCHEMA);
        root.put("version", version);
        ObjectNode constraintsNode = objectMapper.createObjectNode();
        constraintsNode.set("allowedTemplateSlides", objectMapper.valueToTree(constraints.allowedTemplateSlides()));
        constraintsNode.set("excludedTemplateSlides", objectMapper.valueToTree(constraints.excludedTemplateSlides()));
        constraintsNode.put("preserveCover", constraints.preserveCover());
        constraintsNode.put("preserveEnding", constraints.preserveEnding());
        if (constraints.maxSlides() != null) {
            constraintsNode.put("maxSlides", constraints.maxSlides());
        } else {
            constraintsNode.putNull("maxSlides");
        }
        root.set("constraints", constraintsNode);
        if (!root.has("capacityDecisions")) {
            root.putArray("capacityDecisions");
        }
        if (!root.has("fontAdjustments")) {
            root.putArray("fontAdjustments");
        }
        if (!root.has("omittedContent")) {
            root.putArray("omittedContent");
        }
        if (!root.has("splitSuggestions")) {
            root.putArray("splitSuggestions");
        }
        if (!root.has("unsupportedContent")) {
            root.putArray("unsupportedContent");
        }
        if (!root.has("sourceRefs")) {
            root.putObject("sourceRefs");
        }
        return root;
    }

    private void updateNativeAggregates(PptJob job, JsonNode plan, JsonNode serviceMeta, String constraintStatus) {
        int notes = 0;
        int tables = 0;
        int charts = 0;
        for (JsonNode slide : plan.path("slides")) {
            String notesText = slide.path("notes").asText("");
            if (notesText.isBlank()) {
                notesText = slide.path("speaker_notes").asText("");
            }
            if (!notesText.isBlank()) {
                notes++;
            }
            tables += slide.path("table_edits").isArray() ? slide.path("table_edits").size() : 0;
            charts += slide.path("chart_edits").isArray() ? slide.path("chart_edits").size() : 0;
        }
        int capacityRisks = serviceMeta.path("capacityDecisions").isArray()
                ? serviceMeta.path("capacityDecisions").size()
                : 0;
        int fontAdjustments = serviceMeta.path("fontAdjustments").isArray()
                ? serviceMeta.path("fontAdjustments").size()
                : 0;
        job.updateNativePlanAggregates(notes, tables, charts, capacityRisks, fontAdjustments, constraintStatus);
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

    private Path serviceMetaPath(PptJob job) {
        Path path = analysisDir(job).resolve(SERVICE_META_FILE).normalize();
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
