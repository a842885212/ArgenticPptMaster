package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.BeautifyPptxProductionProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptBeautifyUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Beautify 新建任务的服务端灰度门禁。
 */
@Component
public class BeautifyPptxRolloutPolicy {

    private final ModeRolloutGate gate;

    public BeautifyPptxRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public BeautifyPptxRolloutPolicy(PptMasterProperties properties, BeautifyPptxTelemetry telemetry) {
        this.gate = new ModeRolloutGate(new BeautifyRolloutProfile(properties, telemetry));
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

    private static final class BeautifyRolloutProfile implements ModeProductionProfile {

        private final PptMasterProperties properties;
        private final BeautifyPptxTelemetry telemetry;

        private BeautifyRolloutProfile(PptMasterProperties properties, BeautifyPptxTelemetry telemetry) {
            this.properties = properties;
            this.telemetry = telemetry;
        }

        private BeautifyPptxProductionProperties production() {
            return properties.beautifyPptx().production();
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
        public Collection<String> allowedClientIds() {
            return production().allowedTenants();
        }

        @Override
        public boolean executionStopEnabled() {
            return production().executionStopEnabled();
        }

        @Override
        public String authenticatedCallerRequiredMessage() {
            return "beautify-pptx creation requires authenticated caller";
        }

        @Override
        public String callerNotPermittedMessage() {
            return "beautify-pptx creation is not permitted for this caller";
        }

        @Override
        public RuntimeException unavailableException() {
            return new PptBeautifyUnavailableException();
        }

        @Override
        public RuntimeException accessDeniedException(String message) {
            return new PptBeautifyAccessException(message);
        }

        @Override
        public void recordCreationRejected() {
            if (telemetry != null) {
                telemetry.recordCreation(BeautifyPptxTelemetry.CreationOutcome.REJECTED);
            }
        }
    }
}
