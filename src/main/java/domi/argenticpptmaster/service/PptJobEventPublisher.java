package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobStatus;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE（Server-Sent Events）事件发布器。
 * <p>
 * 为每个任务维护一组 {@link SseEmitter} 订阅者，
 * 当 {@link #publish} 被调用时将事件推送给所有订阅的客户端。
 * 订阅时会主动回放任务已有的历史事件，确保后加入的客户端也不会遗漏状态；
 * 若任务已处于终态，则立即完成 Emitter，避免客户端无意义挂起。
 * 自动处理客户端断连、超时和错误场景，清理失效的 Emitter。
 * </p>
 */
@Component
public class PptJobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PptJobEventPublisher.class);

    private final ConcurrentMap<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 为指定任务注册 SSE 订阅。
     * <p>
     * 使用 {@code timeout=0} 表示不超时，由服务端在任务终态时主动关闭连接。
     * 注册完成后会立即推送任务当前已记录的所有事件；若任务已结束
     * （{@link PptJobStatus#COMPLETED}、{@link PptJobStatus#FAILED}
     * 或 {@link PptJobStatus#CANCELLED}），则调用 {@link SseEmitter#complete()}
     * 立即关闭连接，防止客户端长时间 pending。
     * </p>
     *
     * @param job 要订阅的 PPT 任务
     * @return 可用于客户端长连接的 SseEmitter
     */
    public SseEmitter subscribe(PptJob job) {
        UUID jobId = job.id();
        boolean terminal = isTerminal(job.status());
        if (terminal) {
            log.info("ppt_event_subscribe_terminal: jobId={}, status={}, eventHistorySize={}",
                    jobId, job.status(), job.events().size());
        } else {
            log.debug("ppt_event_subscribe: jobId={}, status={}, eventHistorySize={}",
                    jobId, job.status(), job.events().size());
        }
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> {
            log.debug("ppt_event_emitter_completed: jobId={}", jobId);
            remove(jobId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("ppt_event_emitter_timeout: jobId={}", jobId);
            remove(jobId, emitter);
        });
        emitter.onError(ex -> {
            if (isClientDisconnect(ex)) {
                log.debug("ppt_event_emitter_disconnected: jobId={}, message={}", jobId, ex.getMessage());
            } else {
                log.warn("ppt_event_emitter_error: jobId={}", jobId, ex);
            }
            remove(jobId, emitter);
        });

        // 回放历史事件，确保订阅者能收到任务当前已产生的所有状态变更
        replayEvents(emitter, job.events());

        // 任务已结束则立即关闭连接，避免无事件导致的无限挂起
        if (terminal) {
            emitter.complete();
        }

        return emitter;
    }

    /**
     * 判断任务状态是否为终态。
     *
     * @param status 任务状态
     * @return 若状态为 COMPLETED、FAILED 或 CANCELLED 则返回 true
     */
    private boolean isTerminal(PptJobStatus status) {
        return status == PptJobStatus.COMPLETED
                || status == PptJobStatus.FAILED
                || status == PptJobStatus.CANCELLED;
    }

    /**
     * 向 Emitter 回放已有事件列表。
     * <p>
     * 发送过程中若客户端已断连，则捕获 {@link IOException} 并调用
     * {@link SseEmitter#completeWithError(Throwable)} 结束该 Emitter；
     * 后续 {@link #remove(UUID, SseEmitter)} 会在回调中清理该 Emitter。
     * </p>
     *
     * @param emitter 目标 Emitter
     * @param events  历史事件列表
     */
    private void replayEvents(SseEmitter emitter, List<PptJobEvent> events) {
        int sent = 0;
        for (PptJobEvent event : events) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event));
                sent++;
            } catch (Exception ex) {
                logSendFailure("ppt_event_replay_send_failed", ex, null, null, sent, events.size());
                completeWithErrorQuietly(emitter, ex);
                return;
            }
        }
        log.debug("ppt_event_replay_complete: sent={}/{}", sent, events.size());
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
        log.debug("ppt_event_publish: jobId={}, eventType={}, subscriberCount={}",
                jobId, event.type(), jobEmitters.size());
        for (SseEmitter emitter : jobEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event));
            } catch (Exception ex) {
                logSendFailure("ppt_event_publish_send_failed", ex, jobId, event.type().name(), null, null);
                remove(jobId, emitter);
                completeWithErrorQuietly(emitter, ex);
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
            if (jobEmitters.isEmpty()) {
                emitters.remove(jobId, jobEmitters);
            }
        }
    }

    private void completeWithErrorQuietly(SseEmitter emitter, Exception ex) {
        try {
            emitter.completeWithError(ex);
        } catch (RuntimeException completionEx) {
            if (!isClientDisconnect(completionEx)) {
                log.debug("ppt_event_complete_with_error_failed: message={}", completionEx.getMessage(), completionEx);
            }
        }
    }

    private void logSendFailure(
            String logKey,
            Exception ex,
            UUID jobId,
            String eventType,
            Integer sent,
            Integer total) {
        if (isClientDisconnect(ex)) {
            if (jobId == null) {
                log.debug("{}: sent={}/{}, message={}", logKey, sent, total, ex.getMessage());
            } else {
                log.debug("{}: jobId={}, eventType={}, message={}", logKey, jobId, eventType, ex.getMessage());
            }
            return;
        }
        if (jobId == null) {
            log.warn("{}: sent={}/{}", logKey, sent, total, ex);
        } else {
            log.warn("{}: jobId={}, eventType={}", logKey, jobId, eventType, ex);
        }
    }

    private boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.endsWith("ClientAbortException")) {
                return true;
            }
            if (current instanceof IOException ioEx && ioEx.getMessage() != null) {
                String message = ioEx.getMessage();
                if (message.contains("Broken pipe") || message.contains("断开的管道")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
