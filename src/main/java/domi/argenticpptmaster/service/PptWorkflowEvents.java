package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import org.springframework.stereotype.Component;

/**
 * PPT 工作流事件记录器。
 * <p>
 * 组合了任务内事件追加和 SSE 推送两个操作，
 * 确保每次记录事件时既保存到任务的事件列表，又推送给订阅的客户端。
 * </p>
 */
@Component
public class PptWorkflowEvents {

    private final PptJobEventPublisher publisher;

    public PptWorkflowEvents(PptJobEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 记录事件到任务并同时通过 SSE 推送。
     *
     * @param job   目标任务
     * @param event 待记录的事件
     */
    public void record(PptJob job, PptJobEvent event) {
        job.addEvent(event);
        publisher.publish(job.id(), event);
    }
}
