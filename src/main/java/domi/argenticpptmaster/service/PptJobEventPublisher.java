package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJobEvent;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE（Server-Sent Events）事件发布器。
 * <p>
 * 为每个任务维护一组 {@link SseEmitter} 订阅者，
 * 当 {@link #publish} 被调用时将事件推送给所有订阅的客户端。
 * 自动处理客户端断连、超时和错误场景，清理失效的 Emitter。
 * </p>
 */
@Component
public class PptJobEventPublisher {

    private final ConcurrentMap<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 为指定任务注册 SSE 订阅。
     * <p>
     * 使用 {@code timeout=0} 表示不超时，由客户端自行关闭连接。
     * </p>
     *
     * @param jobId 任务 ID
     * @return 可用于客户端长连接的 SseEmitter
     */
    public SseEmitter subscribe(UUID jobId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(ignored -> remove(jobId, emitter));
        return emitter;
    }

    /**
     * 向指定任务的所有订阅者推送事件。
     * <p>
     * 发送失败（客户端断连）的 Emitter 会被自动移除。
     * </p>
     *
     * @param jobId 任务 ID
     * @param event 要推送的事件
     */
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

    /**
     * 移除失效的 Emitter。
     *
     * @param jobId   任务 ID
     * @param emitter 待移除的 Emitter
     */
    private void remove(UUID jobId, SseEmitter emitter) {
        Set<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) {
            jobEmitters.remove(emitter);
        }
    }
}
