package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.NativeEnhancePptxProductionProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptNativeEnhanceAccessException;
import domi.argenticpptmaster.exception.PptNativeEnhanceUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 新建任务的服务端灰度门禁。
 */
@Component
public class NativeEnhancePptxRolloutPolicy {

    private final ModeRolloutGate gate;

    public NativeEnhancePptxRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public NativeEnhancePptxRolloutPolicy(PptMasterProperties properties, NativeEnhancePptxTelemetry telemetry) {
        this.gate = new ModeRolloutGate(new NativeEnhanceRolloutProfile(properties, telemetry));
    }

    /**
     * 在任何任务 ID / 工作区 / 上传持久化之前调用。
     *
     * @param access 可信访问上下文；null 或缺身份时 fail closed
     */
    public void assertCreationAllowed(PptAccessContext access) {
        gate.assertCreationAllowed(access);
    }

    public boolean isExecutionStopped() {
        return gate.isExecutionStopped();
    }

    private static final class NativeEnhanceRolloutProfile implements ModeProductionProfile {

        private final PptMasterProperties properties;
        private final NativeEnhancePptxTelemetry telemetry;

        private NativeEnhanceRolloutProfile(PptMasterProperties properties, NativeEnhancePptxTelemetry telemetry) {
            this.properties = properties;
            this.telemetry = telemetry;
        }

        private NativeEnhancePptxProductionProperties production() {
            return properties.nativeEnhancePptx().production();
        }

        @Override
        public boolean enabled() {
            return production().enabled();
        }

        @Override
        public String adminRole() {
            return production().adminRole();
        }

        @Override
        public Collection<String> allowedTenants() {
            return production().allowedTenants();
        }

        @Override
        public boolean executionStopEnabled() {
            return production().executionStopEnabled();
        }

        @Override
        public String authenticatedCallerRequiredMessage() {
            return "native-enhance-pptx creation requires authenticated caller";
        }

        @Override
        public String callerNotPermittedMessage() {
            return "native-enhance-pptx creation is not permitted for this caller";
        }

        @Override
        public RuntimeException unavailableException() {
            return new PptNativeEnhanceUnavailableException();
        }

        @Override
        public RuntimeException accessDeniedException(String message) {
            return new PptNativeEnhanceAccessException(message);
        }

        @Override
        public void recordCreationRejected() {
            if (telemetry != null) {
                telemetry.recordCreation(NativeEnhancePptxTelemetry.CreationOutcome.REJECTED);
            }
        }
    }
}
