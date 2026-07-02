package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import org.springframework.stereotype.Component;

@Component
public class PptWorkflowEvents {

    private final PptJobEventPublisher publisher;

    public PptWorkflowEvents(PptJobEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void record(PptJob job, PptJobEvent event) {
        job.addEvent(event);
        publisher.publish(job.id(), event);
    }
}
