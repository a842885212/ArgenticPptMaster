package domi.argenticpptmaster.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentscope")
public record AgentScopeProperties(
        String provider,
        String modelName,
        String apiKey,
        String baseUrl,
        Integer maxIters,
        Path sessionStorePath,
        String serviceUserId) {

    public AgentScopeProperties {
        if (provider == null || provider.isBlank()) {
            provider = "openai";
        }
        if (maxIters == null || maxIters < 1) {
            maxIters = 12;
        }
        if (sessionStorePath == null) {
            sessionStorePath = Path.of("var/ppt-master/agent-sessions");
        }
        if (serviceUserId == null || serviceUserId.isBlank()) {
            serviceUserId = "ppt-master-service";
        }
    }
}
