package domi.argenticpptmaster.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope AI 框架的配置属性。
 * <p>
 * 对应 {@code agentscope.*} 前缀的 Spring Boot 配置项。
 * 支持 OpenAI、DashScope（通义千问）和 Ollama 三种模型提供商。
 * </p>
 * <p>
 * 为提升生成服务的可用性，本配置同时支持：
 * </p>
 * <ul>
 *   <li>单一模型兼容模式：沿用历史顶层 {@code provider/modelName/apiKey/baseUrl} 字段</li>
 *   <li>模型组模式：显式配置 {@code primary} 主模型组与 {@code fallbacks} 备用模型组</li>
 *   <li>执行策略：统一配置超时、重试退避等参数</li>
 * </ul>
 *
 * @param provider        模型提供商，可选值：openai（默认）、dashscope、ollama
 * @param modelName       模型名称，如 gpt-4o、qwen-max 等
 * @param apiKey          API 密钥（Ollama 不需要）
 * @param baseUrl         自定义 API 地址（可选，用于代理或私有部署）
 * @param maxIters        代理最大迭代轮数，默认 12
 * @param sessionStorePath 代理会话状态持久化路径，默认 var/ppt-master/agent-sessions
 * @param serviceUserId   服务用户标识，默认 ppt-master-service
 * @param primary         主模型组配置，未显式配置时由顶层字段折叠而成
 * @param fallbacks       备用模型组列表，主模型组失败时按顺序切换
 * @param execution       模型请求执行策略（重试、退避、超时）
 */
@ConfigurationProperties(prefix = "agentscope")
public record AgentScopeProperties(
        String provider,
        String modelName,
        String apiKey,
        String baseUrl,
        Integer maxIters,
        Path sessionStorePath,
        String serviceUserId,
        ModelGroup primary,
        List<ModelGroup> fallbacks,
        Execution execution) {

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
        if (fallbacks == null) {
            fallbacks = Collections.emptyList();
        }
        if (execution == null) {
            execution = Execution.defaults();
        }
    }

    /**
     * 返回生效的主模型组。
     * <p>
     * 如果未显式配置 {@code primary}，则将顶层 {@code provider/modelName/apiKey/baseUrl}
     * 折叠为一个主模型组，保持对历史配置的向后兼容。
     * </p>
     *
     * @return 主模型组，不会为 null
     */
    public ModelGroup effectivePrimary() {
        if (primary != null) {
            return primary;
        }
        return new ModelGroup(provider, modelName, apiKey, baseUrl);
    }

    /**
     * 返回所有生效的 fallback 模型组（已过滤掉无效条目）。
     *
     * @return fallback 模型组列表，不会为 null
     */
    public List<ModelGroup> effectiveFallbacks() {
        return fallbacks.stream()
                .filter(ModelGroup::isValid)
                .toList();
    }

    /**
     * 单个模型组配置。
     *
     * @param provider 模型提供商
     * @param modelName 模型名称
     * @param apiKey API 密钥
     * @param baseUrl 自定义 API 地址
     */
    public record ModelGroup(String provider, String modelName, String apiKey, String baseUrl) {

        /**
         * 判断该模型组是否可用。
         * <p>
         * provider 与 modelName 为必填项；apiKey/baseUrl 依具体提供商而定。
         * </p>
         *
         * @return true 表示可用
         */
        public boolean isValid() {
            return provider != null && !provider.isBlank()
                    && modelName != null && !modelName.isBlank();
        }

        /**
         * 返回标准化后的 provider 名称（小写、去首尾空白）。
         *
         * @return provider 名称
         */
        public String normalizedProvider() {
            return provider == null ? "" : provider.trim().toLowerCase();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ModelGroup other)) {
                return false;
            }
            return Objects.equals(provider, other.provider)
                    && Objects.equals(modelName, other.modelName)
                    && Objects.equals(apiKey, other.apiKey)
                    && Objects.equals(baseUrl, other.baseUrl);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, modelName, apiKey, baseUrl);
        }
    }

    /**
     * 模型请求执行策略。
     *
     * @param maxAttempts  最大尝试次数（含首次），默认 3
     * @param initialBackoff 初始退避间隔，默认 1 秒
     * @param maxBackoff   最大退避间隔，默认 10 秒
     * @param timeout      单次模型请求超时，默认 120 秒
     */
    public record Execution(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration timeout) {

        public Execution {
            if (maxAttempts == null || maxAttempts < 1) {
                maxAttempts = 3;
            }
            if (initialBackoff == null || initialBackoff.isNegative()) {
                initialBackoff = Duration.ofSeconds(1);
            }
            if (maxBackoff == null || maxBackoff.isNegative()) {
                maxBackoff = Duration.ofSeconds(10);
            }
            if (timeout == null || timeout.isNegative()) {
                timeout = Duration.ofSeconds(120);
            }
        }

        /**
         * 返回默认执行策略。
         *
         * @return 默认策略
         */
        public static Execution defaults() {
            return new Execution(null, null, null, null);
        }
    }
}
