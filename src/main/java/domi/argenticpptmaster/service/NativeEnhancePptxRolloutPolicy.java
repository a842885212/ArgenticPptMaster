package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.NativeEnhancePptxProductionProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptNativeEnhanceAccessException;
import domi.argenticpptmaster.exception.PptNativeEnhanceUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 新建任务的服务端灰度门禁。
 */
@Component
public class NativeEnhancePptxRolloutPolicy {

    private final PptMasterProperties properties;
    private final NativeEnhancePptxTelemetry telemetry;

    public NativeEnhancePptxRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public NativeEnhancePptxRolloutPolicy(PptMasterProperties properties, NativeEnhancePptxTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
    }

    /**
     * 在任何任务 ID / 工作区 / 上传持久化之前调用。
     *
     * @param access 可信访问上下文；null 或缺身份时 fail closed
     */
    public void assertCreationAllowed(PptAccessContext access) {
        NativeEnhancePptxProductionProperties production = properties.nativeEnhancePptx().production();
        if (!production.enabled()) {
            recordRejected();
            throw new PptNativeEnhanceUnavailableException();
        }
        if (access == null || access.isInternalService()) {
            recordRejected();
            throw new PptNativeEnhanceAccessException("native-enhance-pptx creation requires authenticated caller");
        }
        if (access.hasRole(production.adminRole())) {
            return;
        }
        if (production.allowedTenants().contains(access.tenantId())) {
            return;
        }
        recordRejected();
        throw new PptNativeEnhanceAccessException("native-enhance-pptx creation is not permitted for this caller");
    }

    private void recordRejected() {
        if (telemetry != null) {
            telemetry.recordCreation(NativeEnhancePptxTelemetry.CreationOutcome.REJECTED);
        }
    }

    public boolean isExecutionStopped() {
        return properties.nativeEnhancePptx().production().executionStopEnabled();
    }
}
