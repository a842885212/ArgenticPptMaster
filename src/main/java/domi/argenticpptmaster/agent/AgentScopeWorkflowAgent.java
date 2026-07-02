package domi.argenticpptmaster.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * AgentScope 工作流代理接口。
 * <p>
 * 基于 AgentScope 框架的 AI 代理接口，定义 PPT 工作流的核心执行契约。
 * 实现类通过 AgentScope 框架驱动大语言模型（LLM）生成 PPT 内容，
 * 并以 Reactor Flux 形式提供异步事件流，支持工作流状态的可观察性。
 * </p>
 */
public interface AgentScopeWorkflowAgent {

    /**
     * 执行 PPT 工作流并返回事件流。
     * <p>
     * 接收输入消息列表和运行时上下文，驱动 AI 代理按 AgentScope 定义的
     * 工作流执行 PPT 生成任务，返回包含工作流执行过程中各类事件
     * （如 LLM 调用、工具调用、等待用户确认等）的事件流。
     * </p>
     *
     * @param messages       输入消息列表，包含用户指令或工具结果等
     * @param runtimeContext AgentScope 运行时上下文，包含会话和用户信息
     * @return 包含工作流执行过程中事件的事件流
     */
    Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext runtimeContext);
}
