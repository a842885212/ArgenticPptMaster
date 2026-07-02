package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJobEvent;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class PptJobEventPublisher {

    private final ConcurrentMap<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(ignored -> remove(jobId, emitter));
        return emitter;
    }

    public void publish(UUID jobId, PptJobEvent event) {
        Set<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null || jobEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : jobEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event));
            } catch (IOException ex) {
                remove(jobId, emitter);
            }
        }
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        Set<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) {
            jobEmitters.remove(emitter);
        }
    }
}
