package domi.argenticpptmaster.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 结构化审计日志，与业务事件分离。
 * <p>
 * 仅记录动作、任务 ID、身份摘要、结果和稳定原因码；禁止路径、令牌、备注与音频原文。
 * </p>
 */
@Component
public class NativeEnhancePptxAuditSink {

    private final ModeAuditSink sink = new ModeAuditSink("native-enhance-pptx-audit", "native_enhance_pptx_audit");

    public void record(
            String action,
            UUID jobId,
            String subjectDigest,
            String tenantDigest,
            String outcome,
            String reasonCode) {
        sink.record(action, jobId, subjectDigest, tenantDigest, outcome, reasonCode);
    }
}
