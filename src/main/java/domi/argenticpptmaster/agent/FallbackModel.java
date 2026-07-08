package domi.argenticpptmaster.agent;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.exception.InternalServerException;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.message.Msg;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

/**
 * 多模型组 fallback 包装器。
 * <p>
 * 实现 AgentScope {@link Model} 接口，内部维护按优先级排序的多个模型实例。
 * 调用时优先使用主模型；当主模型因网络/传输/服务端错误失败时，自动按顺序切换
 * 到下一个备用模型组，直到成功或全部失败。
 * </p>
 * <p>
 * 该包装器仅对“可恢复错误”进行 fallback。对配置错误、鉴权失败、请求参数错误等
 * 不可恢复错误会直接抛出，避免无意义切换。
 * </p>
 */
public class FallbackModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModel.class);

    private final List<Model> candidates;

    /**
     * 创建 fallback 模型。
     *
     * @param candidates 按优先级排序的模型候选列表；第一个为主模型，后续为 fallback
     * @throws IllegalArgumentException 当候选列表为空时抛出
     */
    public FallbackModel(List<Model> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("fallback model candidates must not be empty");
        }
        this.candidates = List.copyOf(candidates);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return streamWithFallback(messages, tools, options, 0, new ArrayList<>());
    }

    /**
     * 递归地尝试各个候选模型。
     *
     * @param messages      输入消息
     * @param tools         工具 schema
     * @param options       生成选项
     * @param index         当前尝试的候选模型索引
     * @param priorFailures 之前失败的模型名称及原因，用于最终异常汇总
     * @return 模型响应流
     */
    private Flux<ChatResponse> streamWithFallback(
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            int index,
            List<String> priorFailures) {
        Model current = candidates.get(index);
        String modelName = current.getModelName();
        return current.stream(messages, tools, options)
                .doOnError(error -> log.warn(
                        "model_stream_failed: model={}, index={}/{}, error={}",
                        modelName, index + 1, candidates.size(), error.getMessage()))
                .onErrorResume(error -> {
                    priorFailures.add(modelName + ": " + error.getMessage());
                    if (!isFallbackEligible(error) || index >= candidates.size() - 1) {
                        return Flux.error(finalException(error, priorFailures));
                    }
                    log.warn(
                            "model_fallback: from={} to={}",
                            modelName, candidates.get(index + 1).getModelName());
                    return streamWithFallback(messages, tools, options, index + 1, priorFailures);
                });
    }

    @Override
    public String getModelName() {
        StringBuilder names = new StringBuilder("fallback[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                names.append(" -> ");
            }
            names.append(candidates.get(i).getModelName());
        }
        names.append("]");
        return names.toString();
    }

    /**
     * 判断错误是否允许切换到下一个模型组。
     * <p>
     * 可恢复错误包括：网络/传输异常、超时、服务端 5xx、OpenAI/内部服务器异常等。
     * 不可恢复错误包括：配置错误、客户端 4xx（如鉴权失败、请求格式错误）等。
     * </p>
     *
     * @param error 捕获的异常
     * @return true 表示允许 fallback
     */
    boolean isFallbackEligible(Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        if (unwrapped == null) {
            unwrapped = error;
        }
        // 网络/传输/超时类错误允许 fallback
        if (unwrapped instanceof SocketException
                || unwrapped instanceof SocketTimeoutException
                || unwrapped instanceof TimeoutException) {
            return true;
        }
        // OpenAI/AgentScope 内部服务端异常通常对应 5xx 或连接错误，允许 fallback
        if (unwrapped instanceof OpenAIException openAiEx) {
            Integer statusCode = openAiEx.getStatusCode();
            if (statusCode != null) {
                return statusCode >= 500 || statusCode == 429;
            }
            String message = openAiEx.getMessage();
            if (message == null) {
                return true;
            }
            String lower = message.toLowerCase();
            return lower.contains("connection error")
                    || lower.contains("connection reset")
                    || lower.contains("connection refused")
                    || lower.contains("transport error")
                    || lower.contains("timeout");
        }
        // 默认：对未知错误保守处理，不 fallback
        return false;
    }

    /**
     * 构造最终失败异常。
     * <p>
     * 如果所有候选模型都失败，返回最后一个异常；否则返回包含完整模型链失败信息的
     * {@link IllegalStateException}。
     * </p>
     *
     * @param lastError     最后一个模型抛出的异常
     * @param priorFailures 所有失败模型名称及原因
     * @return 最终异常
     */
    private Throwable finalException(Throwable lastError, List<String> priorFailures) {
        if (lastError == null) {
            return new IllegalStateException("all model candidates failed: " + priorFailures);
        }
        // 如果只有一个失败点，直接保留原异常信息，避免额外包装
        if (priorFailures.size() <= 1) {
            return lastError;
        }
        return new IllegalStateException(
                "all model candidates failed. attempts: " + priorFailures, lastError);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FallbackModel other)) {
            return false;
        }
        return Objects.equals(candidates, other.candidates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidates);
    }
}
