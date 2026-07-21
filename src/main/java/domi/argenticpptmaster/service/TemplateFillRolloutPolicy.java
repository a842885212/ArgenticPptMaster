package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.config.TemplateFillProductionProperties;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模板填充新建任务的服务端灰度门禁。
 */
@Component
public class TemplateFillRolloutPolicy {

    private final PptMasterProperties properties;
    private final TemplateFillTelemetry telemetry;

    public TemplateFillRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public TemplateFillRolloutPolicy(PptMasterProperties properties, TemplateFillTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
    }

    /**
     * 在任何任务 ID / 工作区 / 上传持久化之前调用。
     *
     * @param access 可信访问上下文；null 或缺身份时 fail closed
     */
    public void assertCreationAllowed(PptAccessContext access) {
        TemplateFillProductionProperties production = properties.templateFillProduction();
        if (!production.enabled()) {
            recordRejected();
            throw new PptTemplateFillUnavailableException();
        }
        if (access == null || access.isInternalService()) {
            recordRejected();
            throw new PptTemplateFillAccessException("template-fill creation requires authenticated caller");
        }
        if (access.hasRole(production.adminRole())) {
            return;
        }
        if (production.allowedTenants().contains(access.tenantId())) {
            return;
        }
        recordRejected();
        throw new PptTemplateFillAccessException("template-fill creation is not permitted for this caller");
    }

    private void recordRejected() {
        if (telemetry != null) {
            telemetry.recordCreation(TemplateFillTelemetry.CreationOutcome.REJECTED);
        }
    }

    public boolean isExecutionStopped() {
        return properties.templateFillProduction().executionStopEnabled();
    }
}
