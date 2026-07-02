package domi.argenticpptmaster.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

public interface AgentScopeWorkflowAgent {

    Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext runtimeContext);
}
