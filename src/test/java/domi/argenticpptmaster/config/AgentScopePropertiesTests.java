package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.AgentScopeProperties.Execution;
import domi.argenticpptmaster.config.AgentScopeProperties.ModelGroup;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentScopeProperties} 的单元测试。
 * <p>
 * 验证旧配置兼容折叠、新模型组配置解析、执行策略默认值等行为。
 * </p>
 */
class AgentScopePropertiesTests {

    /**
     * 验证未配置模型组时，effectivePrimary 由顶层字段折叠而成。
     */
    @Test
    void legacyFieldsFoldIntoEffectivePrimary() {
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "gpt5.4",
                "sk-test",
                "http://localhost:4000/v1",
                12,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                null,
                null,
                null);

        ModelGroup primary = properties.effectivePrimary();
        assertThat(primary.normalizedProvider()).isEqualTo("openai");
        assertThat(primary.modelName()).isEqualTo("gpt5.4");
        assertThat(primary.apiKey()).isEqualTo("sk-test");
        assertThat(primary.baseUrl()).isEqualTo("http://localhost:4000/v1");
        assertThat(properties.effectiveFallbacks()).isEmpty();
    }

    /**
     * 验证显式配置 primary 时，effectivePrimary 返回显式配置。
     */
    @Test
    void explicitPrimaryOverridesLegacyFields() {
        ModelGroup primary = new ModelGroup("dashscope", "qwen-max", "sk-ds", "https://dashscope.example.com/v1");
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "gpt5.4",
                "sk-test",
                "http://localhost:4000/v1",
                12,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                primary,
                null,
                null);

        assertThat(properties.effectivePrimary()).isEqualTo(primary);
    }

    /**
     * 验证显式配置 fallbacks 时，effectiveFallbacks 过滤掉无效条目。
     */
    @Test
    void effectiveFallbacksFiltersInvalidGroups() {
        ModelGroup validFallback = new ModelGroup("openai", "gpt-4o-mini", "sk-fb", "http://localhost:4000/v1");
        ModelGroup invalidFallback = new ModelGroup(null, "no-provider", null, null);
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "gpt5.4",
                "sk-test",
                null,
                12,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                null,
                List.of(validFallback, invalidFallback),
                null);

        List<ModelGroup> fallbacks = properties.effectiveFallbacks();
        assertThat(fallbacks).containsExactly(validFallback);
    }

    /**
     * 验证执行策略默认值。
     */
    @Test
    void executionDefaultsAreApplied() {
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "gpt5.4",
                null,
                null,
                12,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                null,
                null,
                null);

        Execution execution = properties.execution();
        assertThat(execution.maxAttempts()).isEqualTo(3);
        assertThat(execution.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(execution.maxBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(execution.timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    /**
     * 验证自定义执行策略被正确保留。
     */
    @Test
    void customExecutionIsPreserved() {
        Execution execution = new Execution(5, Duration.ofMillis(500), Duration.ofSeconds(60), Duration.ofMinutes(3));
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "gpt5.4",
                null,
                null,
                12,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                null,
                null,
                execution);

        assertThat(properties.execution()).isEqualTo(execution);
    }
}
