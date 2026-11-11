package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.config.TemplateFillProductionProperties;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模板填充新建任务的服务端灰度门禁。
 */
@Component
public class TemplateFillRolloutPolicy {

    private final ModeRolloutGate gate;

    public TemplateFillRolloutPolicy(PptMasterProperties properties) {
        this(properties, null);
    }

    @Autowired
    public TemplateFillRolloutPolicy(PptMasterProperties properties, TemplateFillTelemetry telemetry) {
        this.gate = new ModeRolloutGate(new TemplateFillRolloutProfile(properties, telemetry));
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

    private static final class TemplateFillRolloutProfile implements ModeProductionProfile {

        private final PptMasterProperties properties;
        private final TemplateFillTelemetry telemetry;

        private TemplateFillRolloutProfile(PptMasterProperties properties, TemplateFillTelemetry telemetry) {
            this.properties = properties;
            this.telemetry = telemetry;
        }

        private TemplateFillProductionProperties production() {
            return properties.templateFillProduction();
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
            return "template-fill creation requires authenticated caller";
        }

        @Override
        public String callerNotPermittedMessage() {
            return "template-fill creation is not permitted for this caller";
        }

        @Override
        public RuntimeException unavailableException() {
            return new PptTemplateFillUnavailableException();
        }

        @Override
        public RuntimeException accessDeniedException(String message) {
            return new PptTemplateFillAccessException(message);
        }

        @Override
        public void recordCreationRejected() {
            if (telemetry != null) {
                telemetry.recordCreation(TemplateFillTelemetry.CreationOutcome.REJECTED);
            }
        }
    }
}
