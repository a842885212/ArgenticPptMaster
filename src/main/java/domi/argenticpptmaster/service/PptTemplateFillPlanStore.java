package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 在任务工作区内校验并原子保存人工确认的 template-fill 计划。
 */
@Component
public class PptTemplateFillPlanStore {

    private final PptMasterProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public PptTemplateFillPlanStore(PptMasterProperties properties) {
        this(properties, new ObjectMapper());
    }

    PptTemplateFillPlanStore(PptMasterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 confirmed 门禁并将计划原子写入任务 analysis 目录。
     *
     * @param job     目标任务
     * @param jsonPlan 请求体 JSON
     * @return 保存后的计划路径
     */
    public Path storeConfirmedPlan(PptJob job, String jsonPlan) {
        if (jsonPlan == null || jsonPlan.isBlank()) {
            throw new PptJobStateException("fill plan JSON is required");
        }
        byte[] bytes = jsonPlan.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.templateFillPlanMaxBytes()) {
            throw new PptJobStateException("fill plan exceeds configured size limit");
        }
        JsonNode plan;
        try {
            plan = objectMapper.readTree(jsonPlan);
        } catch (IOException | RuntimeException ex) {
            throw new PptJobStateException("fill plan must be valid JSON");
        }
        if (plan == null || !"confirmed".equals(plan.path("status").asText(null))) {
            throw new PptJobStateException("fill plan status must be confirmed");
        }

        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        Path analysisDir = workspace.resolve("analysis").normalize();
        Path planPath = analysisDir.resolve("fill_plan.json").normalize();
        if (!analysisDir.startsWith(workspace) || !planPath.startsWith(workspace)) {
            throw new PptJobStateException("fill plan path escapes job workspace");
        }
        Path temporaryPath = analysisDir.resolve("fill_plan.json.tmp").normalize();
        try {
            Files.createDirectories(analysisDir);
            Files.writeString(temporaryPath, objectMapper.writeValueAsString(plan) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryPath, planPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryPath, planPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return planPath;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // 保留原始存储异常，避免以清理失败覆盖真正原因。
            }
            throw new PptJobStateException("failed to store fill plan");
        }
    }

    /** 查找任务工作区内已保存的 confirmed 计划路径。 */
    public java.util.Optional<Path> findConfirmedPlan(PptJob job) {
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        Path planPath = workspace.resolve("analysis/fill_plan.json").normalize();
        if (planPath.startsWith(workspace) && Files.isRegularFile(planPath)) {
            return java.util.Optional.of(planPath);
        }
        return java.util.Optional.empty();
    }
}
