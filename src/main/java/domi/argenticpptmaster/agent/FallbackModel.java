package domi.argenticpptmaster.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.exception.OpenAIException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * 调用时优先使用主模型；当主模型因网络/传输/服务端错误，或当前模型组不可用时，
 * 自动按顺序切换到下一个备用模型组，直到成功或全部失败。
 * </p>
 * <p>
 * 该包装器区分两种错误语义：
 * </p>
 * <ul>
 *   <li><b>可恢复错误</b>（网络、超时、5xx、429）：同模型组允许重试，也允许跨模型 fallback；</li>
 *   <li><b>模型组级不可用错误</b>（401 鉴权失败）：不建议在同模型组内反复重试，
 *       但允许切换到下一个模型组尝试；</li>
 *   <li><b>不可恢复错误</b>（配置错误、请求参数错误等）：直接抛出，避免无意义切换。</li>
 * </ul>
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
     * 允许切换的场景包括：
     * </p>
     * <ul>
     *   <li>网络/传输异常、超时；</li>
     *   <li>服务端 5xx、限流 429；</li>
     *   <li>AgentScope 对底层超时/连接错误的包装异常（如 {@link ModelException}）；</li>
     *   <li>当前模型组不可用的 401 鉴权失败（仅在跨模型 fallback 时允许，不参与同模型组重试）。</li>
     * </ul>
     * <p>
     * 不允许切换的场景包括：配置错误、请求参数错误（如 400）等。
     * </p>
     *
     * @param error 捕获的异常
     * @return true 表示允许 fallback
     */
    boolean isFallbackEligible(Throwable error) {
        Throwable current = unwrapThrowable(error);
        while (current != null) {
            if (isRecoverableThrowable(current)) {
                return true;
            }
            if (isModelGroupUnavailable(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 解包 Reactor 包装异常，尽量还原业务可判定的根异常。
     *
     * @param error 原始异常
     * @return 解包后的异常；若无法解包则返回原异常
     */
    private Throwable unwrapThrowable(Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        return unwrapped == null ? error : unwrapped;
    }

    /**
     * 判断单个异常节点是否属于“可恢复错误”。
     * <p>
     * 可恢复错误适合在同模型组内重试，也允许跨模型 fallback。
     * </p>
     *
     * @param error 当前异常节点
     * @return true 表示当前异常节点允许 fallback
     */
    private boolean isRecoverableThrowable(Throwable error) {
        // 网络/传输/超时类错误允许 fallback
        if (error instanceof SocketException
                || error instanceof SocketTimeoutException
                || error instanceof TimeoutException) {
            return true;
        }
        // OpenAI/AgentScope 内部服务端异常通常对应 5xx 或连接错误，允许 fallback
        if (error instanceof OpenAIException openAiEx) {
            return isRecoverableOpenAiException(openAiEx);
        }
        // AgentScope 会把超时/连接错误包装为 ModelException，需要基于消息或 cause 链判定。
        if (error instanceof ModelException modelException) {
            return hasRecoverableMessage(modelException.getMessage());
        }
        return false;
    }

    /**
     * 判断单个异常节点是否属于“模型组级不可用错误”。
     * <p>
     * 这类错误不适合在同模型组内反复重试（因为再次重试仍会失败），
     * 但允许切换到下一个模型组尝试。
     * </p>
     *
     * @param error 当前异常节点
     * @return true 表示当前异常节点允许跨模型 fallback
     */
    private boolean isModelGroupUnavailable(Throwable error) {
        if (error instanceof OpenAIException openAiEx) {
            return isModelGroupUnavailableOpenAiException(openAiEx);
        }
        return false;
    }

    /**
     * 判断 OpenAI 兼容异常是否属于可恢复错误。
     * <p>
     * 5xx 服务端错误与 429 限流错误，既允许同模型组重试，也允许跨模型 fallback。
     * </p>
     *
     * @param openAiEx OpenAI 兼容异常
     * @return true 表示允许 fallback
     */
    private boolean isRecoverableOpenAiException(OpenAIException openAiEx) {
        Integer statusCode = openAiEx.getStatusCode();
        if (statusCode != null) {
            return statusCode >= 500 || statusCode == 429;
        }
        String message = openAiEx.getMessage();
        if (message == null) {
            return true;
        }
        return hasRecoverableMessage(message);
    }

    /**
     * 判断 OpenAI 兼容异常是否属于模型组级不可用错误。
     * <p>
     * 401 表示当前模型组的鉴权/权限/路由不可用，切换到另一个模型组可能成功。
     * 注意：这不代表 401 会在同模型组内重试。
     * </p>
     *
     * @param openAiEx OpenAI 兼容异常
     * @return true 表示允许跨模型 fallback
     */
    private boolean isModelGroupUnavailableOpenAiException(OpenAIException openAiEx) {
        Integer statusCode = openAiEx.getStatusCode();
        return statusCode != null && statusCode == 401;
    }

    /**
     * 根据异常消息中的关键字判断是否属于可恢复的传输/超时错误。
     *
     * @param message 异常消息
     * @return true 表示消息命中了可恢复错误关键字
     */
    private boolean hasRecoverableMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("connection error")
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("transport error")
                || lower.contains("timeout")
                || lower.contains("timed out");
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
