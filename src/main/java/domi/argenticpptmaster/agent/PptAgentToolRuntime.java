package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import java.util.Map;

/** Agent 工具运行时上下文。 */
public record PptAgentToolRuntime(
        PptJob job,
        PptMasterProperties properties,
        PptMasterCommandExecutor executor,
        PptWorkflowEvents events) {

    void completeNode(PptJobNode node, Map<String, Object> summary) {
        if (!node.applicableTo(job.workflowMode())) {
            return;
        }
        PptNodeExecution execution = job.nodeExecution(node);
        if (execution == null || execution.status() == PptJobNodeStatus.PENDING) {
            job.startNode(node);
            events.record(job, PptJobEvent.of(
                    PptJobEventType.NODE_STARTED,
                    "node started: " + node.name(),
                    Map.of("node", node.name())));
        }
        job.completeNode(node, summary);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_COMPLETED,
                "node completed: " + node.name(),
                Map.of("node", node.name(), "summary", summary)));
    }
}
