package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * PPT 任务单个业务节点的执行记录。
 *
 * <p>
 * 每个 {@link PptJobNode} 对应一条执行记录，保存该节点的状态、时间戳、错误消息
 * 以及结构化摘要。摘要可用于恢复时快速判断 checkpoint 是否仍然有效，例如
 * 导出文件名、图片失败计数等。
 * </p>
 *
 * @param node          业务节点
 * @param status        节点状态
 * @param startedAt     节点开始执行时间，未开始时为空
 * @param completedAt   节点完成或失败时间，未结束时为空
 * @param errorMessage  节点失败原因，仅在失败时非空
 * @param summary       节点完成后的结构化摘要，由调用方按需写入
 * @author zhangtianhao
 * @since 2026-07-09
 */
public record PptNodeExecution(
        PptJobNode node,
        PptJobNodeStatus status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        Map<String, Object> summary) {

    /**
     * 创建一条处于 {@link PptJobNodeStatus#PENDING} 状态的节点执行记录。
     *
     * @param node 业务节点
     * @return 初始执行记录
     */
    public static PptNodeExecution pending(PptJobNode node) {
        Objects.requireNonNull(node, "node must not be null");
        return new PptNodeExecution(node, PptJobNodeStatus.PENDING, null, null, null, Map.of());
    }

    /**
     * 标记节点为运行中。
     *
     * @return 更新后的执行记录
     */
    public PptNodeExecution start() {
        return new PptNodeExecution(node, PptJobNodeStatus.RUNNING, Instant.now(), null, null, summary);
    }

    /**
     * 标记节点为已完成，并可附带结构化摘要。
     *
     * @param summary 完成摘要，允许为 null
     * @return 更新后的执行记录
     */
    public PptNodeExecution complete(Map<String, Object> summary) {
        return new PptNodeExecution(
                node,
                PptJobNodeStatus.COMPLETED,
                startedAt,
                Instant.now(),
                null,
                summary == null ? Map.of() : Map.copyOf(summary));
    }

    /**
     * 标记节点为失败，并记录错误原因。
     *
     * @param message 失败原因
     * @return 更新后的执行记录
     */
    public PptNodeExecution fail(String message) {
        return new PptNodeExecution(
                node,
                PptJobNodeStatus.FAILED,
                startedAt,
                Instant.now(),
                message,
                summary);
    }

    /**
     * 标记节点为等待人工确认。
     *
     * @return 更新后的执行记录
     */
    public PptNodeExecution waitForConfirmation() {
        return new PptNodeExecution(
                node,
                PptJobNodeStatus.WAITING_CONFIRMATION,
                startedAt,
                null,
                null,
                summary);
    }

    /**
     * 标记节点为跳过。
     *
     * @return 更新后的执行记录
     */
    public PptNodeExecution skip() {
        return new PptNodeExecution(node, PptJobNodeStatus.SKIPPED, null, Instant.now(), null, Map.of());
    }
}
