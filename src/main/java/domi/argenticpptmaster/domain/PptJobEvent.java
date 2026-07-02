package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;

/**
 * PPT 任务事件的不可变记录。
 * <p>
 * 用于通过 SSE 向客户端推送任务状态变更通知。
 * 每条事件记录包含发生时间、事件类型、描述信息和附带数据。
 * </p>
 *
 * @param occurredAt 事件发生时间
 * @param type       事件类型
 * @param message    事件描述信息
 * @param data       事件附带的结构化数据
 */
public record PptJobEvent(
        Instant occurredAt,
        PptJobEventType type,
        String message,
        Map<String, Object> data) {

    /**
     * 创建无附带数据的事件。
     *
     * @param type    事件类型
     * @param message 事件描述
     * @return 事件实例
     */
    public static PptJobEvent of(PptJobEventType type, String message) {
        return new PptJobEvent(Instant.now(), type, message, Map.of());
    }

    /**
     * 创建含附带数据的事件。
     *
     * @param type    事件类型
     * @param message 事件描述
     * @param data    附带数据（自动执行防御性拷贝，null 视为空 Map）
     * @return 事件实例
     */
    public static PptJobEvent of(PptJobEventType type, String message, Map<String, Object> data) {
        return new PptJobEvent(Instant.now(), type, message, data == null ? Map.of() : Map.copyOf(data));
    }
}
