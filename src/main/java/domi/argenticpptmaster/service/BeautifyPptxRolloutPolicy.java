package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.BeautifyPptxProductionProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptBeautifyUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Beautify 新建任务的服务端灰度门禁。
 */
@Component
public class BeautifyPptxRolloutPolicy {

    private final PptMasterProperties properties;
    private final BeautifyPptxTelemetry telemetry;

    public BeautifyPptxRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public BeautifyPptxRolloutPolicy(PptMasterProperties properties, BeautifyPptxTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
    }

    /**
     * 在任何任务 ID / 工作区 / 上传持久化之前调用。
     *
     * @param access 可信访问上下文；null 或缺身份时 fail closed
     */
    public void assertCreationAllowed(PptAccessContext access) {
        BeautifyPptxProductionProperties production = properties.beautifyPptx().production();
        if (!production.enabled()) {
            recordRejected();
            throw new PptBeautifyUnavailableException();
        }
        if (access == null || access.isInternalService()) {
            recordRejected();
            throw new PptBeautifyAccessException("beautify-pptx creation requires authenticated caller");
        }
        if (access.hasRole(production.adminRole())) {
            return;
        }
        if (production.allowedTenants().contains(access.tenantId())) {
            return;
        }
        recordRejected();
        throw new PptBeautifyAccessException("beautify-pptx creation is not permitted for this caller");
    }

    private void recordRejected() {
        if (telemetry != null) {
            telemetry.recordCreation(BeautifyPptxTelemetry.CreationOutcome.REJECTED);
        }
    }

    public boolean isExecutionStopped() {
        return properties.beautifyPptx().production().executionStopEnabled();
    }
}
