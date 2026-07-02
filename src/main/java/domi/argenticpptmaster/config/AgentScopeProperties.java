package domi.argenticpptmaster.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope AI 框架的配置属性。
 * <p>
 * 对应 {@code agentscope.*} 前缀的 Spring Boot 配置项。
 * 支持 OpenAI、DashScope（通义千问）和 Ollama 三种模型提供商。
 * </p>
 *
 * @param provider        模型提供商，可选值：openai（默认）、dashscope、ollama
 * @param modelName       模型名称，如 gpt-4o、qwen-max 等
 * @param apiKey          API 密钥（Ollama 不需要）
 * @param baseUrl         自定义 API 地址（可选，用于代理或私有部署）
 * @param maxIters        代理最大迭代轮数，默认 12
 * @param sessionStorePath 代理会话状态持久化路径，默认 var/ppt-master/agent-sessions
 * @param serviceUserId   服务用户标识，默认 ppt-master-service
 */
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
