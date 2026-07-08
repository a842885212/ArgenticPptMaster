package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.exception.InternalServerException;
import io.agentscope.core.model.exception.OpenAIException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * {@link FallbackModel} 的单元测试。
 * <p>
 * 验证多模型组 fallback 的主成功路径、失败切换路径、不可恢复错误快速失败路径
 * 以及模型链名称展示。
 * </p>
 */
class FallbackModelTests {

    private static final List<Msg> MESSAGES = List.of(new UserMessage("hello"));
    private static final List<ToolSchema> TOOLS = List.of();
    private static final GenerateOptions OPTIONS = GenerateOptions.builder().build();

    /**
     * 验证主模型成功时，fallback 模型不会被调用。
     */
    @Test
    void primarySuccessDoesNotInvokeFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        Model primary = countingModel("primary", primaryCalls, null);
        Model fallback = countingModel("fallback", fallbackCalls, null);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        List<ChatResponse> responses = model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block();

        assertThat(responses).hasSize(1);
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isZero();
        assertThat(model.getModelName()).isEqualTo("fallback[primary -> fallback]");
    }

    /**
     * 验证主模型抛出可恢复异常（InternalServerException）时，自动切换到 fallback 模型。
     */
    @Test
    void fallsBackOnRecoverableInternalServerError() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        RuntimeException primaryError = new InternalServerException(
                "HTTP transport error during streaming: status 500", 500, null, null);
        Model primary = countingModel("primary", new AtomicInteger(), primaryError);
        Model fallback = countingModel("fallback", fallbackCalls, null);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        List<ChatResponse> responses = model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block();

        assertThat(responses).hasSize(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    /**
     * 验证主模型抛出 OpenAIException（连接错误描述）时，自动切换到 fallback 模型。
     */
    @Test
    void fallsBackOnOpenAIConnectionError() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        RuntimeException primaryError = OpenAIException.create(
                500, "OpenAIException - Connection error", null, null);
        Model primary = countingModel("primary", new AtomicInteger(), primaryError);
        Model fallback = countingModel("fallback", fallbackCalls, null);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        List<ChatResponse> responses = model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block();

        assertThat(responses).hasSize(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    /**
     * 验证网络/超时类异常允许 fallback。
     */
    @Test
    void fallsBackOnNetworkAndTimeoutErrors() {
        assertFallbackOnError(new SocketException("connection reset"));
        assertFallbackOnError(new SocketTimeoutException("read timed out"));
        assertFallbackOnError(new TimeoutException("request timeout"));
    }

    /**
     * 验证不可恢复异常（如 IllegalArgumentException）不会触发 fallback，直接失败。
     */
    @Test
    void doesNotFallbackOnNonRecoverableError() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        RuntimeException primaryError = new IllegalArgumentException("bad request");
        Model primary = countingModel("primary", new AtomicInteger(), primaryError);
        Model fallback = countingModel("fallback", fallbackCalls, null);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        assertThatThrownBy(() -> model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad request");
        assertThat(fallbackCalls.get()).isZero();
    }

    /**
     * 验证所有候选模型都失败时，抛出最终异常并包含各模型失败信息。
     */
    @Test
    void failsWithAggregateMessageWhenAllCandidatesFail() {
        RuntimeException primaryError = new InternalServerException(
                "HTTP transport error during streaming: status 500", 500, null, null);
        RuntimeException fallbackError = new InternalServerException(
                "HTTP transport error during streaming: status 503", 503, null, null);
        Model primary = countingModel("primary", new AtomicInteger(), primaryError);
        Model fallback = countingModel("fallback", new AtomicInteger(), fallbackError);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        assertThatThrownBy(() -> model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block())
                .hasMessageContaining("all model candidates failed")
                .hasMessageContaining("primary")
                .hasMessageContaining("fallback")
                .hasMessageContaining("503");
    }

    /**
     * 验证 {@link FallbackModel#isFallbackEligible(Throwable)} 的判定行为。
     */
    @Test
    void fallbackEligibilityMatchesExpectedRecoverableErrors() {
        FallbackModel model = new FallbackModel(List.of(
                countingModel("primary", new AtomicInteger(), null)));

        assertThat(model.isFallbackEligible(new SocketException("broken pipe"))).isTrue();
        assertThat(model.isFallbackEligible(new TimeoutException())).isTrue();
        assertThat(model.isFallbackEligible(OpenAIException.create(
                500, "Connection error", null, null))).isTrue();
        assertThat(model.isFallbackEligible(OpenAIException.create(
                503, "status 503", null, null))).isTrue();

        assertThat(model.isFallbackEligible(new IllegalArgumentException("bad"))).isFalse();
        assertThat(model.isFallbackEligible(OpenAIException.create(
                400, "bad request", null, null))).isFalse();
        assertThat(model.isFallbackEligible(OpenAIException.create(
                401, "unauthorized", null, null))).isFalse();
    }

    private void assertFallbackOnError(Throwable error) {
        AtomicInteger fallbackCalls = new AtomicInteger();
        Model primary = countingModel("primary", new AtomicInteger(), error);
        Model fallback = countingModel("fallback", fallbackCalls, null);

        FallbackModel model = new FallbackModel(List.of(primary, fallback));
        List<ChatResponse> responses = model.stream(MESSAGES, TOOLS, OPTIONS).collectList().block();

        assertThat(responses).hasSize(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    private static Model countingModel(String name, AtomicInteger counter, Throwable error) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                counter.incrementAndGet();
                if (error != null) {
                    return Flux.error(error);
                }
                return Flux.just(ChatResponse.builder().id(name + "-reply").build());
            }

            @Override
            public String getModelName() {
                return name;
            }
        };
    }
}
