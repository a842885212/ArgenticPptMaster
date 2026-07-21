package domi.argenticpptmaster.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模板填充结构化审计日志，与业务事件分离。
 * <p>
 * 仅记录动作、任务 ID、身份摘要、结果和稳定原因码；禁止路径、令牌与业务原文。
 * </p>
 */
@Component
public class TemplateFillAuditSink {

    private static final Logger AUDIT = LoggerFactory.getLogger("template-fill-audit");

    public void record(
            String action,
            UUID jobId,
            String subjectDigest,
            String tenantDigest,
            String outcome,
            String reasonCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", sanitize(action));
        payload.put("jobId", jobId == null ? null : jobId.toString());
        payload.put("subjectDigest", sanitize(subjectDigest));
        payload.put("tenantDigest", sanitize(tenantDigest));
        payload.put("outcome", sanitize(outcome));
        payload.put("reasonCode", sanitize(reasonCode));
        AUDIT.info("template_fill_audit {}", payload);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
