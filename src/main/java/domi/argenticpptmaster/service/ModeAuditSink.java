package domi.argenticpptmaster.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模式无关的结构化审计日志写入器。
 * <p>
 * logger 名与消息前缀由调用方提供，保证各模式外观保留既有运维契约。
 * </p>
 */
public final class ModeAuditSink {

    private final Logger audit;
    private final String messagePrefix;

    public ModeAuditSink(String loggerName, String messagePrefix) {
        this.audit = LoggerFactory.getLogger(loggerName);
        this.messagePrefix = messagePrefix;
    }

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
        audit.info("{} {}", messagePrefix, payload);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
