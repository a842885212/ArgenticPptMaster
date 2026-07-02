package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptJob;

public interface AgentScopeWorkflowAgentFactory {

    AgentScopeWorkflowAgent create(PptJob job);
}
