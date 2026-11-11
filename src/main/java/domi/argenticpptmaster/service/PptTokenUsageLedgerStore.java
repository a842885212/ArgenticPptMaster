package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptModelTokenUsage;
import domi.argenticpptmaster.domain.PptTokenUsage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务 workspace 内 append-only chat token usage ledger（{@code usage/ledger.jsonl}）。
 * <p>
 * 写入失败只记日志，不抛出以打断 PPT 主流程。
 * </p>
 */
@Component
public class PptTokenUsageLedgerStore {

    public static final String RELATIVE_LEDGER_PATH = "usage/ledger.jsonl";

    private static final Logger log = LoggerFactory.getLogger(PptTokenUsageLedgerStore.class);

    private final ObjectMapper objectMapper;

    public PptTokenUsageLedgerStore() {
        this(new ObjectMapper());
    }

    public PptTokenUsageLedgerStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 追加一条成功计入的 chat usage 记录。
     *
     * @param cachedTokens    可选；null 时不写字段
     * @param reasoningTokens 可选；null 时不写字段
     * @param provider        可选 provider 标识
     */
    public void append(
            PptJob job,
            String modelName,
            int inputTokens,
            int outputTokens,
            Integer cachedTokens,
            Integer reasoningTokens,
            String provider) {
        if (job == null || modelName == null || modelName.isBlank()) {
            return;
        }
        Path workspace = job.workspacePath();
        if (workspace == null) {
            return;
        }
        Path ledgerPath = ledgerPath(workspace);
        try {
            ensureInsideWorkspace(workspace, ledgerPath);
            ObjectNode record = objectMapper.createObjectNode();
            record.put("ts", Instant.now().toString());
            record.put("jobId", job.id().toString());
            record.put("workflowMode", job.workflowMode().name());
            record.put("model", modelName.trim());
            record.put("inputTokens", Math.max(0, inputTokens));
            record.put("outputTokens", Math.max(0, outputTokens));
            record.put("totalTokens", Math.max(0, inputTokens) + Math.max(0, outputTokens));
            if (cachedTokens != null) {
                record.put("cachedTokens", Math.max(0, cachedTokens));
            }
            if (reasoningTokens != null) {
                record.put("reasoningTokens", Math.max(0, reasoningTokens));
            }
            if (provider != null && !provider.isBlank()) {
                record.put("provider", provider.trim());
            }
            record.put("ownershipDigest", ownershipDigest(job));
            String line = objectMapper.writeValueAsString(record) + System.lineSeparator();
            synchronized (lockKey(ledgerPath)) {
                Files.createDirectories(ledgerPath.getParent());
                Files.writeString(
                        ledgerPath,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (RuntimeException | IOException ex) {
            log.warn(
                    "ppt_token_usage_ledger_append_failed: jobId={}, model={}, path={}, message={}",
                    job.id(),
                    modelName,
                    ledgerPath,
                    ex.getMessage());
        }
    }

    /** 从 workspace ledger 重建与 API 语义一致的 totals / byModel。 */
    public PptTokenUsage summarize(Path workspace) {
        return summarize(workspace, null);
    }

    /**
     * 从 workspace ledger 重建汇总；{@code jobId} 非空时仅计入该任务行。
     */
    public PptTokenUsage summarize(Path workspace, UUID jobId) {
        if (workspace == null) {
            return PptTokenUsage.empty();
        }
        Path ledgerPath = ledgerPath(workspace);
        if (!Files.isRegularFile(ledgerPath)) {
            return PptTokenUsage.empty();
        }
        Map<String, PptModelTokenUsage> byModel = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(ledgerPath, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (jobId != null) {
                    String recordedJobId = text(node, "jobId");
                    if (!jobId.toString().equals(recordedJobId)) {
                        continue;
                    }
                }
                String model = text(node, "model");
                if (model == null || model.isBlank()) {
                    continue;
                }
                int input = node.path("inputTokens").asInt(0);
                int output = node.path("outputTokens").asInt(0);
                PptModelTokenUsage current = byModel.getOrDefault(model, PptModelTokenUsage.zero());
                byModel.put(model, current.plus(Math.max(0, input), Math.max(0, output)));
            }
        } catch (RuntimeException | IOException ex) {
            log.warn(
                    "ppt_token_usage_ledger_summarize_failed: path={}, message={}",
                    ledgerPath,
                    ex.getMessage());
            return PptTokenUsage.empty();
        }
        return PptTokenUsage.fromByModel(byModel);
    }

    public static Path ledgerPath(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(RELATIVE_LEDGER_PATH).normalize();
    }

    private static String ownershipDigest(PptJob job) {
        String subject = job.ownerSubjectId().orElse("");
        String tenant = job.ownerTenantId().orElse("");
        if (subject.isBlank() || tenant.isBlank()) {
            return "";
        }
        return ModeLifecycleStore.digestOwnership(subject, tenant);
    }

    private static void ensureInsideWorkspace(Path workspace, Path target) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedWorkspace)) {
            throw new IllegalStateException("token usage ledger path escapes job workspace");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Object lockKey(Path ledgerPath) {
        return ledgerPath.toAbsolutePath().normalize().toString().intern();
    }
}
